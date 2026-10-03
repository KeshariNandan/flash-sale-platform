package com.keshari.flashsale.application;

import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.SaleStatus;
import com.keshari.flashsale.infrastructure.persistence.FlashSaleRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-instance snapshot of each sale's status, window and hold TTL, refreshed
 * on a short interval, so rejecting a claim for a closed sale costs no database
 * read. Status changes made through {@code SaleService} evict the entry
 * immediately on this instance; other instances see it within the TTL.
 */
@Component
public class SaleStateCache {

	public record State(UUID saleId, SaleStatus status, Instant startAt, Instant endAt, Duration holdTtl,
			int perUserLimit, BigDecimal unitPrice, Instant loadedAt) {

		public boolean acceptsClaims(Instant now) {
			return status == SaleStatus.OPEN && !now.isBefore(startAt) && now.isBefore(endAt);
		}

		static State of(FlashSale s, Instant now) {
			return new State(s.getId(), s.status(), s.startAt(), s.endAt(), s.holdTtl(), s.perUserLimit(),
					s.unitPrice(), now);
		}
	}

	private final FlashSaleRepository sales;
	private final Clock clock;
	private final Duration ttl;
	private final Map<UUID, State> states = new ConcurrentHashMap<>();

	@Autowired
	public SaleStateCache(FlashSaleRepository sales, Clock clock) {
		this(sales, clock, Duration.ofSeconds(2));
	}

	SaleStateCache(FlashSaleRepository sales, Clock clock, Duration ttl) {
		this.sales = sales;
		this.clock = clock;
		this.ttl = ttl;
	}

	public State get(UUID saleId) {
		Instant now = clock.instant();
		State cached = states.get(saleId);
		if (cached != null && cached.loadedAt().plus(ttl).isAfter(now)) {
			return cached;
		}
		FlashSale sale = sales.findById(saleId).orElseThrow(() -> new NotFoundException("sale", saleId));
		State fresh = State.of(sale, now);
		states.put(saleId, fresh);
		return fresh;
	}

	public void evict(UUID saleId) {
		states.remove(saleId);
	}
}
