package com.keshari.flashsale.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import com.keshari.flashsale.application.ClaimCommand;
import com.keshari.flashsale.application.ClaimResult;
import com.keshari.flashsale.application.ClaimService;
import com.keshari.flashsale.application.SaleService;
import com.keshari.flashsale.config.FlashSaleProperties;
import com.keshari.flashsale.domain.AppUser;
import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.infrastructure.persistence.AppUserRepository;
import com.keshari.flashsale.infrastructure.persistence.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxRelayTest {

	@Autowired OutboxRelay relay;
	@Autowired ClaimService claims;
	@Autowired SaleService sales;
	@Autowired AppUserRepository users;
	@Autowired OutboxEventRepository outbox;
	@Autowired ConsumerFactory<String, String> consumers;
	@Autowired FlashSaleProperties properties;

	@Test
	void publishesTheOutboxPayloadKeyedByReservationId() {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-OB", "Outbox", BigDecimal.TEN, now.minusSeconds(1),
				now.plusSeconds(600), 2, 1, 120));
		sales.open(sale.getId());
		String buyer = "ob-" + UUID.randomUUID();
		users.save(new AppUser(buyer, buyer + "@example.com", "tok", now));

		ClaimResult result = claims.claim(new ClaimCommand(sale.getId(), buyer, "key-" + UUID.randomUUID(), 1));
		assertThat(result).isInstanceOf(ClaimResult.Accepted.class);
		UUID reservationId = ((ClaimResult.Accepted) result).reservation().getId();

		try (Consumer<String, String> consumer = consumers.createConsumer("outbox-relay-assert", "c1")) {
			consumer.subscribe(List.of(properties.kafka().reservationsTopic()));
			consumer.poll(Duration.ofSeconds(2));

			assertThat(relay.publishBatch()).isEqualTo(1);
			assertThat(outbox.findAll()).anyMatch(e -> e.aggregateId().equals(reservationId) && e.isPublished());
			assertThat(relay.publishBatch()).isZero();

			ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
			assertThat(records).isNotEmpty();
			ConsumerRecord<String, String> record = records.iterator().next();
			assertThat(record.key()).isEqualTo(reservationId.toString());
			assertThat(record.value()).contains(reservationId.toString());
			assertThat(record.value()).contains(sale.getId().toString());
		}
	}
}
