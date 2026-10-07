package com.keshari.flashsale.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import com.keshari.flashsale.application.ReconciliationService.InvariantReport;
import com.keshari.flashsale.domain.AppUser;
import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.infrastructure.persistence.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "flashsale.gate=sql")
class InvariantReportTest {

	@Autowired ReconciliationService reconciler;
	@Autowired ClaimService claims;
	@Autowired SaleService sales;
	@Autowired AppUserRepository users;

	FlashSale open(int stock) {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-INV", "Invariant", BigDecimal.TEN,
				now.minusSeconds(1), now.plusSeconds(600), stock, 1, 120));
		return sales.open(sale.getId());
	}

	String buyer() {
		String id = "inv-" + UUID.randomUUID();
		users.save(new AppUser(id, id + "@example.com", "tok", Instant.now()));
		return id;
	}

	@Test
	void unusedSaleBalances() {
		FlashSale sale = open(5);
		InvariantReport report = reconciler.invariantReport(sale.getId());
		assertThat(report.holds()).isTrue();
		assertThat(report.accounted()).isEqualTo(5);
		assertThat(report.gateRemaining()).isEqualTo(5);
		assertThat(report.activeHolds()).isZero();
		assertThat(report.confirmed()).isZero();
		assertThat(report.deficit()).isZero();
		assertThat(report.surplus()).isZero();
	}

	@Test
	void pendingClaimsStillBalance() {
		FlashSale sale = open(5);
		claims.claim(new ClaimCommand(sale.getId(), buyer(), "key-" + UUID.randomUUID(), 1));
		claims.claim(new ClaimCommand(sale.getId(), buyer(), "key-" + UUID.randomUUID(), 1));
		InvariantReport report = reconciler.invariantReport(sale.getId());
		assertThat(report.activeHolds()).isEqualTo(2);
		assertThat(report.gateRemaining()).isEqualTo(3);
		assertThat(report.holds()).isTrue();
	}

	@Test
	void reconcileExpiresThenStillBalances() throws Exception {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-INV2", "Invariant2", BigDecimal.TEN,
				now.minusSeconds(1), now.plusSeconds(600), 4, 1, 1));
		sales.open(sale.getId());
		claims.claim(new ClaimCommand(sale.getId(), buyer(), "key-" + UUID.randomUUID(), 1));
		Thread.sleep(1100);
		InvariantReport report = reconciler.reconcile(sale.getId());
		assertThat(report.activeHolds()).isZero();
		assertThat(report.gateRemaining()).isEqualTo(4);
		assertThat(report.holds()).isTrue();
	}
}
