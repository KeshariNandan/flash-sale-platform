package com.keshari.flashsale.infrastructure.persistence;

import com.keshari.flashsale.domain.Inventory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

	/**
	 * Baseline (SQL-only) admission: a conditional decrement that can never go
	 * below zero. Returns 1 when a unit was taken, 0 when sold out. Under
	 * concurrency every caller queues on this row's lock, which is exactly the
	 * behaviour the benchmark contrasts with the Redis gate.
	 */
	@Modifying
	@Query(value = """
			UPDATE inventory
			   SET stock_remaining = stock_remaining - 1,
			       version = version + 1,
			       updated_at = now()
			 WHERE sale_id = :saleId AND stock_remaining > 0
			""", nativeQuery = true)
	int tryDecrement(@Param("saleId") UUID saleId);

	/** Return one unit (payment failure, expiry). */
	@Modifying
	@Query(value = """
			UPDATE inventory
			   SET stock_remaining = stock_remaining + 1,
			       version = version + 1,
			       updated_at = now()
			 WHERE sale_id = :saleId
			""", nativeQuery = true)
	int increment(@Param("saleId") UUID saleId);
}
