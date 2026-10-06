package com.keshari.flashsale.infrastructure.payment;

import com.keshari.flashsale.application.PaymentGateway;
import com.keshari.flashsale.config.FlashSaleProperties;
import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-process stand-in for a card gateway. Sleeps a configured latency and fails
 * a configured fraction of charges so payment-failure release can be tested
 * without a merchant account.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

	private final long latencyMs;
	private final double failureRate;
	private final Random random;

	@Autowired
	public SimulatedPaymentGateway(FlashSaleProperties properties) {
		this(properties.payment().latencyMs(), properties.payment().failureRate(), new Random());
	}

	SimulatedPaymentGateway(long latencyMs, double failureRate, Random random) {
		this.latencyMs = latencyMs;
		this.failureRate = failureRate;
		this.random = random;
	}

	@Override
	public PaymentResult charge(String userId, BigDecimal amount, UUID reservationId) {
		sleep();
		if (failureRate > 0 && random.nextDouble() < failureRate) {
			return new PaymentResult.Failed("simulated decline");
		}
		return new PaymentResult.Succeeded("pay-" + reservationId);
	}

	private void sleep() {
		if (latencyMs <= 0) {
			return;
		}
		try {
			Thread.sleep(latencyMs);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("payment interrupted", e);
		}
	}
}
