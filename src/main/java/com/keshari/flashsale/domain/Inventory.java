package com.keshari.flashsale.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable remaining stock for a sale, kept in its own narrow row (1:1 with
 * {@link FlashSale}) so the baseline strategy's hot UPDATE and optimistic
 * locking touch stock alone. {@code stockRemaining} is never negative; the
 * database CHECK constraint enforces the same rule.
 */
@Entity
@Table(name = "inventory")
public class Inventory {

	@Id
	@Column(name = "sale_id")
	private UUID saleId;

	@Column(name = "stock_remaining", nullable = false)
	private int stockRemaining;

	@Version
	@Column(nullable = false)
	private long version;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Inventory() {
		// JPA
	}

	public Inventory(UUID saleId, int initialStock, Instant now) {
		if (initialStock < 0) {
			throw new DomainException("invalid-stock", "initial stock cannot be negative");
		}
		this.saleId = saleId;
		this.stockRemaining = initialStock;
		this.updatedAt = now;
	}

	/** Decrement by one if a unit remains; returns false (and changes nothing) when sold out. */
	public boolean tryDecrement(Instant now) {
		if (stockRemaining <= 0) {
			return false;
		}
		stockRemaining--;
		updatedAt = now;
		return true;
	}

	/** Return one unit to stock (payment failure, expiry). */
	public void increment(Instant now) {
		stockRemaining++;
		updatedAt = now;
	}

	public UUID saleId() {
		return saleId;
	}

	public int stockRemaining() {
		return stockRemaining;
	}

	public long version() {
		return version;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}
