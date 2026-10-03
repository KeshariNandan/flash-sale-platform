# Requirement Gathering

## Approach

Requirements came from three sources. A study of how large retailers and ticketing platforms run limited-stock sales established the shape of the traffic and the rules common to all of them: a scheduled start, a fixed quantity, a per-customer limit, a short payment window and a hard rule against overselling. A review of published engineering write-ups on flash sale and inventory systems, listed in the References, surfaced the failure modes a design must defend against. The capstone itself imposed the remaining constraints: one developer, a seven-day build, a laptop as the only benchmark host, and a report that traces one feature end to end with measured results.

Requirements were split into functional requirements, what the system does, and non-functional requirements, how well it must do it. Each functional requirement maps to at least one automated test; each non-functional requirement maps to a test, a benchmark measurement or an explicit design decision.

## Users and actors

Table 2.01 lists the actors. The scheduler is an actor because fulfilment and hold expiry are triggered by time rather than by a person.

Table: Users and actors of the flash sale platform
| Actor | Description | Primary goals |
|---|---|---|
| Buyer | Authenticated customer with a saved payment method; represented in this project by the k6 load generator. | See whether stock remains, claim a unit, learn quickly whether it succeeded, check reservation status. |
| Sale administrator | Merchandising or operations user who configures and controls sales. | Create a sale (SKU, price, stock, window, per-user limit); open or close it; view statistics and the invariant report. |
| Operations engineer | On-call engineer for the running service. | Observe health, throughput, rejection reasons and latency; be alerted on invariant failure or a dependency outage. |
| Scheduler (system) | Time-driven processes inside the service. | Open and close sales on time, relay outbox events, fulfil reservations, expire stale holds, run reconciliation. |
| Payment gateway (external) | Third-party provider, simulated here. | Charge the saved payment method and return success or failure. |

## Use cases

Figure 2.01 groups the use cases by actor. The central one is **Claim a unit**; every other buyer-facing use case supports it, and every system use case completes it or cleans up after it.

![Use case diagram](diagrams/fig_use_cases.png)

Table 2.02 details the primary use case. The alternative flows are the ones the concurrency tests exercise directly.

Table: Use case detail for UC-02 Claim a unit
| Element | Description |
|---|---|
| Actor | Buyer |
| Preconditions | Buyer is authenticated; sale is OPEN; buyer has a saved payment method. |
| Trigger | Buyer submits a claim with a client-generated idempotency key. |
| Main flow | 1. Check the buyer's request rate and the global admission rate. 2. Atomically check remaining stock, the buyer's prior claims and the idempotency key. 3. Decrement stock, record a hold with a TTL, return a reservation id and expiry. 4. Asynchronously charge the payment method and create the order. 5. Buyer polls or is notified of confirmation. |
| A: sold out | Step 2 finds no stock. Respond immediately; no database write. |
| B: duplicate | Buyer already holds or bought a unit, or the key was seen before. Return the original outcome; take no additional unit. |
| C: payment failure | Step 4 fails. Mark the reservation released, return the unit to stock, inform the buyer. |
| D: abandoned | Hold expires before payment completes. Reconciler marks it expired and returns the unit. |
| E: dependency down | Redis unreachable. Refuse new claims with a retry-after hint rather than fall back to a path that could oversell. |
| Postconditions | confirmed orders + active holds + remaining stock = initial stock; buyer holds at most the per-user limit. |

Figure 2.02 shows the lifecycle of a sale and of a reservation. Sold-out is not terminal: when abandoned holds are released the sale returns to open, which is what prevents under-selling.

![Sale and reservation state machines](diagrams/fig_sale_states.png)

## Functional requirements

Table: Functional requirements
| ID | Requirement | Verification |
|---|---|---|
| FR-01 | Administrator creates a sale with SKU, name, price, start and end time, initial stock, per-user limit and hold TTL. | Integration test; admin API. |
| FR-02 | Sale moves through SCHEDULED, OPEN, SOLD_OUT, CLOSED automatically at configured times and manually via the admin API. | State-machine unit tests; scheduler test. |
| FR-03 | On open, stock is loaded into the in-memory gate before the first claim is accepted. | Redis counter equals initial stock after open. |
| FR-04 | A buyer submits a claim and receives in the same response a reservation with expiry, or a sold-out, duplicate or closed result. | Controller and service tests. |
| FR-05 | The system never accepts more claims than the initial stock, under any concurrency. | Concurrency test against small stock; load-test oversell count. |
| FR-06 | A buyer cannot hold or buy more than the per-user limit in one sale, however many concurrent requests they send. | Concurrency test, one user, many threads. |
| FR-07 | A claim with an idempotency key already seen for that buyer returns the original outcome and consumes no stock. | Service test replaying the key. |
| FR-08 | Every accepted claim is persisted as a pending reservation together with an outbox event in one transaction. | Outbox row count equals reservation count. |
| FR-09 | Accepted reservations are published to Kafka at least once and consumed by the order worker. | Testcontainers Kafka integration test. |
| FR-10 | The worker charges payment and creates exactly one order per reservation even if the event is delivered twice. | Worker test with duplicate delivery. |
| FR-11 | On payment failure the reservation is released and its unit returned to stock. | Worker test with failing gateway. |
| FR-12 | Holds not confirmed within the TTL are expired and their units returned. | Reconciler test with advanced clock. |
| FR-13 | A buyer can query the status of a reservation. | Controller test. |
| FR-14 | Administrator views per-sale statistics and the invariant check result. | Admin API test. |
| FR-15 | Health and metrics endpoints report dependency state and counters for every gate outcome. | Actuator endpoint test. |

