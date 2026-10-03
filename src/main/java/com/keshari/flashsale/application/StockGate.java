package com.keshari.flashsale.application;

import java.time.Duration;
import java.util.UUID;

/**
 * Admission port: decides, atomically with respect to other callers, whether a
 * buyer may take one unit of a sale. Two adapters implement it:
 * <ul>
 *   <li>{@code SqlStockGate} - baseline: conditional UPDATE on the inventory row</li>
 *   <li>{@code RedisStockGate} - optimised: one Lua script on co-located keys</li>
 * </ul>
 * The gate's decision is committed independently of the caller's database
 * transaction, so a caller that fails afterwards must call {@link #release}.
 */
public interface StockGate {

	/** Load the sale's stock into the gate before the first claim. Idempotent. */
	void open(UUID saleId, int initialStock);

	/** Try to take one unit for this buyer under this idempotency key. */
	GateDecision tryReserve(UUID saleId, String userId, String idempotencyKey, UUID reservationId, Duration holdTtl);

	/** Return a unit previously granted to this reservation. Idempotent. */
	void release(UUID saleId, String userId, UUID reservationId);

	/** Units currently available in the gate. */
	long remaining(UUID saleId);

	/** Remove the sale from the gate after it closes. */
	void close(UUID saleId);

	/** Outcome of {@link #tryReserve}. */
	sealed interface GateDecision {

		/** A unit was taken; the caller must now persist the reservation. */
		record Accepted(UUID reservationId) implements GateDecision {
		}

		/** No units remain. */
		record SoldOut() implements GateDecision {
		}

		/** Buyer already holds or bought a unit in this sale. */
		record Duplicate() implements GateDecision {
		}

		/** Same idempotency key seen before; return the original reservation. */
		record Replay(UUID originalReservationId) implements GateDecision {
		}
	}
}
