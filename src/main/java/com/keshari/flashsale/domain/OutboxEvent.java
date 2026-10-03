package com.keshari.flashsale.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transactional outbox row: a domain event written in the same transaction as
 * the state change it describes, published later by the relay. The identity
 * column gives publication order; {@code publishedAt} is set exactly once.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

	public static final String AGGREGATE_RESERVATION = "RESERVATION";
	public static final String TYPE_RESERVATION_ACCEPTED = "ReservationAccepted";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "aggregate_type", nullable = false, length = 64)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false)
	private UUID aggregateId;

	@Column(name = "event_type", nullable = false, length = 64)
	private String eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String payload;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	protected OutboxEvent() {
		// JPA
	}

	private OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload, Instant now) {
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.createdAt = now;
	}

	/** The single event type this project publishes: a reservation was accepted and awaits fulfilment. */
	public static OutboxEvent reservationAccepted(UUID reservationId, String jsonPayload, Instant now) {
		return new OutboxEvent(AGGREGATE_RESERVATION, reservationId, TYPE_RESERVATION_ACCEPTED, jsonPayload, now);
	}

	public void markPublished(Instant now) {
		if (publishedAt != null) {
			throw new IllegalStateTransitionException("OutboxEvent", "PUBLISHED", "markPublished");
		}
		publishedAt = now;
	}

	public boolean isPublished() {
		return publishedAt != null;
	}

	public Long id() {
		return id;
	}

	public String aggregateType() {
		return aggregateType;
	}

	public UUID aggregateId() {
		return aggregateId;
	}

	public String eventType() {
		return eventType;
	}

	public String payload() {
		return payload;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant publishedAt() {
		return publishedAt;
	}
}
