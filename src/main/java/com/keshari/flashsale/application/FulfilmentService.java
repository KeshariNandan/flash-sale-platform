package com.keshari.flashsale.application;

import com.keshari.flashsale.application.PaymentGateway.PaymentResult;
import com.keshari.flashsale.domain.Reservation;
import com.keshari.flashsale.infrastructure.persistence.CustomerOrderRepository;
import com.keshari.flashsale.infrastructure.persistence.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns an accepted reservation into a paid order, or releases the hold when
 * payment fails. Safe under at-least-once delivery: a second call for a
 * reservation that is no longer PENDING is a no-op.
 */
@Service
public class FulfilmentService {

	private static final Logger log = LoggerFactory.getLogger(FulfilmentService.class);

	private final ReservationRepository reservations;
	private final CustomerOrderRepository orders;
	private final PaymentGateway payments;
	private final StockGate gate;
	private final TransactionTemplate tx;
	private final Clock clock;

	public FulfilmentService(ReservationRepository reservations, CustomerOrderRepository orders,
			PaymentGateway payments, StockGate gate, TransactionTemplate tx, Clock clock) {
		this.reservations = reservations;
		this.orders = orders;
		this.payments = payments;
		this.gate = gate;
		this.tx = tx;
		this.clock = clock;
	}

	public void fulfil(ReservationAccepted event) {
		Reservation current = reservations.findById(event.reservationId()).orElse(null);
		if (current == null || !current.isPending()) {
			return;
		}
		Instant now = clock.instant();
		if (current.isExpired(now)) {
			return;
		}

		PaymentResult result = payments.charge(current.userId(), event.amount(), current.getId());
		switch (result) {
			case PaymentResult.Succeeded s -> confirmPaid(current, event, s, now);
			case PaymentResult.Failed f -> releaseUnpaid(current, f.reason(), now);
		}
	}

	private void confirmPaid(Reservation reservation, ReservationAccepted event, PaymentResult.Succeeded payment,
			Instant now) {
		tx.executeWithoutResult(status -> {
			Reservation r = reservations.findById(reservation.getId()).orElse(null);
			if (r == null || !r.isPending()) {
				return;
			}
			orders.insertPaidIgnore(UUID.randomUUID(), r.getId(), r.saleId(), r.userId(), event.amount(),
					payment.paymentRef(), now);
			if (orders.findByReservationId(r.getId()).isPresent() && r.isPending()) {
				r.confirm(now);
				reservations.save(r);
			}
		});
	}

	private void releaseUnpaid(Reservation reservation, String reason, Instant now) {
		Boolean released = tx.execute(status -> {
			Reservation r = reservations.findById(reservation.getId()).orElse(null);
			if (r == null || !r.isPending()) {
				return false;
			}
			r.release(now);
			reservations.save(r);
			return true;
		});
		if (Boolean.TRUE.equals(released)) {
			gate.release(reservation.saleId(), reservation.userId(), reservation.getId());
			log.info("released reservation {} after payment failure: {}", reservation.getId(), reason);
		}
	}
}
