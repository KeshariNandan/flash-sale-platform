package com.keshari.flashsale.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import com.keshari.flashsale.application.ReconciliationService.InvariantReport;
import com.keshari.flashsale.domain.AppUser;
import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.ReservationStatus;
import com.keshari.flashsale.infrastructure.persistence.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "flashsale.gate=redis")
class ReconciliationServiceRedisTest {

	@Autowired ReconciliationService reconciler;
	@Autowired ClaimService claims;
	@Autowired SaleService sales;
	@Autowired StockGate gate;
	@Autowired AppUserRepository users;

	record Opened(FlashSale sale, String buyer) {
	}

	Opened open(int stock, int holdTtlSeconds) {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-RCR", "Redis reconcile", BigDecimal.TEN,
				now.minusSeconds(1), now.plusSeconds(600), stock, 1, holdTtlSeconds));
		sales.open(sale.getId());
		String buyer = "rcr-" + UUID.randomUUID();
		users.save(new AppUser(buyer, buyer + "@example.com", "tok", now));
		return new Opened(sale, buyer);
	}

	@Test
	void expiredHoldReturnsTheUnitToRedis() throws Exception {
		Opened opened = open(2, 1);
		ClaimResult result = claims
				.claim(new ClaimCommand(opened.sale().getId(), opened.buyer(), "key-" + UUID.randomUUID(), 1));
		assertThat(result).isInstanceOf(ClaimResult.Accepted.class);
		assertThat(gate.remaining(opened.sale().getId())).isEqualTo(1);

		Thread.sleep(1100);
		assertThat(reconciler.expireStaleHolds()).isEqualTo(1);
		UUID reservationId = ((ClaimResult.Accepted) result).reservation().getId();
		assertThat(claims.reservation(reservationId).status()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(gate.remaining(opened.sale().getId())).isEqualTo(2);
	}

	@Test
	void invariantUsesGateRemainingNotPostgresInventory() {
		Opened opened = open(3, 120);
		claims.claim(new ClaimCommand(opened.sale().getId(), opened.buyer(), "key-" + UUID.randomUUID(), 1));
		InvariantReport report = reconciler.invariantReport(opened.sale().getId());
		assertThat(report.gateRemaining()).isEqualTo(2);
		assertThat(report.activeHolds()).isEqualTo(1);
		assertThat(report.holds()).isTrue();
	}
}
