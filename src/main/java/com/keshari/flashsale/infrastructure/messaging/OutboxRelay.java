package com.keshari.flashsale.infrastructure.messaging;

import com.keshari.flashsale.application.ReservationEventPublisher;
import com.keshari.flashsale.config.FlashSaleProperties;
import com.keshari.flashsale.domain.OutboxEvent;
import com.keshari.flashsale.infrastructure.persistence.OutboxEventRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains unpublished outbox rows with {@code FOR UPDATE SKIP LOCKED}, publishes
 * each payload, then marks it published. A crash after publish and before the
 * mark yields at-least-once delivery; the worker is idempotent.
 */
@Component
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxEventRepository outbox;
	private final ReservationEventPublisher publisher;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final FlashSaleProperties properties;

	public OutboxRelay(OutboxEventRepository outbox, ReservationEventPublisher publisher, TransactionTemplate tx,
			Clock clock, FlashSaleProperties properties) {
		this.outbox = outbox;
		this.publisher = publisher;
		this.tx = tx;
		this.clock = clock;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${flashsale.outbox.poll-ms:200}")
	public void scheduledPublish() {
		if (!properties.outbox().enabled()) {
			return;
		}
		publishBatch();
	}

	public int publishBatch() {
		Integer published = tx.execute(status -> {
			List<OutboxEvent> batch = outbox.lockNextUnpublished(properties.outbox().batchSize());
			for (OutboxEvent event : batch) {
				publisher.publish(event.aggregateId().toString(), event.payload());
				event.markPublished(clock.instant());
			}
			return batch.size();
		});
		int count = published == null ? 0 : published;
		if (count > 0) {
			log.info("published {} outbox event(s)", count);
		}
		return count;
	}
}
