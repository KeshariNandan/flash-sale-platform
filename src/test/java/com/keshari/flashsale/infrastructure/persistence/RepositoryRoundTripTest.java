package com.keshari.flashsale.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import com.keshari.flashsale.domain.AppUser;
import com.keshari.flashsale.domain.CustomerOrder;
import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.Inventory;
import com.keshari.flashsale.domain.OutboxEvent;
import com.keshari.flashsale.domain.Reservation;
import com.keshari.flashsale.domain.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entity mappings against the Flyway schema (Hibernate validates them at start)
 * and the hand-written repository queries used by the baseline gate, the relay
 * and the reconciler.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RepositoryRoundTripTest {

	@Autowired FlashSaleRepository sales;
	@Autowired InventoryRepository inventories;
	@Autowired ReservationRepository reservations;
	@Autowired CustomerOrderRepository orders;
	@Autowired OutboxEventRepository outbox;
	@Autowired AppUserRepository users;

	final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

	@Test
	@Transactional
	void fullAggregateRoundTrip() {
		FlashSale sale = FlashSale.schedule("SKU-RT", "Round trip", new BigDecimal("19.50"), now, now.plusSeconds(600),
				3, 1, 120, now);
		sales.save(sale);
		inventories.save(new Inventory(sale.getId(), 3, now));
		String buyer = "buyer-" + UUID.randomUUID();
		users.save(new AppUser(buyer, buyer + "@example.com", "tok", now));

		Reservation r = Reservation.pending(UUID.randomUUID(), sale.getId(), buyer, "idem-1", now.plusSeconds(120), now);
		reservations.save(r);
		outbox.save(OutboxEvent.reservationAccepted(r.getId(), "{\"reservationId\":\"" + r.getId() + "\"}", now));

		assertThat(reservations.findByUserIdAndIdempotencyKey(buyer, "idem-1")).isPresent();
		assertThat(outbox.countByPublishedAtIsNull()).isGreaterThanOrEqualTo(1);
		assertThat(outbox.lockNextUnpublished(10)).anyMatch(e -> e.aggregateId().equals(r.getId()));

		r.confirm(now);
		orders.save(CustomerOrder.paid(r, sale.unitPrice(), "pay_rt", now));
		assertThat(orders.findByReservationId(r.getId())).isPresent();
		assertThat(reservations.countBySaleIdAndStatus(sale.getId(), ReservationStatus.CONFIRMED)).isEqualTo(1);
	}

	@Test
	@Transactional
	void conditionalDecrementStopsAtZero() {
		FlashSale sale = FlashSale.schedule("SKU-DEC", "Decrement", BigDecimal.ONE, now, now.plusSeconds(600), 2, 1,
				120, now);
		sales.save(sale);
		inventories.saveAndFlush(new Inventory(sale.getId(), 2, now));

		assertThat(inventories.tryDecrement(sale.getId())).isEqualTo(1);
		assertThat(inventories.tryDecrement(sale.getId())).isEqualTo(1);
		assertThat(inventories.tryDecrement(sale.getId())).as("sold out").isZero();
		assertThat(inventories.increment(sale.getId())).isEqualTo(1);
		assertThat(inventories.tryDecrement(sale.getId())).isEqualTo(1);
	}

	@Test
	@Transactional
	void expiryQueryFindsOnlyStalePendingHolds() {
		FlashSale sale = FlashSale.schedule("SKU-EXP", "Expiry", BigDecimal.ONE, now, now.plusSeconds(600), 5, 1, 120,
				now);
		sales.save(sale);
		String a = "a-" + UUID.randomUUID();
		String b = "b-" + UUID.randomUUID();
		users.save(new AppUser(a, a + "@x", "t", now));
		users.save(new AppUser(b, b + "@x", "t", now));
		Instant past = now.minusSeconds(30);
		// expiresAt in the past relative to "now" but created earlier so the domain rule holds
		Reservation stale = Reservation.pending(UUID.randomUUID(), sale.getId(), a, "k", past, past.minusSeconds(120));
		Reservation fresh = Reservation.pending(UUID.randomUUID(), sale.getId(), b, "k", now.plusSeconds(120), now);
		reservations.saveAll(java.util.List.of(stale, fresh));

		var found = reservations.findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, now, Limit.of(100));
		assertThat(found).extracting(Reservation::getId).contains(stale.getId()).doesNotContain(fresh.getId());
	}
}
