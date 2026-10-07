package com.keshari.flashsale.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminSaleApiTest {

	@Autowired
	TestRestTemplate http;

	static Map<String, Object> validSale() {
		Instant now = Instant.now();
		return Map.of("sku", "SKU-API", "name", "API drop", "unitPrice", 25.00, "startAt", now.toString(), "endAt",
				now.plusSeconds(3600).toString(), "initialStock", 100, "perUserLimit", 1, "holdTtlSeconds", 120);
	}

	@Test
	void createOpenCloseLifecycleOverHttp() {
		ResponseEntity<Map> created = http.postForEntity("/api/v1/admin/sales", validSale(), Map.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getHeaders().getLocation()).isNotNull();
		String id = (String) created.getBody().get("id");
		assertThat(created.getBody().get("status")).isEqualTo("SCHEDULED");

		ResponseEntity<Map> opened = http.postForEntity("/api/v1/admin/sales/" + id + "/open", null, Map.class);
		assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(opened.getBody().get("status")).isEqualTo("OPEN");

		// opening twice is an illegal transition -> 409 problem detail with stable type
		ResponseEntity<ProblemDetail> again = http.postForEntity("/api/v1/admin/sales/" + id + "/open", null,
				ProblemDetail.class);
		assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(again.getBody().getType().toString()).endsWith("/illegal-transition");

		ResponseEntity<Map> closed = http.postForEntity("/api/v1/admin/sales/" + id + "/close", null, Map.class);
		assertThat(closed.getBody().get("status")).isEqualTo("CLOSED");

		ResponseEntity<Map> fetched = http.getForEntity("/api/v1/admin/sales/" + id, Map.class);
		assertThat(fetched.getBody().get("status")).isEqualTo("CLOSED");
	}

	@Test
	void validationFailuresReturn400WithFieldErrors() {
		Map<String, Object> bad = Map.of("sku", "", "name", "x", "unitPrice", 0, "startAt", Instant.now().toString(),
				"endAt", Instant.now().toString(), "initialStock", 0);
		ResponseEntity<ProblemDetail> resp = http.postForEntity("/api/v1/admin/sales", bad, ProblemDetail.class);
		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(resp.getBody().getType().toString()).endsWith("/validation-error");
		assertThat(resp.getBody().getProperties()).containsKey("errors");
		@SuppressWarnings("unchecked")
		Map<String, String> errors = (Map<String, String>) resp.getBody().getProperties().get("errors");
		assertThat(errors).containsKeys("sku", "unitPrice", "initialStock");
	}

	@Test
	void domainRuleViolationReturns400() {
		Instant now = Instant.now();
		Map<String, Object> inverted = Map.of("sku", "S", "name", "n", "unitPrice", 1, "startAt",
				now.plusSeconds(10).toString(), "endAt", now.toString(), "initialStock", 1);
		ResponseEntity<ProblemDetail> resp = http.postForEntity("/api/v1/admin/sales", inverted, ProblemDetail.class);
		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(resp.getBody().getType().toString()).endsWith("/invalid-sale");
	}

	@Test
	void unknownSaleReturns404() {
		ResponseEntity<ProblemDetail> resp = http.getForEntity("/api/v1/admin/sales/" + UUID.randomUUID(),
				ProblemDetail.class);
		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(resp.getBody().getType().toString()).endsWith("/not-found");
	}

	@Test
	void statsReportRemainingStockAndZeroOversellOnAFreshSale() {
		ResponseEntity<Map> created = http.postForEntity("/api/v1/admin/sales", validSale(), Map.class);
		String id = (String) created.getBody().get("id");
		http.postForEntity("/api/v1/admin/sales/" + id + "/open", null, Map.class);

		ResponseEntity<Map> stats = http.getForEntity("/api/v1/admin/sales/" + id + "/stats", Map.class);
		assertThat(stats.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(stats.getBody().get("initialStock")).isEqualTo(100);
		assertThat(stats.getBody().get("stockRemaining")).isEqualTo(100);
		assertThat(stats.getBody().get("gateRemaining")).isEqualTo(100);
		assertThat(stats.getBody().get("pending")).isEqualTo(0);
		assertThat(stats.getBody().get("accounted")).isEqualTo(100);
		assertThat(stats.getBody().get("oversell")).isEqualTo(0);
	}

	@Test
	void invariantOnAFreshSaleHolds() {
		ResponseEntity<Map> created = http.postForEntity("/api/v1/admin/sales", validSale(), Map.class);
		String id = (String) created.getBody().get("id");
		http.postForEntity("/api/v1/admin/sales/" + id + "/open", null, Map.class);

		ResponseEntity<Map> report = http.getForEntity("/api/v1/admin/sales/" + id + "/invariant", Map.class);
		assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(report.getBody().get("holds")).isEqualTo(true);
		assertThat(report.getBody().get("accounted")).isEqualTo(100);
		assertThat(report.getBody().get("gateRemaining")).isEqualTo(100);
		assertThat(report.getBody().get("deficit")).isEqualTo(0);
	}

	@Test
	void reconcileReturnsTheSameBalance() {
		ResponseEntity<Map> created = http.postForEntity("/api/v1/admin/sales", validSale(), Map.class);
		String id = (String) created.getBody().get("id");
		http.postForEntity("/api/v1/admin/sales/" + id + "/open", null, Map.class);

		ResponseEntity<Map> report = http.postForEntity("/api/v1/admin/sales/" + id + "/reconcile", null, Map.class);
		assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(report.getBody().get("holds")).isEqualTo(true);
		assertThat(report.getBody().get("accounted")).isEqualTo(100);
	}
}
