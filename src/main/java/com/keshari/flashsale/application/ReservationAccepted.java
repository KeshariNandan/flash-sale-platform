package com.keshari.flashsale.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The single domain event this system publishes: a reservation was accepted and
 * awaits fulfilment. Serialised into the outbox payload and onto Kafka.
 */
public record ReservationAccepted(UUID reservationId, UUID saleId, String userId, BigDecimal amount,
		Instant acceptedAt, Instant expiresAt) {
}
