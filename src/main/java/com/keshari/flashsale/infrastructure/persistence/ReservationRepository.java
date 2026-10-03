package com.keshari.flashsale.infrastructure.persistence;

import com.keshari.flashsale.domain.Reservation;
import com.keshari.flashsale.domain.ReservationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

	/** Idempotency backstop when the Redis record has expired. */
	Optional<Reservation> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

	/** Stale holds for the reconciler; served by the partial index ix_reservation_expiry. */
	List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant cutoff, Limit limit);

	long countBySaleIdAndStatus(UUID saleId, ReservationStatus status);

	/** Does the buyer already hold or own a unit in this sale? (served by ux_reservation_sale_user) */
	boolean existsBySaleIdAndUserIdAndStatusIn(UUID saleId, String userId, Collection<ReservationStatus> statuses);
}
