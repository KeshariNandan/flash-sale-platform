package com.keshari.flashsale.infrastructure.persistence;

import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.SaleStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashSaleRepository extends JpaRepository<FlashSale, UUID> {

	List<FlashSale> findByStatus(SaleStatus status);
}
