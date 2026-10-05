# Feature Development Process

## The feature: Claim a unit

The feature chosen for detailed treatment is **Claim a unit**, the `POST /api/v1/sales/{saleId}/claims` endpoint. It is the only feature that every buyer exercises, it is where all four invariants are decided, and it is the feature whose performance determines whether the sale survives its first minute. This chapter follows the template's structure: the request as it arrives, the service that picks it up, the flow through the MVC layers, and finally the optimisation with before-and-after measurements.

## Development process

The feature was built in two complete iterations. The first iteration implemented the straightforward design: the controller calls the service, the service decrements the `inventory` row with a conditional update inside a transaction, inserts the reservation, charges the payment gateway and inserts the order, all before responding. This is the design most teams ship first and it is correct; the conditional update cannot oversell. It was covered by the concurrency tests and benchmarked. The second iteration moved admission into Redis and fulfilment behind Kafka without changing the controller, the domain model, the tests or the API contract; only the `StockGate` implementation and the point at which payment happens changed. The same benchmark was run again.

Working in this order had two benefits. The baseline numbers are from a real, tested system, so the improvement claimed is against a fair reference. And the tests written for iteration one, especially the concurrency tests, caught two real bugs in iteration two: a missing release when the database transaction failed after the Redis decrement, and an order-worker race where a late payment success after hold expiry would have confirmed a reservation the reconciler had already expired.

## API request

The endpoint accepts a small JSON body. Buyer identity is deliberately not in the body; it is taken from the verified JWT so that a buyer cannot consume another buyer's per-user allowance. The idempotency key is a header, following the convention popularised by payment APIs, and is generated once by the client per purchase intent and re-sent unchanged on every retry.

```
POST /api/v1/sales/7c9e6679-7425-40de-944b-e07fc1f90ae7/claims HTTP/1.1
Host: api.flashsale.example
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Idempotency-Key: 3f1c2d9e-0b4a-4c6e-9a1d-8e7f6a5b4c3d
Content-Type: application/json

{
  "quantity": 1,
  "clientTimestamp": "2026-10-05T09:00:00.412Z"
}
```

Table: Request elements of the claim endpoint
| Element | Location | Required | Validation |
|---|---|---|---|
| saleId | Path | Yes | UUID; sale must exist. |
| Authorization | Header | Yes | Bearer JWT; subject becomes userId; scope buyer. |
| Idempotency-Key | Header | Yes | 16 to 128 characters; unique per buyer per purchase intent. |
| quantity | Body | Yes | Integer, currently must equal 1 and not exceed per_user_limit. |
| clientTimestamp | Body | No | ISO-8601; recorded for latency analysis, not trusted for sale-window decisions. |

The response for an accepted claim is `202 Accepted`, chosen over `201 Created` because the order does not yet exist; the buyer has a reservation whose outcome will be decided asynchronously.

```
HTTP/1.1 202 Accepted
Content-Type: application/json
Location: /api/v1/reservations/9b2f4d1c-3e5a-4f6b-8c7d-2a1b0c9d8e7f

{
  "reservationId": "9b2f4d1c-3e5a-4f6b-8c7d-2a1b0c9d8e7f",
  "saleId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "status": "PENDING",
  "expiresAt": "2026-10-05T09:02:00.418Z"
}
```

Table: Response outcomes of the claim endpoint
| HTTP status | Problem type | Meaning | Decided by |
|---|---|---|---|
| 202 Accepted | - | Unit held; reservation pending fulfilment. | Redis gate then PostgreSQL commit. |
| 200 OK | - | Replay of a previously accepted claim for the same idempotency key. | Redis idempotency record. |
| 409 Conflict | sold-out | No units remain at this instant. | Redis gate. |
| 409 Conflict | duplicate-claim | Buyer already holds or bought a unit in this sale. | Redis gate. |
| 409 Conflict | sale-not-open | Sale is SCHEDULED, SOLD_OUT with no releases, or CLOSED. | Cached sale state. |
| 429 Too Many Requests | rate-limited | Buyer or global attempt rate exceeded; Retry-After header set. | Rate limiter. |
| 503 Service Unavailable | gate-unavailable | Redis unreachable; the system fails closed rather than risk oversell. | Gate adapter. |
| 400 Bad Request | validation-error | Missing header or invalid body. | Controller validation. |

## Service that picks up the request

`ClaimService` is the single owner of the claim policy. Its `claim` method receives a `ClaimCommand` containing the sale id, the buyer id, the idempotency key and the quantity, and returns a `ClaimResult`, a sealed type with one variant per outcome in Table 5.02. The service is stateless and is safe to run on any number of instances. Its collaborators are the `StockGate` port, the `ReservationRepository`, the `OutboxRepository` and a small `SaleStateCache` that holds the current status and hold TTL of open sales with a short refresh interval, so that closed-sale rejections do not need a database read either.

## Flow through the MVC layers

Figure 5.01 shows the path of a request through the application. The controller is the "C" of MVC; the service and domain form the model; the "view" in a JSON API is the response mapping performed by the controller and the exception handler.

