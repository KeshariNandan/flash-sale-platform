package com.keshari.flashsale.domain;

/** Lifecycle of a reservation. Only PENDING may transition; the other three are terminal. */
public enum ReservationStatus {
	PENDING, CONFIRMED, RELEASED, EXPIRED;

	public boolean isTerminal() {
		return this != PENDING;
	}
}
