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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * An accepted claim from the moment the gate admits it until it is confirmed
 * (paid), released (payment failed) or expired (hold TTL passed).
 * <p>
 * Only a PENDING reservation may transition, and each transition is recorded
 * once; attempts from a terminal state raise {@link IllegalStateTransitionException}.
 */
@Entity
@Table(name = "reservation")
public class Reservation implements Persistable<UUID> {

	@Id
	private UUID id;

	@Column(name = "sale_id", nullable = false)
	private UUID saleId;

	@Column(name = "user_id", nullable = false, length = 64)
	private String userId;

	@Column(name = "idempotency_key", nullable = false, length = 128)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ReservationStatus status;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Transient
	private boolean isNew = false;

	protected Reservation() {
		// JPA
	}

	private Reservation(UUID id, UUID saleId, String userId, String idempotencyKey, Instant expiresAt, Instant now) {
		this.id = id;
		this.saleId = saleId;
		this.userId = userId;
		this.idempotencyKey = idempotencyKey;
		this.status = ReservationStatus.PENDING;
		this.expiresAt = expiresAt;
		this.createdAt = now;
		this.updatedAt = now;
		this.isNew = true;
	}

	/** A freshly accepted claim. The id is supplied so Redis hold and database row share it. */
	public static Reservation pending(UUID id, UUID saleId, String userId, String idempotencyKey, Instant expiresAt,
			Instant now) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(saleId, "saleId");
		if (userId == null || userId.isBlank()) {
			throw new DomainException("invalid-reservation", "userId is required");
		}
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new DomainException("invalid-reservation", "idempotencyKey is required");
		}
		if (!expiresAt.isAfter(now)) {
			throw new DomainException("invalid-reservation", "expiresAt must be in the future");
		}
		return new Reservation(id, saleId, userId, idempotencyKey, expiresAt, now);
	}

	// -- transitions ---------------------------------------------------------

	/** PENDING -> CONFIRMED on successful payment. */
	public void confirm(Instant now) {
		transition(ReservationStatus.CONFIRMED, "confirm", now);
	}

	/** PENDING -> RELEASED on payment failure or database failure after admission. */
	public void release(Instant now) {
		transition(ReservationStatus.RELEASED, "release", now);
	}

	/** PENDING -> EXPIRED when the hold TTL has passed without confirmation. */
	public void expire(Instant now) {
		if (!isExpired(now)) {
			throw new DomainException("not-expired", "reservation " + id + " has not reached its expiry");
		}
		transition(ReservationStatus.EXPIRED, "expire", now);
	}

	private void transition(ReservationStatus target, String action, Instant now) {
		if (status.isTerminal()) {
			throw new IllegalStateTransitionException("Reservation", status, action);
		}
		status = target;
		updatedAt = now;
	}

	public boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}

	public boolean isPending() {
		return status == ReservationStatus.PENDING;
	}

	// -- Persistable ---------------------------------------------------------

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

	public UUID saleId() {
		return saleId;
	}

	public String userId() {
		return userId;
	}

	public String idempotencyKey() {
		return idempotencyKey;
	}

	public ReservationStatus status() {
		return status;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof Reservation other && Objects.equals(id, other.id));
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(id);
	}
}