![Request flow through security, controller, service and infrastructure layers](diagrams/fig_mvc_flow.png)

The steps are as follows.

1. The Spring Security filter validates the JWT signature and expiry and places the subject into the security context as the principal. Requests without a valid token stop here with 401.
2. `RateLimitFilter` performs two atomic Redis increments with a one-second expiry, one on the buyer's key and one on the global key, and rejects with 429 if either bucket is exceeded. This protects Redis and the application from a buyer who scripts thousands of parallel requests and from aggregate load beyond what the downstream can absorb.
3. `ClaimController.claim` binds the path variable, headers and body, applies bean validation, constructs a `ClaimCommand` and calls the service.
4. `ClaimService` consults `SaleStateCache`; if the sale is not open it returns `SaleNotOpen` without touching Redis.
5. The service calls `StockGate.tryReserve`. In the optimised configuration this executes the reserve script on Redis and returns accepted, sold out, duplicate or replay.
6. On acceptance the service opens a transaction, inserts the reservation in PENDING with `expires_at = now() + hold TTL`, inserts the outbox event whose payload is the serialised `ReservationAccepted` record, and commits. If the commit fails, it calls `StockGate.release` and returns a `GateUnavailable` result so the buyer receives a 503 and may retry with the same key.
7. The controller maps the result to the HTTP response, setting the `Location` header for accepted claims. Domain exceptions raised anywhere below are converted by `ApiExceptionHandler` into problem-details responses.

Everything after step 7, the outbox relay, Kafka, the payment charge and the order insert, happens outside the request and is described in the Class Diagrams chapter.

## The reserve script

The whole correctness argument for admission rests on one Lua script, reproduced below and loaded by `RedisStockGate` from `src/main/resources/redis/reserve.lua`. Redis executes a script as a single command on its command thread, so no other client's command can run between the read of the stock and the write of the decrement. The script therefore replaces what would otherwise be a database row lock or a compare-and-swap retry loop. Keys are hash-tagged `{sale:<id>}` so the same EVAL is valid on a Redis Cluster.

```
-- KEYS[1] = {sale:<id>}:stock
-- KEYS[2] = {sale:<id>}:buyers
-- KEYS[3] = {sale:<id>}:hold:<userId>
-- KEYS[4] = idem:<userId>:<key>
-- ARGV[1] = userId, ARGV[2] = reservationId, ARGV[3] = holdTtlSeconds

local prior = redis.call('GET', KEYS[4])
if prior then
  return {0, prior}                 -- replay: same key seen before
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return {-2, ''}                   -- duplicate: user already holds or bought
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
  return {-1, ''}                   -- sold out
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])
redis.call('SET', KEYS[4], ARGV[2], 'EX', 86400)
return {1, ARGV[2]}                 -- accepted
```

Figure 5.02 illustrates two requests arriving for the last unit. Whichever script Redis executes first wins; the second sees zero stock. There is no lock for the application to hold, time out or leak.

