package com.keshari.flashsale.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationTest {

	static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
	static final Instant EXPIRY = NOW.plusSeconds(120);

	static Reservation pending() {
		return Reservation.pending(UUID.randomUUID(), UUID.randomUUID(), "buyer-1", "key-1", EXPIRY, NOW);
	}

	@Test
	void newReservationIsPendingAndNew() {
		Reservation r = pending();
		assertThat(r.status()).isEqualTo(ReservationStatus.PENDING);
		assertThat(r.isPending()).isTrue();
		assertThat(r.isNew()).isTrue();
		assertThat(r.isExpired(NOW)).isFalse();
		assertThat(r.isExpired(EXPIRY)).isTrue();
	}

	@Test
	void requiresBuyerKeyAndFutureExpiry() {
		UUID id = UUID.randomUUID();
		UUID sale = UUID.randomUUID();
		assertThatThrownBy(() -> Reservation.pending(id, sale, " ", "k", EXPIRY, NOW)).isInstanceOf(DomainException.class);
		assertThatThrownBy(() -> Reservation.pending(id, sale, "u", "", EXPIRY, NOW)).isInstanceOf(DomainException.class);
		assertThatThrownBy(() -> Reservation.pending(id, sale, "u", "k", NOW, NOW)).isInstanceOf(DomainException.class);
	}

	@Test
	void pendingCanBeConfirmedOnce() {
		Reservation r = pending();
		r.confirm(NOW.plusSeconds(5));
		assertThat(r.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(r.updatedAt()).isEqualTo(NOW.plusSeconds(5));
		assertThatThrownBy(() -> r.confirm(NOW.plusSeconds(6))).isInstanceOf(IllegalStateTransitionException.class);
		assertThatThrownBy(() -> r.release(NOW.plusSeconds(6))).isInstanceOf(IllegalStateTransitionException.class);
	}

	@Test
	void pendingCanBeReleased() {
		Reservation r = pending();
		r.release(NOW.plusSeconds(1));
		assertThat(r.status()).isEqualTo(ReservationStatus.RELEASED);
		assertThatThrownBy(() -> r.confirm(NOW.plusSeconds(2))).isInstanceOf(IllegalStateTransitionException.class);
	}

	@Test
	void expireRequiresDeadlineToHavePassed() {
		Reservation r = pending();
		assertThatThrownBy(() -> r.expire(NOW.plusSeconds(10))).isInstanceOf(DomainException.class)
				.hasMessageContaining("not reached");
		r.expire(EXPIRY);
		assertThat(r.status()).isEqualTo(ReservationStatus.EXPIRED);
	}

	@Test
	void latePaymentAfterExpiryCannotConfirm() {
		// the order-worker race described in the report: a late payment success
		// must not resurrect a reservation the reconciler has already expired
		Reservation r = pending();
		r.expire(EXPIRY);
		assertThatThrownBy(() -> r.confirm(EXPIRY.plusSeconds(1))).isInstanceOf(IllegalStateTransitionException.class);
	}
}
