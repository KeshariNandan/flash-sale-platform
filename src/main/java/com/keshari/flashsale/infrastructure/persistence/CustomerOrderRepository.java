package com.keshari.flashsale.infrastructure.persistence;

import com.keshari.flashsale.domain.CustomerOrder;
import com.keshari.flashsale.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

	Optional<CustomerOrder> findByReservationId(UUID reservationId);

	long countBySaleIdAndStatus(UUID saleId, OrderStatus status);

	/**
	 * At-least-once Kafka deliveries insert the same reservation twice; the unique
	 * index on reservation_id makes the second write a no-op.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO customer_order (id, reservation_id, sale_id, user_id, amount, payment_ref, status, created_at)
			VALUES (:id, :reservationId, :saleId, :userId, :amount, :paymentRef, 'PAID', :createdAt)
			ON CONFLICT (reservation_id) DO NOTHING
			""", nativeQuery = true)
	int insertPaidIgnore(@Param("id") UUID id, @Param("reservationId") UUID reservationId, @Param("saleId") UUID saleId,
			@Param("userId") String userId, @Param("amount") BigDecimal amount, @Param("paymentRef") String paymentRef,
			@Param("createdAt") Instant createdAt);
}
