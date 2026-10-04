package com.keshari.flashsale.application;

import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.Inventory;
import com.keshari.flashsale.domain.OrderStatus;
import com.keshari.flashsale.domain.ReservationStatus;
import com.keshari.flashsale.infrastructure.persistence.CustomerOrderRepository;
import com.keshari.flashsale.infrastructure.persistence.FlashSaleRepository;
import com.keshari.flashsale.infrastructure.persistence.InventoryRepository;
import com.keshari.flashsale.infrastructure.persistence.ReservationRepository;
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
	private final ReservationRepository reservations;
	private final CustomerOrderRepository orders;
	private final StockGate stockGate;
	private final SaleStateCache saleState;
	private final Clock clock;

	public SaleService(FlashSaleRepository sales, InventoryRepository inventories, ReservationRepository reservations,
			CustomerOrderRepository orders, StockGate stockGate, SaleStateCache saleState, Clock clock) {
		this.sales = sales;
		this.inventories = inventories;
		this.reservations = reservations;
		this.orders = orders;
		this.stockGate = stockGate;
		this.saleState = saleState;
		this.clock = clock;
	}

	public record SaleStats(UUID saleId, int initialStock, int stockRemaining, long pending, long confirmed,
			long released, long expired, long paidOrders, long accounted, long oversell) {
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
		saleState.evict(saleId);
		return sale;
	}

	@Transactional
	public FlashSale close(UUID saleId) {
		FlashSale sale = get(saleId);
		sale.close();
		sales.save(sale);
		stockGate.close(sale.getId());
		saleState.evict(saleId);
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

	/**
	 * Ledger snapshot used by the load-test harness and (later) the reconciler.
	 * {@code accounted} is remaining + pending + confirmed; {@code oversell} is
	 * how far that exceeds {@code initialStock} (must stay 0).
	 */
	@Transactional(readOnly = true)
	public SaleStats stats(UUID saleId) {
		FlashSale sale = get(saleId);
		Inventory inventory = inventory(saleId);
		long pending = reservations.countBySaleIdAndStatus(saleId, ReservationStatus.PENDING);
		long confirmed = reservations.countBySaleIdAndStatus(saleId, ReservationStatus.CONFIRMED);
		long released = reservations.countBySaleIdAndStatus(saleId, ReservationStatus.RELEASED);
		long expired = reservations.countBySaleIdAndStatus(saleId, ReservationStatus.EXPIRED);
		long paid = orders.countBySaleIdAndStatus(saleId, OrderStatus.PAID);
		long accounted = (long) inventory.stockRemaining() + pending + confirmed;
		long oversell = Math.max(0, accounted - sale.initialStock());
		return new SaleStats(saleId, sale.initialStock(), inventory.stockRemaining(), pending, confirmed, released,
				expired, paid, accounted, oversell);
	}
}
