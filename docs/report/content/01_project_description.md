# Project Description

## The problem being solved

A flash sale is a short, scheduled sale of a limited quantity of a product at an attractive price. Retailers use it to launch products, clear inventory or drive traffic; ticketing platforms face the same shape of demand when a popular event goes on sale. What makes the problem interesting from an engineering point of view is not the volume of successful orders, which is bounded by stock and therefore small, but the volume of attempts. A sale of one thousand units can easily attract hundreds of thousands of attempts in its first minute, and almost all of them must be rejected quickly, fairly and without damaging the system.

Three failure modes appear in naive implementations. The first is **overselling**: two requests read the same stock value, both decide there is a unit left, and both write an order. The second is **collapse under load**: every request contends for the same database row, lock wait time becomes the dominant cost, connection pools are exhausted, and the whole application, including pages unrelated to the sale, becomes unavailable. The third is **unfairness and abuse**: a single buyer scripting many parallel requests can take a disproportionate share of the stock, and duplicate submissions caused by client retries produce duplicate orders.

This project treats a flash sale as a **reservation problem** rather than a shopping-cart problem. The service's job is to decide, for each attempt, whether that buyer gets a unit, and to do so with the following guarantees: the number of confirmed orders never exceeds the initial stock; a buyer never receives more than the per-user limit regardless of how many requests they send; a retried request produces the same answer as the original; and a unit that was reserved but never paid for returns to the pool so the sale does not under-sell either.

## Objectives

The objectives were fixed at the start of the module and drove every design decision:

1. Build a backend service in Java and Spring Boot that accepts purchase claims for a scheduled, limited-stock sale and turns accepted claims into paid orders.
2. Guarantee the four invariants above under genuine concurrency, and prove it with automated concurrency tests, not by inspection.
3. Keep the relational database as the source of truth while removing it from the hot path of admission decisions.
4. Make the system observable and safe to operate: explicit failure behaviour when a dependency is unavailable, metrics for every decision the gate makes, and a reconciliation job that detects drift.
5. Measure, rather than assert, the effect of the architecture. Run the same one-million-attempt workload against a baseline implementation and the optimised implementation and report the numbers.
6. Design a deployment on AWS that a small team could actually run, with private networking, managed data services and a repeatable release process.

## Scope and boundaries

The platform deliberately models one sale at a time with one SKU and a per-user limit of one unit, because that is the case in which contention is highest and the design is easiest to reason about and verify. Multi-SKU catalogues, carts, shipping and tax are out of scope; they are ordinary CRUD concerns that add pages of code without adding to the problem being studied. Payment is modelled through a `PaymentGateway` port with a simulated adapter that has configurable latency and failure rate. This mirrors how flash sales are run in practice, where buyers pre-register a payment method so that the purchase is a single action, and it keeps the benchmark deterministic. Buyer identity is carried in a signed JWT so that the per-user limit cannot be bypassed by editing a request body.

## Solution overview

Figure 1.01 shows how the project was developed. The order matters: the baseline implementation was built and benchmarked before the optimised path existed, so that the comparison in the Feature Development chapter reflects two complete, working systems under the same load rather than a strawman.

![Project development process](diagrams/fig_dev_process.png)

Figure 1.02 shows the resulting architecture. A buyer's request passes through a JWT filter and a Redis-backed rate limiter, then reaches the claim controller. The claim service invokes a Lua script on Redis that atomically checks stock, checks that this user has not already bought, checks the idempotency key, and, if all pass, decrements the stock and records a hold with a time-to-live. Only accepted claims touch PostgreSQL, where a pending reservation and an outbox event are written in one short transaction. A scheduled relay publishes outbox events to a Kafka topic; an order worker consumes them, charges the payment method, creates the order and confirms the reservation. A reconciler runs on a schedule to expire holds that were never paid and to verify that confirmed orders, active holds and remaining stock still sum to the initial stock.

![High-level architecture of the flash sale platform](diagrams/fig_architecture.png)

The design separates two very different workloads. Admission is a read-mostly, extremely bursty, latency-sensitive decision that must be made hundreds of thousands of times; it is served from memory by a single-threaded data structure server that makes the atomicity argument trivial. Fulfilment is a write-heavy, side-effecting process that happens at most once per unit of stock; it is served by a durable log and a database, at whatever rate the database can sustain, without any buyer waiting on it.

## Relevance

The pattern generalises well beyond retail. Airline seat selection, exam slot booking, vaccine appointment systems, concert ticketing, limited NFT mints and cloud spot-capacity allocation all have the shape "many claimants, few units, correctness matters more than throughput". Several of these systems have failed publicly under launch-day load. The value of this project is that it shows, with measurements, how a small team using widely available open-source components can build a service that degrades gracefully, by rejecting fast, rather than catastrophically, by locking up.

## Deliverables

The submission consists of three artefacts. The public GitHub repository contains the Spring Boot application, database migrations, a Docker Compose file that starts PostgreSQL, Redis and Kafka locally, the k6 load-test scripts, recorded benchmark results, and the sources for every diagram in this report. The ZIP archive is a snapshot of that repository. This report documents the requirements, low-level design, schema, the claim feature end to end, the benchmark, the AWS deployment design and the technologies used.
