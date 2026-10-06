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
import com.keshari.flashsale.domain.Reservation;
import com.keshari.flashsale.domain.ReservationStatus;
import com.keshari.flashsale.infrastructure.persistence.AppUserRepository;
import com.keshari.flashsale.infrastructure.persistence.CustomerOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"flashsale.gate=sql",
		"flashsale.outbox.enabled=false",
		"flashsale.payment.failure-rate=1",
		"spring.kafka.listener.auto-startup=false"
})
class PaymentFailureReleaseTest {

	@Autowired ClaimService claims;
	@Autowired SaleService sales;
	@Autowired AppUserRepository users;
	@Autowired CustomerOrderRepository orders;
	@Autowired FulfilmentService fulfilment;
	@Autowired StockGate gate;

	@Test
	void declinedPaymentReleasesTheHoldAndReturnsTheUnit() {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-PF", "Pay fail", BigDecimal.TEN,
				now.minusSeconds(1), now.plusSeconds(600), 2, 1, 300));
		sales.open(sale.getId());
		String buyer = "pf-" + UUID.randomUUID();
		users.save(new AppUser(buyer, buyer + "@example.com", "tok", now));

		ClaimResult result = claims.claim(new ClaimCommand(sale.getId(), buyer, "key-" + UUID.randomUUID(), 1));
		assertThat(result).isInstanceOf(ClaimResult.Accepted.class);
		Reservation reservation = ((ClaimResult.Accepted) result).reservation();
		assertThat(gate.remaining(sale.getId())).isEqualTo(1);

		ReservationAccepted event = new ReservationAccepted(reservation.getId(), reservation.saleId(),
				reservation.userId(), BigDecimal.TEN, reservation.createdAt(), reservation.expiresAt());
		fulfilment.fulfil(event);

		assertThat(claims.reservation(reservation.getId()).status()).isEqualTo(ReservationStatus.RELEASED);
		assertThat(orders.findByReservationId(reservation.getId())).isEmpty();
		assertThat(gate.remaining(sale.getId())).isEqualTo(2);
	}
}
