package com.keshari.flashsale.api;

import com.keshari.flashsale.application.ClaimCommand;
import com.keshari.flashsale.application.ClaimResult;
import com.keshari.flashsale.application.ClaimService;
import com.keshari.flashsale.domain.Reservation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The claim feature: {@code POST /api/v1/sales/{saleId}/claims}. Thin by design:
 * validate, build the command, call the service, map the sealed result to HTTP.
 */
@RestController
@RequestMapping("/api/v1")
public class ClaimController {

	public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

	private final ClaimService claims;

	public ClaimController(ClaimService claims) {
		this.claims = claims;
	}

	public record ClaimRequest(@Min(1) @Max(1) int quantity, Instant clientTimestamp) {
	}

	public record ReservationResponse(UUID reservationId, UUID saleId, String status, Instant expiresAt,
			Instant createdAt) {

		static ReservationResponse from(Reservation r) {
			return new ReservationResponse(r.getId(), r.saleId(), r.status().name(), r.expiresAt(), r.createdAt());
		}
	}

	@PostMapping("/sales/{saleId}/claims")
	public ResponseEntity<?> claim(@PathVariable UUID saleId, @CurrentBuyer String buyerId,
			@RequestHeader(IDEMPOTENCY_HEADER) @Size(min = 16, max = 128) String idempotencyKey,
			@Valid @RequestBody ClaimRequest body) {

		ClaimResult result = claims.claim(new ClaimCommand(saleId, buyerId, idempotencyKey, body.quantity()));

		return switch (result) {
			case ClaimResult.Accepted a -> ResponseEntity.accepted()
					.location(URI.create("/api/v1/reservations/" + a.reservation().getId()))
					.body(ReservationResponse.from(a.reservation()));
			case ClaimResult.Replayed r -> ResponseEntity.ok(ReservationResponse.from(r.reservation()));
			case ClaimResult.SoldOut s -> conflict("sold-out", "no units remain");
			case ClaimResult.Duplicate d -> conflict("duplicate-claim", "buyer already holds or bought a unit in this sale");
			case ClaimResult.SaleNotOpen n -> conflict("sale-not-open", "sale is " + n.status());
			case ClaimResult.UnknownBuyer u -> problem(HttpStatus.FORBIDDEN, "unknown-buyer", "buyer is not registered");
			case ClaimResult.GateUnavailable g -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.header("Retry-After", "1")
					.body(ApiExceptionHandler.problem(HttpStatus.SERVICE_UNAVAILABLE, "gate-unavailable", g.reason()));
		};
	}

	@GetMapping("/reservations/{reservationId}")
	public ReservationResponse reservation(@PathVariable UUID reservationId) {
		return ReservationResponse.from(claims.reservation(reservationId));
	}

	private static ResponseEntity<ProblemDetail> conflict(String type, String detail) {
		return problem(HttpStatus.CONFLICT, type, detail);
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail) {
		return ResponseEntity.status(status).body(ApiExceptionHandler.problem(status, type, detail));
	}
}
