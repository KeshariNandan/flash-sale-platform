# Baseline SQL gate — 1,000,000 claim attempts

Measured on 2026-10-04 against `flashsale.gate=sql`. This is the slow path:
every attempt, including sold-out rejects, takes the inventory-row lock.

## Environment

| Parameter | Value |
|---|---|
| Host | Apple M4 Pro, 14 cores, 24 GB, macOS 26.6.2 (arm64) |
| Colima VM | 4 CPUs, 8 GB RAM, Docker 29.5.2 |
| App | Spring Boot 4.0.2, Temurin 25.0.2, virtual threads, port 8085, default Hikari pool |
| Gate | `SqlStockGate` (`UPDATE inventory SET stock_remaining = stock_remaining - 1 WHERE stock_remaining > 0`) |
| k6 | v1.3.0, shared-iterations, 200 VUs, 1,000,000 iterations, keep-alive |
| Sale | `e827a3e2-e371-4c2d-969e-3226c622422b`, stock 1,000, per-user limit 1, hold TTL 3,600 s |
| Buyer pool | 50,000 (`lt-0` … `lt-49999`), fresh `Idempotency-Key` per iteration |

Generator and system under test shared this laptop. Absolute RPS is a
comparison baseline, not a production capacity number.

## k6 summary

| Metric | Value |
|---|---|
| Wall-clock duration | 222.8 s (3 m 42.8 s) |
| Achieved request rate | 4,488 req/s |
| Iterations completed | 1,000,000 / 1,000,000 |
| Latency p50 / p95 / p99 | 42.21 / 77.71 / 99.17 ms |
| Maximum latency | 605.83 ms |
| 202 accepted | 1,000 |
| 409 sold-out | 979,563 |
| 409 duplicate-claim | 19,437 |
| 200 replay | 0 |
| 5xx / other | 0 |

k6 reports `http_req_failed` ≈ 99.89 % because it treats HTTP 409 as failure.
Those 409s are the expected sold-out and duplicate outcomes, not errors.

The `expected_response:true` trend (HTTP 2xx only — the 1,000 accepted claims)
is slower than the mix: p50 144 ms, p95 312 ms, p99 416 ms. That is the
inventory-row lock queue that rejected attempts also join, which is why the
baseline cannot keep accept latency flat once the sale is contested.

## Ledger snapshot (`GET /api/v1/admin/sales/{id}/stats`)

| Field | Value |
|---|---|
| initialStock | 1,000 |
| stockRemaining | 0 |
| pending | 1,000 |
| confirmed | 0 (no order worker yet) |
| released / expired | 0 / 0 |
| accounted (remaining + pending + confirmed) | 1,000 |
| oversell | **0** |

Invariant held: every accepted unit is a PENDING reservation and remaining
stock is zero. No extra unit was created.

## What this run is for

The same script, sale shape and machine will be used for `flashsale.gate=redis`.
The comparison that matters is whether rejected attempts (979,563 of them)
stop taking a Postgres row lock.
