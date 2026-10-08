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
import org.springframework.http.ResponseEntity;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "flashsale.gate=sql", "flashsale.security.enabled=false", "flashsale.rate-limit.enabled=true",
				"flashsale.rate-limit.user-per-second=2", "flashsale.rate-limit.global-per-second=1000" })
@AutoConfigureTestRestTemplate
class RateLimitApiTest {

	@Autowired TestRestTemplate http;
	@Autowired SaleService sales;
	@Autowired AppUserRepository users;

	@Test
	void thirdClaimInTheSameSecondIs429() {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-RL", "Rate", BigDecimal.TEN, now.minusSeconds(1),
				now.plusSeconds(600), 10, 1, 120));
		sales.open(sale.getId());
		String buyer = "rl-" + UUID.randomUUID();
		users.save(new AppUser(buyer, buyer + "@example.com", "tok", now));
		String url = "/api/v1/sales/" + sale.getId() + "/claims";

		assertThat(claim(url, buyer).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(claim(url, buyer).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		ResponseEntity<Map> limited = claim(url, buyer);
		assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(String.valueOf(limited.getBody().get("type"))).endsWith("/rate-limited");
		assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("1");
	}

	ResponseEntity<Map> claim(String url, String buyer) {
		HttpHeaders h = new HttpHeaders();
		h.set(CurrentBuyerArgumentResolver.HEADER, buyer);
		h.set(ClaimController.IDEMPOTENCY_HEADER, UUID.randomUUID().toString());
		return http.postForEntity(url,
				new HttpEntity<>(Map.of("quantity", 1, "clientTimestamp", Instant.now().toString()), h), Map.class);
	}
}
