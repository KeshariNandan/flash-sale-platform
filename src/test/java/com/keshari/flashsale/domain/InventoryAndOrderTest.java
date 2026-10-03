package com.keshari.flashsale.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryAndOrderTest {

	static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

	@Test
	void inventoryNeverGoesNegative() {
		Inventory inv = new Inventory(UUID.randomUUID(), 2, NOW);
		assertThat(inv.tryDecrement(NOW)).isTrue();
		assertThat(inv.tryDecrement(NOW)).isTrue();
		assertThat(inv.tryDecrement(NOW)).as("third unit does not exist").isFalse();
		assertThat(inv.stockRemaining()).isZero();
		inv.increment(NOW);
		assertThat(inv.stockRemaining()).isEqualTo(1);
	}

	@Test
	void orderCopiesIdentityFromReservation() {
		Reservation r = Reservation.pending(UUID.randomUUID(), UUID.randomUUID(), "buyer-9", "k", NOW.plusSeconds(60),
				NOW);
		CustomerOrder o = CustomerOrder.paid(r, new BigDecimal("49.99"), "pay_123", NOW);
		assertThat(o.reservationId()).isEqualTo(r.getId());
		assertThat(o.saleId()).isEqualTo(r.saleId());
		assertThat(o.userId()).isEqualTo("buyer-9");
		assertThat(o.status()).isEqualTo(OrderStatus.PAID);
		assertThat(o.isNew()).isTrue();
	}

	@Test
	void orderRejectsBadAmountOrReference() {
		Reservation r = Reservation.pending(UUID.randomUUID(), UUID.randomUUID(), "b", "k", NOW.plusSeconds(60), NOW);
		assertThatThrownBy(() -> CustomerOrder.paid(r, BigDecimal.ZERO, "ref", NOW)).isInstanceOf(DomainException.class);
		assertThatThrownBy(() -> CustomerOrder.paid(r, BigDecimal.TEN, " ", NOW)).isInstanceOf(DomainException.class);
	}

	@Test
	void outboxEventIsPublishedExactlyOnce() {
		OutboxEvent e = OutboxEvent.reservationAccepted(UUID.randomUUID(), "{\"a\":1}", NOW);
		assertThat(e.isPublished()).isFalse();
		assertThat(e.eventType()).isEqualTo(OutboxEvent.TYPE_RESERVATION_ACCEPTED);
		e.markPublished(NOW.plusSeconds(1));
		assertThat(e.isPublished()).isTrue();
		assertThatThrownBy(() -> e.markPublished(NOW.plusSeconds(2))).isInstanceOf(IllegalStateTransitionException.class);
	}
}
