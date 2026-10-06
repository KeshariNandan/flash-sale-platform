package com.keshari.flashsale.infrastructure.messaging;

import com.keshari.flashsale.application.ReservationEventPublisher;
import com.keshari.flashsale.config.FlashSaleProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends the outbox payload to {@code sale.reservations} keyed by reservation id
 * so one sale does not pin every record to a single partition.
 */
@Component
public class KafkaReservationEventPublisher implements ReservationEventPublisher {

	private final KafkaTemplate<String, String> kafka;
	private final String topic;

	public KafkaReservationEventPublisher(KafkaTemplate<String, String> kafka, FlashSaleProperties properties) {
		this.kafka = kafka;
		this.topic = properties.kafka().reservationsTopic();
	}

	@Override
	public void publish(String reservationId, String payload) {
		try {
			kafka.send(topic, reservationId, payload).get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted publishing reservation " + reservationId, e);
		}
		catch (Exception e) {
			throw new IllegalStateException("failed publishing reservation " + reservationId, e);
		}
	}
}
