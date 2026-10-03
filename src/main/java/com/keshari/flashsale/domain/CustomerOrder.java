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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A paid unit. Exactly one order exists per confirmed reservation; the UNIQUE
 * constraint on {@code reservation_id} is the database-level guarantee.
 */
@Entity
@Table(name = "customer_order")
public class CustomerOrder implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "reservation_id", nullable = false, unique = true)
	private UUID reservationId;

	@Column(name = "sale_id", nullable = false)
	private UUID saleId;

	@Column(name = "user_id", nullable = false, length = 64)
	private String userId;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "payment_ref", nullable = false, length = 128)
	private String paymentRef;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private OrderStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = false;

	protected CustomerOrder() {
		// JPA
	}

	private CustomerOrder(UUID id, Reservation reservation, BigDecimal amount, String paymentRef, OrderStatus status,
			Instant now) {
		this.id = id;
		this.reservationId = reservation.getId();
		this.saleId = reservation.saleId();
		this.userId = reservation.userId();
		this.amount = amount;
		this.paymentRef = paymentRef;
		this.status = status;
		this.createdAt = now;
		this.isNew = true;
	}

	/** Record a successful charge for a reservation. */
	public static CustomerOrder paid(Reservation reservation, BigDecimal amount, String paymentRef, Instant now) {
		Objects.requireNonNull(reservation, "reservation");
		if (amount == null || amount.signum() <= 0) {
			throw new DomainException("invalid-order", "amount must be positive");
		}
		if (paymentRef == null || paymentRef.isBlank()) {
			throw new DomainException("invalid-order", "paymentRef is required");
		}
		return new CustomerOrder(UUID.randomUUID(), reservation, amount, paymentRef, OrderStatus.PAID, now);
	}

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

	public UUID reservationId() {
		return reservationId;
	}

	public UUID saleId() {
		return saleId;
	}

	public String userId() {
		return userId;
	}

	public BigDecimal amount() {
		return amount;
	}

	public String paymentRef() {
		return paymentRef;
	}

	public OrderStatus status() {
		return status;
	}

	public Instant createdAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof CustomerOrder other && Objects.equals(id, other.id));
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}
}
