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

/** The HTTP contract of the claim endpoint as tabulated in the report. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "flashsale.gate=sql")
@AutoConfigureTestRestTemplate
class ClaimApiTest {

	@Autowired TestRestTemplate http;
	@Autowired SaleService sales;
	@Autowired AppUserRepository users;

	FlashSale openSale(int stock) {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-H", "HTTP", new BigDecimal("5.00"),
				now.minusSeconds(1), now.plusSeconds(600), stock, 1, 120));
		return sales.open(sale.getId());
	}

	String buyer() {
		String id = "h-" + UUID.randomUUID();
		users.save(new AppUser(id, id + "@example.com", "tok", Instant.now()));
		return id;
	}

	HttpEntity<Map<String, Object>> request(String buyer, String key) {
		HttpHeaders h = new HttpHeaders();
		if (buyer != null) {
			h.set(CurrentBuyerArgumentResolver.HEADER, buyer);
		}
		if (key != null) {
			h.set(ClaimController.IDEMPOTENCY_HEADER, key);
		}
		return new HttpEntity<>(Map.of("quantity", 1, "clientTimestamp", Instant.now().toString()), h);
	}

	String claimsUrl(FlashSale sale) {
		return "/api/v1/sales/" + sale.getId() + "/claims";
	}

	@Test
	void acceptedClaimReturns202WithLocationAndReplayReturns200() {
		FlashSale sale = openSale(3);
		String buyer = buyer();
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> first = http.postForEntity(claimsUrl(sale), request(buyer, key), Map.class);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(first.getHeaders().getLocation()).isNotNull();
		assertThat(first.getBody().get("status")).isEqualTo("PENDING");
		String reservationId = (String) first.getBody().get("reservationId");

		ResponseEntity<Map> replay = http.postForEntity(claimsUrl(sale), request(buyer, key), Map.class);
		assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(replay.getBody().get("reservationId")).isEqualTo(reservationId);

		ResponseEntity<Map> status = http.getForEntity("/api/v1/reservations/" + reservationId, Map.class);
		assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(status.getBody().get("status")).isEqualTo("PENDING");
	}

	@Test
	void secondClaimBySameBuyerIsDuplicateAndSoldOutIsReported() {
		FlashSale sale = openSale(1);
		String buyer = buyer();
		http.postForEntity(claimsUrl(sale), request(buyer, UUID.randomUUID().toString()), Map.class);

		ResponseEntity<ProblemDetail> dup = http.postForEntity(claimsUrl(sale),
				request(buyer, UUID.randomUUID().toString()), ProblemDetail.class);
		assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(dup.getBody().getType().toString()).endsWith("/duplicate-claim");

		ResponseEntity<ProblemDetail> soldOut = http.postForEntity(claimsUrl(sale),
				request(buyer(), UUID.randomUUID().toString()), ProblemDetail.class);
		assertThat(soldOut.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(soldOut.getBody().getType().toString()).endsWith("/sold-out");
	}

	@Test
	void missingIdentityIs401AndShortKeyIs400() {
		FlashSale sale = openSale(1);
		ResponseEntity<ProblemDetail> noBuyer = http.postForEntity(claimsUrl(sale),
				request(null, UUID.randomUUID().toString()), ProblemDetail.class);
		assertThat(noBuyer.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		ResponseEntity<ProblemDetail> shortKey = http.postForEntity(claimsUrl(sale), request(buyer(), "short"),
				ProblemDetail.class);
		assertThat(shortKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(shortKey.getBody().getType().toString()).endsWith("/validation-error");

		ResponseEntity<ProblemDetail> noKey = http.postForEntity(claimsUrl(sale), request(buyer(), null),
				ProblemDetail.class);
		assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void unknownSaleIs404() {
		ResponseEntity<ProblemDetail> resp = http.postForEntity("/api/v1/sales/" + UUID.randomUUID() + "/claims",
				request(buyer(), UUID.randomUUID().toString()), ProblemDetail.class);
		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}
}
