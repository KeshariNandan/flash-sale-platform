package com.keshari.flashsale.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import com.keshari.flashsale.application.SaleService;
import com.keshari.flashsale.domain.AppUser;
import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.infrastructure.persistence.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** HTTP contract of the claim endpoint with {@code flashsale.gate=redis}. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "flashsale.gate=redis")
@AutoConfigureTestRestTemplate
class ClaimApiRedisTest {

	@Autowired TestRestTemplate http;
	@Autowired SaleService sales;
	@Autowired AppUserRepository users;

	FlashSale openSale(int stock) {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-HR", "HTTP Redis", new BigDecimal("5.00"),
				now.minusSeconds(1), now.plusSeconds(600), stock, 1, 120));
		return sales.open(sale.getId());
	}

	String buyer() {
		String id = "hr-" + UUID.randomUUID();
		users.save(new AppUser(id, id + "@example.com", "tok", Instant.now()));
		return id;
	}

	HttpEntity<Map<String, Object>> request(String buyer, String key) {
		HttpHeaders h = new HttpHeaders();
		h.set(CurrentBuyerArgumentResolver.HEADER, buyer);
		h.set(ClaimController.IDEMPOTENCY_HEADER, key);
		return new HttpEntity<>(Map.of("quantity", 1, "clientTimestamp", Instant.now().toString()), h);
	}

	@Test
	void acceptedReplayDuplicateAndSoldOutMatchTheSqlContract() {
		FlashSale sale = openSale(1);
		String url = "/api/v1/sales/" + sale.getId() + "/claims";
		String winner = buyer();
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> first = http.postForEntity(url, request(winner, key), Map.class);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

		ResponseEntity<Map> replay = http.postForEntity(url, request(winner, key), Map.class);
		assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(replay.getBody().get("reservationId")).isEqualTo(first.getBody().get("reservationId"));

		ResponseEntity<ProblemDetail> dup = http.postForEntity(url, request(winner, UUID.randomUUID().toString()),
				ProblemDetail.class);
		assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(dup.getBody().getType().toString()).endsWith("/duplicate-claim");

		ResponseEntity<ProblemDetail> soldOut = http.postForEntity(url, request(buyer(), UUID.randomUUID().toString()),
				ProblemDetail.class);
		assertThat(soldOut.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(soldOut.getBody().getType().toString()).endsWith("/sold-out");

		ResponseEntity<Map> stats = http.getForEntity("/api/v1/admin/sales/" + sale.getId() + "/stats", Map.class);
		assertThat(stats.getBody().get("gateRemaining")).isEqualTo(0);
		assertThat(stats.getBody().get("pending")).isEqualTo(1);
		assertThat(stats.getBody().get("oversell")).isEqualTo(0);
	}
}
