package com.keshari.flashsale.application;

import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.Inventory;
import com.keshari.flashsale.infrastructure.persistence.FlashSaleRepository;
import com.keshari.flashsale.infrastructure.persistence.InventoryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator use cases: create, open and close sales. Opening a sale loads
 * its stock into the {@link StockGate} before any claim can be accepted (FR-03).
 */
@Service
public class SaleService {

	private final FlashSaleRepository sales;
	private final InventoryRepository inventories;
	private final StockGate stockGate;
	private final Clock clock;

	public SaleService(FlashSaleRepository sales, InventoryRepository inventories, StockGate stockGate, Clock clock) {
		this.sales = sales;
		this.inventories = inventories;
		this.stockGate = stockGate;
		this.clock = clock;
	}

	public record CreateSale(String sku, String name, BigDecimal unitPrice, Instant startAt, Instant endAt,
			int initialStock, int perUserLimit, int holdTtlSeconds) {
	}

	@Transactional
	public FlashSale create(CreateSale cmd) {
		Instant now = clock.instant();
		FlashSale sale = FlashSale.schedule(cmd.sku(), cmd.name(), cmd.unitPrice(), cmd.startAt(), cmd.endAt(),
				cmd.initialStock(), cmd.perUserLimit(), cmd.holdTtlSeconds(), now);
		sales.save(sale);
		inventories.save(new Inventory(sale.getId(), sale.initialStock(), now));
		return sale;
	}

	/**
	 * Stock is loaded into the gate after the status change commits; the gate
	 * call is idempotent so a crash between the two is repaired by re-opening.
	 */
	@Transactional
	public FlashSale open(UUID saleId) {
		FlashSale sale = get(saleId);
		sale.open();
		sales.save(sale);
		stockGate.open(sale.getId(), sale.initialStock());
		return sale;
	}

	@Transactional
	public FlashSale close(UUID saleId) {
		FlashSale sale = get(saleId);
		sale.close();
		sales.save(sale);
		stockGate.close(sale.getId());
		return sale;
	}

	@Transactional(readOnly = true)
	public FlashSale get(UUID saleId) {
		return sales.findById(saleId).orElseThrow(() -> new NotFoundException("sale", saleId));
	}

	@Transactional(readOnly = true)
	public List<FlashSale> list() {
		return sales.findAll();
	}

	@Transactional(readOnly = true)
	public Inventory inventory(UUID saleId) {
		return inventories.findById(saleId).orElseThrow(() -> new NotFoundException("inventory for sale", saleId));
	}
}
