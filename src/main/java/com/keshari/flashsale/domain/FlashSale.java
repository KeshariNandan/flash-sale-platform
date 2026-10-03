package com.keshari.flashsale.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Configuration and lifecycle of one limited-stock sale of a single SKU.
 * <p>
 * Invariants: start precedes end; stock and per-user limit are positive; claims
 * are accepted only while {@link SaleStatus#OPEN} and inside the window.
 */
@Entity
@Table(name = "flash_sale")
public class FlashSale implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(nullable = false, length = 64)
	private String sku;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
	private BigDecimal unitPrice;

	@Column(name = "start_at", nullable = false)
	private Instant startAt;

	@Column(name = "end_at", nullable = false)
	private Instant endAt;

	@Column(name = "initial_stock", nullable = false)
	private int initialStock;

	@Column(name = "per_user_limit", nullable = false)
	private int perUserLimit;

	@Column(name = "hold_ttl_seconds", nullable = false)
	private int holdTtlSeconds;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private SaleStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = false;

	protected FlashSale() {
		// JPA
	}

	private FlashSale(UUID id, String sku, String name, BigDecimal unitPrice, Instant startAt, Instant endAt,
			int initialStock, int perUserLimit, int holdTtlSeconds, Instant now) {
		this.id = id;
		this.sku = sku;
		this.name = name;
		this.unitPrice = unitPrice;
		this.startAt = startAt;
		this.endAt = endAt;
		this.initialStock = initialStock;
		this.perUserLimit = perUserLimit;
		this.holdTtlSeconds = holdTtlSeconds;
		this.status = SaleStatus.SCHEDULED;
		this.createdAt = now;
		this.isNew = true;
	}

	/** Create a sale in SCHEDULED state, validating every configuration rule. */
	public static FlashSale schedule(String sku, String name, BigDecimal unitPrice, Instant startAt, Instant endAt,
			int initialStock, int perUserLimit, int holdTtlSeconds, Instant now) {
		require(sku != null && !sku.isBlank(), "sku is required");
		require(name != null && !name.isBlank(), "name is required");
		require(unitPrice != null && unitPrice.signum() > 0, "unitPrice must be positive");
		require(startAt != null && endAt != null && endAt.isAfter(startAt), "endAt must be after startAt");
		require(initialStock > 0, "initialStock must be positive");
		require(perUserLimit > 0, "perUserLimit must be positive");
		require(holdTtlSeconds > 0, "holdTtlSeconds must be positive");
		return new FlashSale(UUID.randomUUID(), sku, name, unitPrice, startAt, endAt, initialStock, perUserLimit,
				holdTtlSeconds, now);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new DomainException("invalid-sale", message);
		}
	}

	// -- lifecycle -----------------------------------------------------------

	/** SCHEDULED -> OPEN. Stock must be loaded into the gate before claims are accepted. */
	public void open() {
		if (status != SaleStatus.SCHEDULED) {
			throw new IllegalStateTransitionException("FlashSale", status, "open");
		}
		status = SaleStatus.OPEN;
	}

	/** OPEN -> SOLD_OUT, when the gate reports zero remaining units. */
	public void markSoldOut() {
		if (status != SaleStatus.OPEN) {
			throw new IllegalStateTransitionException("FlashSale", status, "markSoldOut");
		}
		status = SaleStatus.SOLD_OUT;
	}

	/** SOLD_OUT -> OPEN, when a released or expired hold returns a unit. Prevents under-selling. */
	public void reopen() {
		if (status != SaleStatus.SOLD_OUT) {
			throw new IllegalStateTransitionException("FlashSale", status, "reopen");
		}
		status = SaleStatus.OPEN;
	}

	/** Any non-closed state -> CLOSED. Terminal. */
	public void close() {
		if (status == SaleStatus.CLOSED) {
			throw new IllegalStateTransitionException("FlashSale", status, "close");
		}
		status = SaleStatus.CLOSED;
	}

	/** True when a claim may be attempted right now. */
	public boolean acceptsClaims(Instant now) {
		return status == SaleStatus.OPEN && !now.isBefore(startAt) && now.isBefore(endAt);
	}

	public Duration holdTtl() {
		return Duration.ofSeconds(holdTtlSeconds);
	}

	// -- Persistable: ids are assigned in code, so tell Spring Data when to INSERT vs MERGE

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	// -- accessors -----------------------------------------------------------

	public String sku() {
		return sku;
	}

	public String name() {
		return name;
	}

	public BigDecimal unitPrice() {
		return unitPrice;
	}

	public Instant startAt() {
		return startAt;
	}

	public Instant endAt() {
		return endAt;
	}

	public int initialStock() {
		return initialStock;
	}

	public int perUserLimit() {
		return perUserLimit;
	}

	public int holdTtlSeconds() {
		return holdTtlSeconds;
	}

	public SaleStatus status() {
		return status;
	}

	public Instant createdAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof FlashSale other && Objects.equals(id, other.id));
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}
}
