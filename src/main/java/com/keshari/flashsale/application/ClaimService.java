package com.keshari.flashsale.application;

import com.keshari.flashsale.application.StockGate.GateDecision;
import com.keshari.flashsale.domain.OutboxEvent;
import com.keshari.flashsale.domain.Reservation;
import com.keshari.flashsale.infrastructure.persistence.ReservationRepository;
import com.keshari.flashsale.infrastructure.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Single owner of the claim policy (use case UC-02). Stateless; safe on any
 * number of instances. Steps:
 * <ol>
 *   <li>consult the sale snapshot; reject if not open (no gate or DB call)</li>
 *   <li>ask the {@link StockGate} for a unit</li>
 *   <li>on acceptance, persist the PENDING reservation and its outbox event in
 *       one short transaction; if that fails, release the unit and report
 *       {@link ClaimResult.GateUnavailable} so the buyer can retry with the same key</li>
 * </ol>
 * The database's partial unique indexes are the last word: if the gate's
 * duplicate check is bypassed, the insert fails and is classified here.
 */
@Service
public class ClaimService {

	private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

	private final SaleStateCache saleState;
	private final StockGate gate;
	private final ReservationRepository reservations;
	private final OutboxEventRepository outbox;
	private final TransactionTemplate tx;
	private final JsonMapper json;
	private final Clock clock;

	public ClaimService(SaleStateCache saleState, StockGate gate, ReservationRepository reservations,
			OutboxEventRepository outbox, TransactionTemplate tx, JsonMapper json, Clock clock) {
		this.saleState = saleState;
		this.gate = gate;
		this.reservations = reservations;
		this.outbox = outbox;
		this.tx = tx;
		this.json = json;
		this.clock = clock;
	}

	public ClaimResult claim(ClaimCommand cmd) {
		SaleStateCache.State sale = saleState.get(cmd.saleId());
		Instant now = clock.instant();
		if (!sale.acceptsClaims(now)) {
			return new ClaimResult.SaleNotOpen(sale.status());
		}

		UUID reservationId = UUID.randomUUID();
		GateDecision decision = gate.tryReserve(cmd.saleId(), cmd.userId(), cmd.idempotencyKey(), reservationId,
				sale.holdTtl());

		return switch (decision) {
			case GateDecision.SoldOut s -> new ClaimResult.SoldOut();
			case GateDecision.Duplicate d -> new ClaimResult.Duplicate();
			case GateDecision.Replay r -> replay(r.originalReservationId(), cmd);
			case GateDecision.Accepted a -> persistAccepted(cmd, sale, reservationId, now);
		};
	}

	private ClaimResult replay(UUID originalId, ClaimCommand cmd) {
		Optional<Reservation> original = reservations.findById(originalId)
				.or(() -> reservations.findByUserIdAndIdempotencyKey(cmd.userId(), cmd.idempotencyKey()));
		// the gate remembered the key but the row never committed (crash between the two):
		// treat as a duplicate rather than invent a reservation
		return original.<ClaimResult>map(ClaimResult.Replayed::new).orElseGet(ClaimResult.Duplicate::new);
	}

	private ClaimResult persistAccepted(ClaimCommand cmd, SaleStateCache.State sale, UUID reservationId, Instant now) {
		Instant expiresAt = now.plus(sale.holdTtl());
		try {
			Reservation saved = tx.execute(status -> {
				Reservation r = Reservation.pending(reservationId, cmd.saleId(), cmd.userId(), cmd.idempotencyKey(),
						expiresAt, now);
				reservations.save(r);
				var event = new ReservationAccepted(r.getId(), r.saleId(), r.userId(), sale.unitPrice(), now, expiresAt);
				outbox.save(OutboxEvent.reservationAccepted(r.getId(), json.writeValueAsString(event), now));
				return r;
			});
			return new ClaimResult.Accepted(saved);
		}
		catch (DataIntegrityViolationException e) {
			gate.release(cmd.saleId(), cmd.userId(), reservationId);
			return classifyConstraint(e, cmd);
		}
		catch (DataAccessException e) {
			gate.release(cmd.saleId(), cmd.userId(), reservationId);
			log.warn("persist after admission failed for sale={} user={}: {}", cmd.saleId(), cmd.userId(),
					e.getMostSpecificCause().getMessage());
			return new ClaimResult.GateUnavailable("persistence failure");
		}
	}

	/** Map the violated constraint to the business outcome it protects. */
	private ClaimResult classifyConstraint(DataIntegrityViolationException e, ClaimCommand cmd) {
		String constraint = constraintName(e);
		if (constraint == null) {
			return new ClaimResult.GateUnavailable("integrity violation");
		}
		return switch (constraint) {
			case "ux_reservation_sale_user" -> new ClaimResult.Duplicate();
			case "ux_reservation_user_key" -> reservations
					.findByUserIdAndIdempotencyKey(cmd.userId(), cmd.idempotencyKey())
					.<ClaimResult>map(ClaimResult.Replayed::new).orElseGet(ClaimResult.Duplicate::new);
			case "reservation_user_id_fkey" -> new ClaimResult.UnknownBuyer();
			default -> new ClaimResult.GateUnavailable("integrity violation: " + constraint);
		};
	}

	private static String constraintName(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
				return cve.getConstraintName();
			}
		}
		return null;
	}

	public Reservation reservation(UUID id) {
		return reservations.findById(id).orElseThrow(() -> new NotFoundException("reservation", id));
	}
}
