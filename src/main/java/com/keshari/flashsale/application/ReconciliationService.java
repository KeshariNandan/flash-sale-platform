package com.keshari.flashsale.application;

import com.keshari.flashsale.config.FlashSaleProperties;
import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.Reservation;
import com.keshari.flashsale.domain.ReservationStatus;
import com.keshari.flashsale.domain.SaleStatus;
import com.keshari.flashsale.infrastructure.persistence.FlashSaleRepository;
import com.keshari.flashsale.infrastructure.persistence.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Returns abandoned holds to the gate and reports whether
 * {@code confirmed + pending + gate remaining} still equals initial stock.
 */
@Service
public class ReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

	private final ReservationRepository reservations;
	private final FlashSaleRepository sales;
	private final StockGate gate;
	private final SaleStateCache saleState;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final FlashSaleProperties properties;

	public ReconciliationService(ReservationRepository reservations, FlashSaleRepository sales, StockGate gate,
			SaleStateCache saleState, TransactionTemplate tx, Clock clock, FlashSaleProperties properties) {
		this.reservations = reservations;
		this.sales = sales;
		this.gate = gate;
		this.saleState = saleState;
		this.tx = tx;
		this.clock = clock;
		this.properties = properties;
	}

	public record InvariantReport(UUID saleId, int initialStock, long confirmed, long activeHolds, long gateRemaining,
			long accounted, boolean holds, long deficit, long surplus) {
	}

	@Scheduled(fixedDelayString = "${flashsale.reconciler.poll-ms:1000}")
	public void scheduledExpire() {
		if (!properties.reconciler().enabled()) {
			return;
		}
		int expired = expireStaleHolds();
		if (expired > 0) {
			log.info("reconciler expired {} stale hold(s)", expired);
		}
	}

	public int expireStaleHolds() {
		Instant now = clock.instant();
		List<Reservation> stale = reservations.findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, now,
				Limit.of(properties.reconciler().batchSize()));
		int expired = 0;
		for (Reservation snapshot : stale) {
			if (expireOne(snapshot, now)) {
				expired++;
			}
		}
		return expired;
	}

	public InvariantReport invariantReport(UUID saleId) {
		FlashSale sale = sales.findById(saleId).orElseThrow(() -> new NotFoundException("sale", saleId));
		long pending = reservations.countBySaleIdAndStatus(saleId, ReservationStatus.PENDING);
		long confirmed = reservations.countBySaleIdAndStatus(saleId, ReservationStatus.CONFIRMED);
		long remaining = gate.remaining(saleId);
		long accounted = pending + confirmed + remaining;
		long deficit = Math.max(0, sale.initialStock() - accounted);
		long surplus = Math.max(0, accounted - sale.initialStock());
		return new InvariantReport(saleId, sale.initialStock(), confirmed, pending, remaining, accounted,
				accounted == sale.initialStock(), deficit, surplus);
	}

	public InvariantReport reconcile(UUID saleId) {
		expireStaleHolds();
		return invariantReport(saleId);
	}

	private boolean expireOne(Reservation snapshot, Instant now) {
		Boolean expired = tx.execute(status -> {
			Reservation r = reservations.findById(snapshot.getId()).orElse(null);
			if (r == null || !r.isPending() || !r.isExpired(now)) {
				return false;
			}
			r.expire(now);
			reservations.save(r);
			reopenIfSoldOut(r.saleId());
			return true;
		});
		if (!Boolean.TRUE.equals(expired)) {
			return false;
		}
		gate.release(snapshot.saleId(), snapshot.userId(), snapshot.getId());
		return true;
	}

	private void reopenIfSoldOut(UUID saleId) {
		sales.findById(saleId).ifPresent(sale -> {
			if (sale.status() == SaleStatus.SOLD_OUT) {
				sale.reopen();
				sales.save(sale);
				saleState.evict(saleId);
			}
		});
	}
}
