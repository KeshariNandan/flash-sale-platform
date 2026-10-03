package com.keshari.flashsale.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FlashSaleTest {

	static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

	static FlashSale sale() {
		return FlashSale.schedule("SKU-1", "Limited drop", new BigDecimal("49.99"), NOW.plusSeconds(60),
				NOW.plusSeconds(3600), 1000, 1, 120, NOW);
	}

	@Test
	void scheduledSaleStartsInScheduledState() {
		FlashSale s = sale();
		assertThat(s.status()).isEqualTo(SaleStatus.SCHEDULED);
		assertThat(s.isNew()).isTrue();
		assertThat(s.holdTtl()).isEqualTo(Duration.ofSeconds(120));
	}

	@Test
	void rejectsInvalidConfiguration() {
		assertThatThrownBy(() -> FlashSale.schedule("SKU", "n", BigDecimal.ZERO, NOW, NOW.plusSeconds(1), 1, 1, 1, NOW))
				.isInstanceOf(DomainException.class).hasMessageContaining("unitPrice");
		assertThatThrownBy(() -> FlashSale.schedule("SKU", "n", BigDecimal.ONE, NOW, NOW, 1, 1, 1, NOW))
				.isInstanceOf(DomainException.class).hasMessageContaining("endAt");
		assertThatThrownBy(() -> FlashSale.schedule("SKU", "n", BigDecimal.ONE, NOW, NOW.plusSeconds(1), 0, 1, 1, NOW))
				.isInstanceOf(DomainException.class).hasMessageContaining("initialStock");
		assertThatThrownBy(() -> FlashSale.schedule("SKU", "n", BigDecimal.ONE, NOW, NOW.plusSeconds(1), 1, 0, 1, NOW))
				.isInstanceOf(DomainException.class).hasMessageContaining("perUserLimit");
	}

	@Test
	void followsLifecycleScheduledOpenSoldOutReopenClosed() {
		FlashSale s = sale();
		s.open();
		assertThat(s.status()).isEqualTo(SaleStatus.OPEN);
		s.markSoldOut();
		assertThat(s.status()).isEqualTo(SaleStatus.SOLD_OUT);
		s.reopen();
		assertThat(s.status()).isEqualTo(SaleStatus.OPEN);
		s.close();
		assertThat(s.status()).isEqualTo(SaleStatus.CLOSED);
	}

	@Test
	void illegalTransitionsAreRejected() {
		FlashSale s = sale();
		assertThatThrownBy(s::markSoldOut).isInstanceOf(IllegalStateTransitionException.class);
		assertThatThrownBy(s::reopen).isInstanceOf(IllegalStateTransitionException.class);
		s.open();
		assertThatThrownBy(s::open).isInstanceOf(IllegalStateTransitionException.class);
		s.close();
		assertThatThrownBy(s::close).isInstanceOf(IllegalStateTransitionException.class);
		assertThatThrownBy(s::open).isInstanceOf(IllegalStateTransitionException.class);
	}

	@Test
	void acceptsClaimsOnlyWhileOpenAndInsideWindow() {
		FlashSale s = sale();
		Instant inside = NOW.plusSeconds(120);
		assertThat(s.acceptsClaims(inside)).as("scheduled").isFalse();
		s.open();
		assertThat(s.acceptsClaims(NOW)).as("before start").isFalse();
		assertThat(s.acceptsClaims(inside)).as("open, inside window").isTrue();
		assertThat(s.acceptsClaims(NOW.plusSeconds(3600))).as("at end").isFalse();
		s.markSoldOut();
		assertThat(s.acceptsClaims(inside)).as("sold out").isFalse();
	}
}
