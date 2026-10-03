package com.keshari.flashsale.infrastructure.persistence;

import com.keshari.flashsale.domain.CustomerOrder;
import com.keshari.flashsale.domain.OrderStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

	Optional<CustomerOrder> findByReservationId(UUID reservationId);

	long countBySaleIdAndStatus(UUID saleId, OrderStatus status);
}
