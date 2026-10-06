package com.keshari.flashsale.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import com.keshari.flashsale.application.ClaimCommand;
import com.keshari.flashsale.application.ClaimResult;
import com.keshari.flashsale.application.ClaimService;
import com.keshari.flashsale.application.FulfilmentService;
import com.keshari.flashsale.application.ReservationAccepted;
import com.keshari.flashsale.application.SaleService;
import com.keshari.flashsale.application.StockGate;
import com.keshari.flashsale.domain.AppUser;
import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.OrderStatus;
import com.keshari.flashsale.domain.Reservation;
import com.keshari.flashsale.domain.ReservationStatus;
import com.keshari.flashsale.infrastructure.persistence.AppUserRepository;
import com.keshari.flashsale.infrastructure.persistence.CustomerOrderRepository;
import com.keshari.flashsale.infrastructure.persistence.OutboxEventRepository;
import com.keshari.flashsale.infrastructure.persistence.ReservationRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"flashsale.gate=sql",
		"flashsale.outbox.enabled=false",
		"flashsale.payment.failure-rate=0",
		"spring.kafka.listener.auto-startup=true"
})
class OrderPipelineTest {

	@Autowired ClaimService claims;
	@Autowired SaleService sales;
	@Autowired AppUserRepository users;
	@Autowired ReservationRepository reservations;
	@Autowired CustomerOrderRepository orders;
	@Autowired OutboxRelay relay;
	@Autowired FulfilmentService fulfilment;
	@Autowired StockGate gate;
	@Autowired OutboxEventRepository outbox;
	@Autowired JsonMapper json;
	@Autowired KafkaListenerEndpointRegistry listeners;

	record OpenSale(FlashSale sale, String buyer) {
	}

	OpenSale open(int stock) {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-PP", "Pipeline", BigDecimal.TEN,
				now.minusSeconds(1), now.plusSeconds(600), stock, 1, 300));
		sales.open(sale.getId());
		String buyer = "pp-" + UUID.randomUUID();
		users.save(new AppUser(buyer, buyer + "@example.com", "tok", now));
		return new OpenSale(sale, buyer);
	}

	ClaimResult.Accepted accept(OpenSale open) {
		ClaimResult result = claims.claim(new ClaimCommand(open.sale().getId(), open.buyer(),
				"key-" + UUID.randomUUID(), 1));
		assertThat(result).isInstanceOf(ClaimResult.Accepted.class);
		return (ClaimResult.Accepted) result;
	}

	void waitUntil(Duration timeout, java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("condition not met within " + timeout);
	}

	@Test
	void acceptedClaimBecomesAPaidOrderAfterRelay() throws Exception {
		OpenSale open = open(3);
		ClaimResult.Accepted accepted = accept(open);
		UUID reservationId = accepted.reservation().getId();
		String payload = outbox.findAll().stream().filter(e -> e.aggregateId().equals(reservationId)).findFirst()
				.orElseThrow().payload();
		assertThat(json.readValue(payload, ReservationAccepted.class).reservationId()).isEqualTo(reservationId);
		var container = listeners.getListenerContainer("order-worker");
		assertThat(container).isNotNull();
		if (!container.isRunning()) {
			container.start();
		}

		assertThat(relay.publishBatch()).isEqualTo(1);
		waitUntil(Duration.ofSeconds(20),
				() -> reservations.findById(reservationId).map(r -> r.status() == ReservationStatus.CONFIRMED)
						.orElse(false));

		Reservation confirmed = reservations.findById(reservationId).orElseThrow();
		assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(orders.findByReservationId(reservationId)).isPresent();
		assertThat(orders.findByReservationId(reservationId).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
		assertThat(sales.stats(open.sale().getId()).paidOrders()).isEqualTo(1);
	}

	@Test
	void duplicateFulfilmentDoesNotCreateASecondOrder() {
		OpenSale open = open(3);
		ClaimResult.Accepted accepted = accept(open);
		Reservation reservation = accepted.reservation();
		ReservationAccepted event = new ReservationAccepted(reservation.getId(), reservation.saleId(),
				reservation.userId(), BigDecimal.TEN, reservation.createdAt(), reservation.expiresAt());

		fulfilment.fulfil(event);
		fulfilment.fulfil(event);

		assertThat(reservations.findById(reservation.getId()).orElseThrow().status())
				.isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(orders.countBySaleIdAndStatus(open.sale().getId(), OrderStatus.PAID)).isEqualTo(1);
	}
}
