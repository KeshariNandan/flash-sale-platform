# Optimised Redis gate — 1,000,000 claim attempts

Measured on 2026-10-08 against `flashsale.gate=redis` on the same laptop as
the 2026-10-04 SQL baseline. JWT authentication was on; the rate limiter was
off so admission cost is the only variable.

## Environment

| Parameter | Value |
|---|---|
| Host | Apple M4 Pro, 14 cores, 24 GB, macOS 26.6.2 (arm64) |
| Colima VM | 4 CPUs, 8 GB RAM |
| App | Spring Boot 4.0.2, Temurin 25, virtual threads, port 8085, `flashsale.gate=redis`, `flashsale.rate-limit.enabled=false` |
| Gate | `RedisStockGate` (Lua `reserve.lua`) |
| Auth | HS256 buyer JWTs minted in k6 `setup` (`lt-0` … `lt-49999`) |
| k6 | v1.3.0, shared-iterations, 200 VUs, 1,000,000 iterations, keep-alive |
| Sale | `6e3c3fd2-e64a-4c48-9c0b-4833f8c89cb2`, stock 1,000, per-user limit 1, hold TTL 3,600 s |

## k6 summary

| Metric | Value |
|---|---|
| Wall-clock duration | 65.8 s (1 m 05.8 s) |
| Achieved request rate | 15,201 req/s |
| Iterations completed | 1,000,000 / 1,000,000 |
| Latency p50 / p95 / p99 | 10.85 / 24.40 / 50.47 ms |
| Maximum latency | 798.29 ms |
| 202 accepted | 1,000 |
| 409 sold-out | 979,617 |
| 409 duplicate-claim | 19,383 |
| 429 rate limited | 0 (limiter disabled for the comparison) |
| 200 replay | 0 |
| 5xx / other | 0 |

HTTP 2xx only (the 1,000 accepted claims): p50 118 ms, p95 324 ms, p99 599 ms.
Winners still persist a reservation row; rejects never touch PostgreSQL.

## Ledger snapshot (`GET /api/v1/admin/sales/{id}/stats`)

Taken immediately after k6 returned.

| Field | Value |
|---|---|
| initialStock | 1,000 |
| stockRemaining (Postgres inventory) | 1,000 (unused on the Redis path) |
| gateRemaining | 0 |
| pending | 0 |
| confirmed | 1,000 |
| paidOrders | 1,000 |
| released / expired | 0 / 0 |
| accounted (gateRemaining + pending + confirmed) | 1,000 |
| oversell | **0** |

The order worker confirmed every accepted reservation during the run; the first
ledger snapshot already showed `pending = 0`. Invariant
`confirmed + pending + gate remaining = initial stock` held.

## Versus baseline

| Metric | SQL baseline (4 Oct) | Redis (8 Oct) | Ratio |
|---|---|---|---|
| Duration | 222.8 s | 65.8 s | 3.4× shorter |
| Request rate | 4,488 req/s | 15,201 req/s | 3.4× |
| Overall p99 | 99.17 ms | 50.47 ms | 1.96× lower |
| Oversell | 0 | 0 | same |
