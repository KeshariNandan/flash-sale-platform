package com.keshari.flashsale.infrastructure.messaging;

import com.keshari.flashsale.application.FulfilmentService;
import com.keshari.flashsale.application.ReservationAccepted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer group {@code flash-sale-order-worker}. Manual ack after fulfilment
 * so a crash before the database commit is retried. Duplicate deliveries are
 * absorbed by {@link FulfilmentService}.
 */
@Component
public class OrderWorker {

	private static final Logger log = LoggerFactory.getLogger(OrderWorker.class);

	private final FulfilmentService fulfilment;
	private final JsonMapper json;

	public OrderWorker(FulfilmentService fulfilment, JsonMapper json) {
		this.fulfilment = fulfilment;
		this.json = json;
	}

	@KafkaListener(id = "order-worker", topics = "${flashsale.kafka.reservations-topic:sale.reservations}",
			groupId = "flash-sale-order-worker")
	public void onReservationAccepted(ConsumerRecord<String, String> record) {
		ReservationAccepted event;
		try {
			event = json.readValue(record.value(), ReservationAccepted.class);
		}
		catch (RuntimeException e) {
			log.error("unreadable ReservationAccepted payload key={} value={}", record.key(), record.value(), e);
			throw e;
		}
		try {
			fulfilment.fulfil(event);
		}
		catch (RuntimeException e) {
			log.error("fulfilment failed for reservation {}", event.reservationId(), e);
			throw e;
		}
	}
}
