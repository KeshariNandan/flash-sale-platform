package com.keshari.flashsale.infrastructure.persistence;

import com.keshari.flashsale.application.StockGate;
import com.keshari.flashsale.domain.ReservationStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Baseline admission strategy, kept only to provide the "before" measurement
 * for the benchmark. Every attempt, accepted or not, performs a conditional
 * UPDATE on the single inventory row and therefore queues on its row lock.
 * <p>
 * Each call commits in its own transaction (REQUIRES_NEW) so the gate contract
 * - decision committed independently of the caller - is the same as Redis.
 * Duplicate and replay checks here are best-effort reads; the partial unique
 * indexes on {@code reservation} remain the authoritative guard.
 */
@Component
@ConditionalOnProperty(name = "flashsale.gate", havingValue = "sql")
public class SqlStockGate implements StockGate {

	private final InventoryRepository inventories;
	private final ReservationRepository reservations;

	public SqlStockGate(InventoryRepository inventories, ReservationRepository reservations) {
		this.inventories = inventories;
		this.reservations = reservations;
	}

	@Override
	public void open(UUID saleId, int initialStock) {
		// stock already lives in the inventory row created with the sale
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public GateDecision tryReserve(UUID saleId, String userId, String idempotencyKey, UUID reservationId,
			Duration holdTtl) {
		var prior = reservations.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
		if (prior.isPresent()) {
			return new GateDecision.Replay(prior.get().getId());
		}
		if (reservations.existsBySaleIdAndUserIdAndStatusIn(saleId, userId,
				List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED))) {
			return new GateDecision.Duplicate();
		}
		return inventories.tryDecrement(saleId) == 1 ? new GateDecision.Accepted(reservationId)
				: new GateDecision.SoldOut();
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void release(UUID saleId, String userId, UUID reservationId) {
		inventories.increment(saleId);
	}

	@Override
	@Transactional(readOnly = true)
	public long remaining(UUID saleId) {
		return inventories.findById(saleId).map(i -> (long) i.stockRemaining()).orElse(0L);
	}

	@Override
	public void close(UUID saleId) {
		// nothing cached outside the database
	}
}
