package com.keshari.flashsale.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.application.PaymentGateway.PaymentResult;
import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulatedPaymentGatewayTest {

	@Test
	void alwaysSucceedsWhenFailureRateIsZero() {
		var gateway = new SimulatedPaymentGateway(0, 0, new Random(1));
		UUID reservation = UUID.randomUUID();
		assertThat(gateway.charge("u", BigDecimal.ONE, reservation))
				.isEqualTo(new PaymentResult.Succeeded("pay-" + reservation));
	}

	@Test
	void alwaysFailsWhenFailureRateIsOne() {
		var gateway = new SimulatedPaymentGateway(0, 1, new Random(1));
		assertThat(gateway.charge("u", BigDecimal.ONE, UUID.randomUUID()))
				.isEqualTo(new PaymentResult.Failed("simulated decline"));
	}
}
