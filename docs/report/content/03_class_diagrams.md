# Class Diagrams

## Design principles

The low-level design follows a layered structure with dependencies pointing inwards. The `domain` package holds the entities and their state transitions and depends on nothing but the JDK and JPA annotations. The `application` package holds the use-case services and defines the interfaces, or ports, through which it needs the outside world: a `StockGate` for admission, a `PaymentGateway` for charging, a `ReservationEventPublisher` for messaging, and Spring Data repositories for persistence. The `infrastructure` package supplies the adapters that implement those ports with Redis, Kafka and PostgreSQL. The `api` package is the inbound adapter for HTTP. Spring wires the layers together at start-up.

Two consequences of this structure are worth noting because they were decisive during the project. First, the baseline and optimised claim strategies are simply two implementations of `StockGate`, `SqlStockGate` and `RedisStockGate`, selected by a configuration property. The controller, service and tests are identical for both, which is what makes the benchmark a fair comparison. Second, the domain classes can be unit tested for their state-transition rules without a database, a container or a Spring context, so those tests run in milliseconds and are executed on every build.

## Domain model

Figure 3.01 shows the domain classes. `FlashSale` owns the configuration of a sale and its lifecycle. `Inventory` is a separate entity, in a one-to-one relationship with the sale, because it is the only row that the baseline strategy updates on every accepted claim; isolating it keeps that hot row as narrow as possible and lets the optimistic-locking `version` column apply to stock alone. `Reservation` represents an accepted claim from the moment the gate accepts it until it is confirmed, released or expired. `CustomerOrder` is created only on successful payment and is linked one-to-one with its reservation; the unique constraint on `reservation_id` is the database-level guarantee that a reservation cannot produce two orders. `OutboxEvent` is the persistent record of a domain event awaiting publication.

![Domain class diagram](diagrams/fig_class_domain.png)

Table 3.01 summarises the responsibility of each domain class and the invariants it protects. The state-transition methods throw a domain exception when called in an illegal state, which the API layer translates to a 409 Conflict response.

Table: Domain classes, responsibilities and protected invariants
| Class | Responsibility | Invariant or rule enforced |
|---|---|---|
| FlashSale | Sale configuration and lifecycle (SCHEDULED, OPEN, SOLD_OUT, CLOSED). | Claims are accepted only while OPEN; start time precedes end time; stock and per-user limit are positive. |
| Inventory | Durable remaining stock for a sale. | `stock_remaining` is never negative; every change is either the baseline conditional decrement or a reconciler adjustment. |
| Reservation | An accepted claim awaiting fulfilment. | Only PENDING reservations may be confirmed, released or expired; each transition is recorded once. |
| CustomerOrder | A paid unit. | Exactly one order per confirmed reservation. |
| OutboxEvent | Domain event pending publication. | Written in the same transaction as the state change it describes; `published_at` set exactly once. |
| SaleStatus, ReservationStatus, OrderStatus | Enumerations of the lifecycles. | Closed set of states; no free-text status. |

## Application services and infrastructure adapters

Figure 3.02 shows the service and adapter classes and the interfaces between them. The controllers are intentionally thin: `ClaimController` reads the sale id from the path, the buyer id from the authenticated principal and the idempotency key from a header, builds a `ClaimCommand`, calls the service and maps the result to a response. All policy lives in `ClaimService`.

![Application services, ports and infrastructure adapters](diagrams/fig_class_services.png)

`ClaimService.claim` performs three steps. It asks the `StockGate` to reserve a unit for this buyer and key. If the gate rejects, it returns the corresponding result immediately and nothing is written to the database. If the gate accepts, it opens a transaction, saves a `Reservation` in state PENDING and an `OutboxEvent` describing the acceptance, and commits. If that transaction fails for any reason, the service calls `StockGate.release` to return the unit and rethrows, so a database outage cannot strand units in Redis. The reconciler covers the residual case in which the process dies between the two calls.

`OutboxRelay` runs on a fixed delay. It selects a batch of unpublished events with `FOR UPDATE SKIP LOCKED`, so several relay instances can run concurrently without publishing the same event twice, publishes them through `ReservationEventPublisher`, and marks them published. Because the relay may crash after publishing but before marking, delivery is at least once, and the consumer is written to tolerate that.

