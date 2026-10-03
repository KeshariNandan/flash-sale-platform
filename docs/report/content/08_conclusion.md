# Conclusion

## Key takeaways

The central lesson is that a flash sale is a reservation problem, and treating it as one changes the architecture. Once admission is separated from fulfilment, each half can use the storage suited to it: an in-memory, single-threaded store for the burst of decisions and a relational database for the small number of durable facts. Every design element in this report follows from that split.

Several techniques proved their worth and transfer to other systems. Atomic scripting in Redis replaces distributed locks for short, well-defined critical sections and is far easier to reason about. The transactional outbox is the correct way to publish an event about a database change; it costs one extra insert and removes a whole class of lost-message and phantom-message bugs. Idempotency keys must be designed into an API from the start, because clients retry and networks duplicate whether or not the server is ready. Partial unique indexes let the database enforce rules such as "one active reservation per buyer per sale" that are awkward to express otherwise, and they hold even when the application layer is wrong. A reconciliation job that recomputes an invariant from first principles and alarms when it fails is cheap insurance in any system with state in more than one store.

On process, building and benchmarking the straightforward design before the optimised one was the most valuable decision. It produced a fair comparison, tests that later caught real bugs, and a concrete answer to the question a stakeholder actually asks, which is not "is Redis fast" but "how much better is this than what we would have built anyway".

## Practical applications

The pattern applies wherever many claimants compete for few units and correctness matters more than average throughput: ticketing at a popular on-sale, airline and railway seat selection, public-sector appointment and examination booking, allocation of scarce cloud capacity such as GPU instances, and limited-edition retail from sneakers to consoles. Several such systems have failed publicly when built as ordinary CRUD applications. The same building blocks, an atomic in-memory gate with holds, a durable outbox, an idempotent asynchronous worker and a reconciler, give a system that rejects quickly and fairly rather than locking up.

The technologies are equally transferable. Spring Boot is the default for Java services; PostgreSQL and Redis are the most common pairing of durable store and cache; Kafka is the standard event backbone; and the AWS services in the deployment design are how most teams run those components. A developer who understands the trade-offs here can apply them directly to an order, payment or inventory service in a conventional e-commerce platform.

## Limitations

Table 8.01 lists the limitations of the delivered system and of the technologies as used, with the improvement that would address each.

Table: Limitations and suggested improvements
| Area | Limitation | Suggested improvement |
|---|---|---|
| Benchmark | Load generator and system under test shared one laptop, so absolute throughput is bounded by that machine. | Run the generator on a separate host or against the deployed environment; repeat with several instance counts for a scaling curve. |
| Hot key | All attempts for one sale hit one Redis key; in a large cluster this concentrates load on one core. | Stripe stock across N keys with different hash tags, select a stripe per request, fall back to a neighbour when empty. The gate interface already permits this. |
| Waiting room | Under extreme load the rate limiter rejects with 429; buyers are not queued or told their position. | Add a virtual waiting room issuing signed queue tokens at a controlled rate ahead of the gate. |
| Per-user limit | Implemented for a limit of one using a set. | Replace set membership with a per-user count in the same Lua script. |
| Payment | Simulated gateway; no real provider or interactive 3-D Secure flow. | Integrate a sandbox gateway; model an interactive step inside the hold window. |
| Multi-SKU | One SKU per sale. | Key stock, buyers and holds by sale and SKU; add a sale_item table. |
| Kafka ordering | Keyed by reservation id, so no ordering across a buyer's events. | Key by buyer id if per-buyer ordering becomes a requirement. |
| Cost | Multi-AZ database, Kafka cluster and NAT Gateway run continuously although sales are episodic. | Scale tiers to a minimum between sales; evaluate MSK Serverless and Aurora Serverless v2; use VPC endpoints to cut NAT charges. |
| Security | HS256 shared-secret JWTs in development; no revocation. | Use an identity provider issuing RS256 tokens with JWKS rotation; add per-endpoint scopes. |
| Infrastructure as code | The AWS design is documented but not codified; dashboards and alarms are described, not committed. | Express VPC, security groups, Beanstalk environments, data services, dashboards and alarms in Terraform or CloudFormation. |

## Cost implications

The deployment costs on the order of a few hundred US dollars per month for a small always-on footprint, dominated by the highly available database, the Kafka cluster and the NAT Gateway. That is small against the revenue of even a modest sale, but much of it is spent on components idle between events. The effective levers are scheduling, scaling the compute tiers down to one instance each outside sale windows, and serverless variants of the data services where their pricing fits episodic use. In exchange the design buys automatic failover for every stateful component and a release process that rolls back on its own, which for a system that exists to survive its busiest minute is the right trade.

## Closing remarks

The project set out to build a service that stays correct and responsive when many people want the same few things at the same moment, and to measure rather than assert that it does. The delivered system enforces its invariants at three layers, in Redis for speed, in PostgreSQL for durability and in a reconciler for verification, and the benchmark shows the difference between a design that queues on a database row and one that decides in memory. The hard part of such systems is not any single technology but the discipline of deciding where each fact lives, who may change it, and how the system finds out when something has gone wrong.