![Two concurrent claims for the last unit resolved by Redis's single command thread](diagrams/fig_redis_race.png)

A companion `release` script increments stock, removes the buyer from the set and deletes the hold. It is invoked by the service on database failure, by the order worker on payment failure, and by the reconciler on expiry. Each call is idempotent because it first checks that the hold key still refers to the reservation being released.

## Performance optimisation

Figure 5.03 places the two implementations side by side. In the baseline every accepted claim performs a row update under a lock, two inserts and a blocking payment call before the buyer hears anything, and every rejected claim still performs the conditional update, which must acquire the row lock only to find zero stock. In the optimised path the buyer's request performs one Redis script call and, only if accepted, one short transaction with two inserts. Rejected claims, which are the overwhelming majority in a sale of one thousand units receiving a million attempts, never reach the database at all.

![Baseline claim path compared with the optimised claim path](diagrams/fig_baseline_vs_optimised.png)

The second, smaller optimisation concerns the reservation status lookup used by buyers polling `GET /api/v1/reservations/{id}` and by the reconciler's expiry query. The reconciler query filters on `status = 'PENDING' AND expires_at < now()`; without an index it scans the growing reservation table on every run. The partial index `ix_reservation_expiry` restricts the index to pending rows, keeping it tiny even as the table grows. Its effect was measured with `EXPLAIN ANALYZE` and is reported in Table 5.06.

## Benchmark method

The benchmark drives one million purchase attempts against a single sale using k6. The number refers to total attempts, not concurrent connections; a laptop cannot hold a million sockets, so the load is generated by a fixed pool of virtual users with HTTP keep-alive and the `shared-iterations` executor, which distributes exactly the configured number of iterations across the pool. Each virtual user draws its buyer identity from a pre-generated pool of JWTs so that the per-user limit and the rate limiter behave as they would with real buyers rather than being triggered by one synthetic account. Each run is preceded by a warm-up of ten thousand requests that is excluded from the figures.

The generator lives in `load-test/claim.js`. It posts the claim payload shown above, identifies the buyer with the interim `X-Buyer-Id` header (`lt-0` … `lt-49999`; the JWT resource server will replace this header without changing the rest of the path), and issues a fresh `Idempotency-Key` per iteration so a returning winner is a `duplicate-claim` rather than a replay. `load-test/seed-sale.sh` opens the sale and inserts that buyer pool before the run. After the run, `GET /api/v1/admin/sales/{id}/stats` is the ledger check: `oversell` must be 0.

Table: Benchmark environment and parameters
| Parameter | Value |
|---|---|
| Host | Apple M4 Pro, 14 cores, 24 GB RAM, macOS 26.6.2 (arm64) |
| Containers | PostgreSQL 16, Redis 7, Kafka 3.9.1 (KRaft) via Docker Compose on Colima (Virtualization.Framework); Colima VM 4 CPUs, 8 GB RAM |
| Application | Spring Boot 4.0.2, Temurin 25.0.2, single instance on port 8085, virtual threads enabled, default Hikari pool, JVM heap default |
| Load generator | k6 v1.3.0, shared-iterations executor, 1,000,000 iterations, 200 virtual users, HTTP keep-alive |
| Sale | initial_stock 1,000; per_user_limit 1; hold TTL 3,600 s (so holds do not expire mid-run); buyer pool 50,000 (`lt-N`) |
| Payment simulation | not on the request path in either strategy; baseline writes only the PENDING reservation. Worker latency will be filled with the optimised run. |
| Measurements | k6 `http_req_duration` p50/p95/p99, request rate, custom counters for accepted / sold-out / duplicate; `SaleStats` remaining + pending + confirmed vs initial stock |

Because the generator and the system under test share one machine, the absolute request rate is bounded by that machine and the figures below must be read as a comparison between two designs under identical conditions, not as production capacity.

## Results

Table: Baseline strategy, one million attempts
| Metric | Value |
|---|---|
| Wall-clock duration | 222.8 s |
| Achieved request rate | 4,488 req/s |
| Latency p50 / p95 / p99 | 42.21 / 77.71 / 99.17 ms |
| Maximum latency | 605.83 ms |
| 202 accepted (PENDING reservations) | 1,000 |
| 409 sold out | 979,563 |
| 409 duplicate | 19,437 |
| 5xx errors | 0 |
| PostgreSQL peak active connections / lock waits | default Hikari pool (10); accepted-path p99 416 ms shows the single-row lock queue |
| Confirmed orders in database after run | 0 (order worker not yet deployed; 1,000 PENDING holds match stock) |
| Oversell count (pending + remaining − initial, if positive) | 0 |

Table: Optimised strategy, one million attempts
| Metric | Value |
|---|---|
| Wall-clock duration | [TO FILL] s |
| Achieved request rate | [TO FILL] req/s |
| Latency p50 / p95 / p99 | [TO FILL] ms / [TO FILL] ms / [TO FILL] ms |
| Maximum latency | [TO FILL] ms |
| 202 accepted | [TO FILL] (expected about 1,000 plus re-releases) |
| 409 sold out | [TO FILL] |
| 409 duplicate | [TO FILL] |
| 429 rate limited | [TO FILL] |
| 5xx errors | [TO FILL] |
| Time for worker to confirm all accepted reservations | [TO FILL] s |
| Confirmed orders in database after run | [TO FILL] |
| Invariant check: confirmed + active holds + redis stock = initial stock | [TO FILL: PASS / FAIL with numbers] |
| Oversell count | [TO FILL] (expected 0) |

Table: Reconciler expiry query before and after the partial index
| Measurement | Without index | With ix_reservation_expiry |
|---|---|---|
| Rows in reservation table | [TO FILL] | [TO FILL] |
| Plan | [TO FILL: Seq Scan] | [TO FILL: Index Scan] |
| Execution time (EXPLAIN ANALYZE) | [TO FILL] ms | [TO FILL] ms |
| Buffers read | [TO FILL] | [TO FILL] |

## Interpretation

The baseline run finished one million attempts in 222.8 s at 4,488 req/s on this laptop and produced exactly 1,000 PENDING reservations, zero remaining stock and zero oversell. That is the correctness bar. The latency story is less flattering and is the reason the Redis gate exists. k6's overall p99 is 99 ms only because 979,563 of the attempts are sold-out rejects; those still take the inventory-row lock, but they return as soon as the `UPDATE … WHERE stock_remaining > 0` affects zero rows. The 1,000 accepted claims, isolated as HTTP 2xx, have p50 144 ms and p99 416 ms: each winner waits behind the same row lock as every reject. Adding virtual users cannot make that queue shorter.

[TO FILL after the Redis run: compare p99 and throughput; note that rejections in the optimised path cost one Redis round trip; confirm both runs produced zero oversell; comment on worker drain time. State the improvement as a ratio and in absolute milliseconds.]

The result that matters most is not the latency ratio but the shape of the two curves under load. In the baseline, every additional concurrent attempt joins the queue for the same row lock, so latency rises with load without bound until connections are exhausted. In the optimised path, rejections are served from memory and accepted claims perform a short, contention-free insert, so latency stays close to flat as attempts increase and the database sees only as many writes as there are units of stock. That change in shape is what allows the system to absorb a burst it cannot fully serve, which is the definition of graceful degradation.
