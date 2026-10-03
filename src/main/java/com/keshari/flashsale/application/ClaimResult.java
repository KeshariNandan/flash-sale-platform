package com.keshari.flashsale.application;

import com.keshari.flashsale.domain.Reservation;
import com.keshari.flashsale.domain.SaleStatus;

/**
 * Every possible outcome of a claim. Sealed so the controller must handle each
 * one; see the response table in the report's feature chapter.
 */
public sealed interface ClaimResult {

	/** 202: unit held, reservation pending fulfilment. */
	record Accepted(Reservation reservation) implements ClaimResult {
	}

	/** 200: same idempotency key seen before; original outcome returned. */
	record Replayed(Reservation reservation) implements ClaimResult {
	}

	/** 409 sold-out. */
	record SoldOut() implements ClaimResult {
	}

	/** 409 duplicate-claim: buyer already holds or bought a unit in this sale. */
	record Duplicate() implements ClaimResult {
	}

	/** 409 sale-not-open. */
	record SaleNotOpen(SaleStatus status) implements ClaimResult {
	}

	/** 403 unknown-buyer: principal has no app_user row. */
	record UnknownBuyer() implements ClaimResult {
	}

	/** 503 gate-unavailable: admission or persistence failed after the gate; unit was released. */
	record GateUnavailable(String reason) implements ClaimResult {
	}
}
