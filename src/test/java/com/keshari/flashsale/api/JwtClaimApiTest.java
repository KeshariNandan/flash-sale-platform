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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "flashsale.gate=sql", "flashsale.security.enabled=true", "flashsale.rate-limit.enabled=false" })
@AutoConfigureTestRestTemplate
class JwtClaimApiTest {

	@Autowired TestRestTemplate http;
	@Autowired SaleService sales;
	@Autowired AppUserRepository users;

	FlashSale openSale() {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-JWT", "JWT", new BigDecimal("5.00"),
				now.minusSeconds(1), now.plusSeconds(600), 3, 1, 120));
		return sales.open(sale.getId());
	}

	String registerBuyer() {
		String id = "jwt-" + UUID.randomUUID();
		users.save(new AppUser(id, id + "@example.com", "tok", Instant.now()));
		return id;
	}

	HttpEntity<Map<String, Object>> claim(String bearer) {
		HttpHeaders h = new HttpHeaders();
		h.setBearerAuth(bearer);
		h.set(ClaimController.IDEMPOTENCY_HEADER, UUID.randomUUID().toString());
		return new HttpEntity<>(Map.of("quantity", 1, "clientTimestamp", Instant.now().toString()), h);
	}

	@Test
	void missingAndForgedTokensAre401() {
		FlashSale sale = openSale();
		String url = "/api/v1/sales/" + sale.getId() + "/claims";
		ResponseEntity<ProblemDetail> missing = http.postForEntity(url, claim("not-a-jwt"), ProblemDetail.class);
		assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		HttpHeaders headerOnly = new HttpHeaders();
		headerOnly.set(CurrentBuyerArgumentResolver.HEADER, registerBuyer());
		headerOnly.set(ClaimController.IDEMPOTENCY_HEADER, UUID.randomUUID().toString());
		ResponseEntity<ProblemDetail> noJwt = http.postForEntity(url,
				new HttpEntity<>(Map.of("quantity", 1, "clientTimestamp", Instant.now().toString()), headerOnly),
				ProblemDetail.class);
		assertThat(noJwt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void buyerSubjectIsTheClaimIdentity() {
		FlashSale sale = openSale();
		String buyer = registerBuyer();
		ResponseEntity<Map> accepted = http.postForEntity("/api/v1/sales/" + sale.getId() + "/claims",
				claim(TestTokens.buyer(buyer)), Map.class);
		assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(accepted.getBody().get("status")).isEqualTo("PENDING");
	}

	@Test
	void buyerCannotCallAdminAndAdminCan() {
		ResponseEntity<ProblemDetail> forbidden = http.postForEntity("/api/v1/admin/sales", adminBody(TestTokens.buyer("x")),
				ProblemDetail.class);
		assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		ResponseEntity<Map> created = http.postForEntity("/api/v1/admin/sales", adminBody(TestTokens.admin()), Map.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	HttpEntity<Map<String, Object>> adminBody(String bearer) {
		Instant now = Instant.now();
		HttpHeaders h = new HttpHeaders();
		h.setBearerAuth(bearer);
		return new HttpEntity<>(Map.of("sku", "SKU-A", "name", "Admin", "unitPrice", 1, "startAt", now.toString(),
				"endAt", now.plusSeconds(60).toString(), "initialStock", 1, "perUserLimit", 1, "holdTtlSeconds", 30), h);
	}
}