`OrderWorker` is a Kafka listener with manual acknowledgement. For each `ReservationAccepted` event it loads the reservation; if it is no longer PENDING the event is a duplicate or stale and is acknowledged without action. Otherwise it charges the `PaymentGateway`; on success it inserts the order with a conflict-ignoring statement and confirms the reservation in one transaction, on failure it releases the reservation and calls `StockGate.release`. Only then is the offset acknowledged. Exceptions trigger a bounded retry with back-off, after which the record is forwarded to a dead-letter topic for inspection.

`ReconciliationService` has two scheduled duties. `expireStaleHolds` finds PENDING reservations whose `expires_at` has passed, marks them EXPIRED and releases their units. `invariantReport` computes, for a sale, the number of confirmed orders, the number of active holds and the remaining stock in the gate, and checks that they sum to the initial stock; the result is exposed on the admin API and as a gauge so that a violation raises an alert.

`RedisStockGate` wraps two Lua scripts loaded once at start-up and invoked by their SHA. `SqlStockGate` implements the same interface with a conditional `UPDATE` on `inventory` and exists only to provide the baseline for the benchmark. `SimulatedPaymentGateway` sleeps for a configurable latency and fails a configurable fraction of charges, which allows the payment-failure and release paths to be exercised in tests and under load.

Table: Application and adapter classes (as implemented)
| Class | Package | Role |
|---|---|---|
| ClaimController | api | POST claim, GET reservation; maps `ClaimResult` to HTTP. |
| AdminSaleController | api | Create / open / close / inspect / stats. |
| CurrentBuyerArgumentResolver | api | JWT subject, or `X-Buyer-Id` when security is off. |
| SecurityConfig | config | HS256 resource server; admin routes require `ROLE_ADMIN`. |
| RedisRateLimiter / RateLimitFilter | infrastructure.cache / api | Per-user and global token buckets; 429 + Retry-After. |
| ApiExceptionHandler | api | RFC 9457 problem details. |
| ClaimService | application | Claim policy: cache, gate, persist, release on failure. |
| SaleService | application | Sale lifecycle; loads stock into the gate on open. |
| SaleStateCache | application | Short-TTL snapshot so closed sales skip Redis and SQL. |
| StockGate | application | Admission port (`tryReserve`, `release`, `remaining`). |
| SqlStockGate | infrastructure.persistence | Baseline conditional UPDATE. |
| RedisStockGate | infrastructure.cache | Optimised Lua EVAL. |
| RedisSaleKeys | infrastructure.cache | Hash-tagged key layout per sale. |
| OutboxRelay | infrastructure.messaging | Publishes unpublished outbox rows to Kafka. |
| ReservationEventPublisher | application | Port; Kafka adapter keys records by reservation id. |
| OrderWorker | infrastructure.messaging | Kafka listener; acks after fulfilment. |
| FulfilmentService | application | Charge, confirm + insert order, or release the hold. |
| PaymentGateway / SimulatedPaymentGateway | application / infrastructure.payment | Charge after admission; configurable latency and failure rate. |
| ReconciliationService | application | Expires stale PENDING holds, releases the gate, reopens SOLD_OUT, and reports the stock invariant. |

## Claim sequence

Figure 3.03 traces one claim through every participant, including the alternative outcomes. The important property visible in the diagram is that the buyer's response is sent as soon as the short database transaction commits; the payment, the Kafka round trip and the order insert all happen after the response and never add to the buyer's latency.

![Sequence diagram of the claim feature including alternative flows](diagrams/fig_claim_sequence.png)

## Error handling and API contract

Domain and service exceptions are translated centrally by `ApiExceptionHandler` into RFC 9457 problem-details responses. Each response carries a stable machine-readable `type` such as `sold-out`, `duplicate-claim`, `sale-not-open` or `gate-unavailable`, an HTTP status, and a human-readable detail. Clients, including the load generator, branch on the type rather than on the message text. Validation failures on request bodies and headers produce 400 responses with field-level detail. Unexpected exceptions produce a 500 response with a correlation id that also appears in the server log, and no stack trace is exposed.

## Concurrency model

Inside the JVM the claim path is stateless: no in-memory counters, no synchronised blocks and no caches that would make behaviour depend on which instance served the request. All shared state is in Redis or PostgreSQL, so any number of application instances can run behind the load balancer. The rate limiter follows the same rule; its buckets are Redis keys, so a buyer cannot escape the limit by being routed to a different instance. The Kafka listener container runs with a concurrency of three threads locally, matched to six partitions, so that fulfilment work is spread while ordering within a partition is preserved.
