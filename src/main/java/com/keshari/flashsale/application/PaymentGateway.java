package com.keshari.flashsale.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Charges a saved payment method. The worker calls this after admission so the
 * buyer's claim latency does not include the gateway round trip.
 */
public interface PaymentGateway {

	PaymentResult charge(String userId, BigDecimal amount, UUID reservationId);

	sealed interface PaymentResult {
		record Succeeded(String paymentRef) implements PaymentResult {
		}

		record Failed(String reason) implements PaymentResult {
		}
	}
}
