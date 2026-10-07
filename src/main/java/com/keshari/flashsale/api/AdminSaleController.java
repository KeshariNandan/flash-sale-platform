package com.keshari.flashsale.api;

import com.keshari.flashsale.application.ReconciliationService;
import com.keshari.flashsale.application.SaleService;
import com.keshari.flashsale.domain.FlashSale;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sale administration: create, open, close, inspect. Role protection is added
 * with the security configuration; the endpoints are namespaced under /admin so
 * that rule is a single path matcher.
 */
@RestController
@RequestMapping("/api/v1/admin/sales")
public class AdminSaleController {

	private final SaleService sales;
	private final ReconciliationService reconciler;

	public AdminSaleController(SaleService sales, ReconciliationService reconciler) {
		this.sales = sales;
		this.reconciler = reconciler;
	}

	public record CreateSaleRequest(
			@NotBlank @Size(max = 64) String sku,
			@NotBlank @Size(max = 200) String name,
			@NotNull @DecimalMin(value = "0.01") BigDecimal unitPrice,
			@NotNull Instant startAt,
			@NotNull Instant endAt,
			@Min(1) int initialStock,
			@Min(1) Integer perUserLimit,
			@Min(1) Integer holdTtlSeconds) {

		public SaleService.CreateSale toCommand() {
			return new SaleService.CreateSale(sku, name, unitPrice, startAt, endAt, initialStock,
					perUserLimit == null ? 1 : perUserLimit, holdTtlSeconds == null ? 120 : holdTtlSeconds);
		}
	}

	public record SaleResponse(UUID id, String sku, String name, BigDecimal unitPrice, Instant startAt, Instant endAt,
			int initialStock, int perUserLimit, int holdTtlSeconds, String status, Instant createdAt) {

		static SaleResponse from(FlashSale s) {
			return new SaleResponse(s.getId(), s.sku(), s.name(), s.unitPrice(), s.startAt(), s.endAt(),
					s.initialStock(), s.perUserLimit(), s.holdTtlSeconds(), s.status().name(), s.createdAt());
		}
	}

	@PostMapping
	public ResponseEntity<SaleResponse> create(@Valid @RequestBody CreateSaleRequest request) {
		FlashSale sale = sales.create(request.toCommand());
		return ResponseEntity.created(URI.create("/api/v1/admin/sales/" + sale.getId()))
				.body(SaleResponse.from(sale));
	}

	@GetMapping
	public List<SaleResponse> list() {
		return sales.list().stream().map(SaleResponse::from).toList();
	}

	@GetMapping("/{saleId}")
	public SaleResponse get(@PathVariable UUID saleId) {
		return SaleResponse.from(sales.get(saleId));
	}

	@PostMapping("/{saleId}/open")
	public SaleResponse open(@PathVariable UUID saleId) {
		return SaleResponse.from(sales.open(saleId));
	}

	@PostMapping("/{saleId}/close")
	public SaleResponse close(@PathVariable UUID saleId) {
		return SaleResponse.from(sales.close(saleId));
	}

	@GetMapping("/{saleId}/stats")
	public SaleService.SaleStats stats(@PathVariable UUID saleId) {
		return sales.stats(saleId);
	}

	@GetMapping("/{saleId}/invariant")
	public ReconciliationService.InvariantReport invariant(@PathVariable UUID saleId) {
		return reconciler.invariantReport(saleId);
	}

	@PostMapping("/{saleId}/reconcile")
	public ReconciliationService.InvariantReport reconcile(@PathVariable UUID saleId) {
		return reconciler.reconcile(saleId);
	}
}