## Non-functional requirements

Table: Non-functional requirements
| ID | Category | Requirement | How it is met |
|---|---|---|---|
| NFR-01 | Correctness | Zero oversell and zero duplicate orders in every test and benchmark run. | Atomic Lua gate; database constraints; idempotent consumer. |
| NFR-02 | Latency | Rejections are decided without a database round trip; p99 of the optimised path is reported against the baseline. | Redis gate on the hot path; benchmark chapter. |
| NFR-03 | Throughput | One million attempts from a bounded client pool complete on the benchmark host with no errors other than intended rejections. | Load test result. |
| NFR-04 | Availability | Redis failure stops admission (fail closed) but not fulfilment in flight; Kafka failure delays fulfilment but loses no accepted reservation. | Outbox pattern; explicit 503; tests. |
| NFR-05 | Durability | No accepted reservation is lost after the client receives 202. | Reservation and outbox committed before responding. |
| NFR-06 | Security | Buyer identity only from a verified JWT; admin operations require an admin role; no secrets in the repository. | Spring Security resource server; role checks; environment configuration. |
| NFR-07 | Fairness | A buyer is limited to a few attempts per second; the global admission rate is capped. | Redis token-bucket rate limiter. |
| NFR-08 | Observability | Every gate decision, publish, consume, payment and reconciliation run is counted and timed; logs carry a correlation id. | Micrometer; MDC logging. |
| NFR-09 | Operability | Full stack starts locally with one command; schema migrates automatically. | Docker Compose; Flyway. |
| NFR-10 | Maintainability | Business rules independent of frameworks; infrastructure behind interfaces so baseline and optimised paths swap by configuration. | Layered packages; ports and adapters. |
| NFR-11 | Testability | Integration tests run against real PostgreSQL, Redis and Kafka. | Testcontainers. |

## Feature set

Table 2.05 lists the features with priority. Priority 1 features are required for the invariants and the benchmark; priority 2 make the system operable; priority 3 were stretch goals, discussed in the Conclusion where not completed.

Table: Feature set with priority
| Area | Feature | Priority | Notes |
|---|---|---|---|
| Sale management | Create, open, close; automatic transitions on schedule | 1 | Admin role. |
| Sale management | Pre-warm stock into Redis on open | 1 | Idempotent. |
| Claim | Atomic claim with stock, per-user and idempotency checks | 1 | Single Lua script. |
| Claim | Hold with TTL; expiry returned to buyer | 1 | TTL per sale. |
| Claim | Idempotent replay of prior outcome | 1 | Redis, 24 h TTL. |
| Claim | RFC 9457 problem-details errors | 2 | Machine-readable reason codes. |
| Admission control | Per-user and global token-bucket rate limiting | 2 | 429 with Retry-After. |
| Fulfilment | Transactional outbox and scheduled relay | 1 | SKIP LOCKED batches. |
| Fulfilment | Kafka topic partitioned by reservation id | 1 | Six partitions locally. |
| Fulfilment | Worker with idempotent insert, retries, dead-letter topic | 1 | Manual acknowledgement. |
| Fulfilment | Simulated payment gateway, configurable failure and latency | 1 | Deterministic benchmarks. |
| Reconciliation | Expire stale holds; return units | 1 | Runs every few seconds. |
| Reconciliation | Invariant report per sale | 1 | Admin API and metric. |
| Baseline | SQL-only claim strategy selectable by configuration | 1 | Benchmark only. |
| Observability | Metrics, health indicators, correlation ids | 2 | Actuator, Micrometer. |
| Security | JWT resource server; role-based admin endpoints | 2 | HS256 in development. |
| Scale | Striped stock keys across a Redis cluster | 3 | Designed; see Conclusion. |
| Scale | Virtual waiting room ahead of the gate | 3 | Designed; see Conclusion. |

## Constraints and assumptions

The benchmark host is one laptop running the load generator, the application and all three data services at once, so absolute throughput figures describe the relative cost of the two designs, not production capacity. Buyers are assumed to have a saved payment method, so the purchase is a single action with no interactive payment step inside the hold window. Clock skew between application instances and Redis is assumed small relative to the hold TTL; hold expiry is evaluated by Redis's clock and reservation expiry by the database clock, and the reconciler tolerates the difference.
