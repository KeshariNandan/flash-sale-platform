package com.keshari.flashsale.application;

/**
 * Publishes a {@link ReservationAccepted} payload. The outbox relay is the only
 * caller; the Kafka adapter is the production implementation.
 */
public interface ReservationEventPublisher {

	void publish(String reservationId, String payload);
}
