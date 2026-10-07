package com.keshari.flashsale.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import com.keshari.flashsale.domain.AppUser;
import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.ReservationStatus;
import com.keshari.flashsale.domain.SaleStatus;
import com.keshari.flashsale.infrastructure.persistence.AppUserRepository;
import com.keshari.flashsale.infrastructure.persistence.FlashSaleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "flashsale.gate=sql")
class ReconciliationServiceTest {

	@Autowired ReconciliationService reconciler;
	@Autowired ClaimService claims;
	@Autowired SaleService sales;
	@Autowired StockGate gate;
	@Autowired AppUserRepository users;
	@Autowired FlashSaleRepository saleRows;
	@Autowired SaleStateCache saleState;

	record Opened(FlashSale sale, String buyer) {
	}

	Opened open(int stock, int holdTtlSeconds) {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-RC", "Reconcile", BigDecimal.TEN,
				now.minusSeconds(1), now.plusSeconds(600), stock, 1, holdTtlSeconds));
		sales.open(sale.getId());
		String buyer = "rc-" + UUID.randomUUID();
		users.save(new AppUser(buyer, buyer + "@example.com", "tok", now));
		return new Opened(sale, buyer);
	}

	ClaimResult.Accepted accept(Opened opened) {
		ClaimResult result = claims
				.claim(new ClaimCommand(opened.sale().getId(), opened.buyer(), "key-" + UUID.randomUUID(), 1));
		assertThat(result).isInstanceOf(ClaimResult.Accepted.class);
		return (ClaimResult.Accepted) result;
	}

	@Test
	void expiredHoldIsMarkedAndTheUnitReturnsToTheGate() throws Exception {
		Opened opened = open(3, 1);
		ClaimResult.Accepted accepted = accept(opened);
		assertThat(gate.remaining(opened.sale().getId())).isEqualTo(2);

		Thread.sleep(1100);
		assertThat(reconciler.expireStaleHolds()).isEqualTo(1);
		assertThat(claims.reservation(accepted.reservation().getId()).status()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(gate.remaining(opened.sale().getId())).isEqualTo(3);
		assertThat(reconciler.expireStaleHolds()).isZero();
	}

	@Test
	void aLiveHoldIsLeftAlone() {
		Opened opened = open(2, 120);
		accept(opened);
		assertThat(reconciler.expireStaleHolds()).isZero();
		assertThat(gate.remaining(opened.sale().getId())).isEqualTo(1);
	}

	@Test
	void soldOutSaleReopensWhenAnExpiredHoldReturnsAUnit() throws Exception {
		Opened opened = open(1, 1);
		accept(opened);
		FlashSale soldOut = saleRows.findById(opened.sale().getId()).orElseThrow();
		soldOut.markSoldOut();
		saleRows.save(soldOut);
		saleState.evict(opened.sale().getId());

		Thread.sleep(1100);
		assertThat(reconciler.expireStaleHolds()).isEqualTo(1);
		assertThat(sales.get(opened.sale().getId()).status()).isEqualTo(SaleStatus.OPEN);
		assertThat(gate.remaining(opened.sale().getId())).isEqualTo(1);
	}
}
