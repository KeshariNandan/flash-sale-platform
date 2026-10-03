# Technologies Used

This chapter covers each technology that carries a significant part of the design: what it is, how the project uses it, and where it is used in industry. Table 7.01 gives the overview.

Table: Technology summary
| Technology | Version | Role in this project | Alternatives considered |
|---|---|---|---|
| Java | 25 (LTS) | Application language and runtime | Kotlin, Go |
| Spring Boot | 4.0.2 | Web, data, messaging, security, actuator | Quarkus, Micronaut |
| PostgreSQL | 16 | Durable record of sales, reservations, orders, outbox | MySQL, Oracle |
| Redis | 7 | Atomic admission gate, holds, idempotency, rate limiting | Hazelcast, database row locks |
| Apache Kafka | 3.9 (KRaft) | Durable event log between acceptance and fulfilment | Apache Pulsar, RabbitMQ, SQS |
| Flyway | bundled with Boot 4 | Versioned schema migrations | Liquibase |
| Docker, Compose, Testcontainers | 29 / bundled | Local infrastructure and real dependencies in tests | Native installs, mocks |
| k6 | latest | Load generation for the one-million-attempt benchmark | JMeter, Gatling |
| AWS | - | Production target: Beanstalk, RDS, ElastiCache, MSK, VPC | Google Cloud, Azure |

## Java 25 and Spring Boot 4

Java is the dominant language for transactional backends because of its mature runtime, low-pause garbage collectors and first-class clients for every database, broker and cloud service. Java 25 is the current long-term-support release. The project uses records for immutable commands, events and DTOs, sealed interfaces for `ClaimResult` so every outcome must be handled, and virtual threads so that blocking Redis and JDBC calls on the claim path do not each pin an operating-system thread.

Spring Boot is the opinionated layer over the Spring Framework that makes a production-ready service the default. Version 4 is built on Spring Framework 7 and Jakarta EE 11. Its starters cover every integration the design needs: Spring MVC for the REST API, Spring Data JPA for repositories, Spring Data Redis for the Lua gate, Spring for Apache Kafka for the relay and worker, Spring Security for the JWT resource server, Flyway for migrations, Actuator and Micrometer for health and metrics, and Docker Compose support that starts the local stack with the application. Figure 7.01 shows how the project's packages sit on the framework.

![Layered structure of the application on the Spring Boot runtime](diagrams/fig_spring_layers.png)

Two features do heavy lifting. Declarative transactions make the "reservation plus outbox in one commit" rule a single annotation, and Spring Kafka's `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` gives the worker bounded retries with back-off and a dead-letter topic without hand-written loops.

Spring Boot is the most widely deployed Java service framework. Netflix built its microservice platform on Spring and contributed much of Spring Cloud; large banks, airlines and retailers run order, payment and account services on it. Its practical value is that a team standardises on one way to expose health, read configuration, secure endpoints and emit metrics across many services.

## PostgreSQL 16

PostgreSQL is an open-source relational database known for standards compliance and correctness under concurrency. Multi-version concurrency control lets readers never block writers, and its constraint system, including partial and expression indexes, lets business rules be enforced where they cannot be bypassed. Here it is the durable record of truth: every accepted claim, state transition and order is a committed row, and the partial unique indexes of the schema chapter are the last defence against duplicate reservations and orders. The transactional outbox is a PostgreSQL table, and `SELECT ... FOR UPDATE SKIP LOCKED` lets several relay instances drain it concurrently without coordination. The baseline benchmark relies on PostgreSQL's row locking to show why a database-only design serialises under a burst.

PostgreSQL runs at very large scale at Instagram, which has described sharding it for users and media metadata, and it is the engine behind Amazon RDS and Aurora PostgreSQL, Google Cloud SQL, Supabase and Neon. The same engine, with the same constraints, runs in a laptop container, in continuous integration and in production.

## Redis 7

Redis is an in-memory data structure server that executes commands on a single thread per shard and can run Lua scripts atomically. Those two properties make it the admission gate: a script that reads stock, checks the buyer set, decrements and writes a hold runs to completion before any other command, so the gate is correct without any lock held by the application, and it answers in well under a millisecond, which lets the overwhelming majority of attempts, the rejections, be decided without touching the database.

The project uses Redis for the stock counter and buyer set per sale, the per-user hold with a time-to-live, the idempotency record that gives a retried request its original response, and the rate limiter's token buckets. Keys for a sale share a hash tag so the script's keys are co-located, keeping the design compatible with Redis Cluster. Append-only-file persistence prevents a restart from silently losing the counter, and the reconciler cross-checks Redis against PostgreSQL so drift is detected rather than trusted.

Redis serves as cache and coordination store at Twitter, GitHub and Stack Overflow and in essentially every large e-commerce platform's inventory hot path. Alibaba Cloud documents its Redis-compatible service in exactly this role for flash sales, with atomic Lua scripts for inventory deduction; that documentation was one of the sources consulted.

## Apache Kafka

Kafka is a distributed, partitioned, replicated commit log. Producers append records to topics; records are stored durably in order within a partition; consumers in a group each own a subset of partitions and track their own offsets. Durability, per-partition ordering and independent consumer progress are exactly what the design needs between acceptance and fulfilment: an accepted reservation must never be lost, the worker must be able to fall behind during the burst and catch up afterwards, and adding workers must increase throughput without duplicating work.

Figure 7.02 shows the topology. The relay produces `ReservationAccepted` records keyed by reservation id to `sale.reservations` with six partitions. Keying by reservation id spreads records across partitions; keying by sale id, which would send a whole sale to one partition and one thread, was rejected because ordering across reservations is not required. The worker runs as one consumer group with three threads locally, acknowledges offsets manually after the database commit, and after bounded retries forwards failing records to `sale.reservations.DLT`. Kafka 3.9 runs in KRaft mode, without ZooKeeper, so one container suffices locally and Amazon MSK provides it in production. The producer uses `acks=all` with idempotence enabled, and the consumer is idempotent anyway because the outbox relay is at-least-once.

![Kafka topic, partitions, consumer group and dead-letter topic](diagrams/fig_kafka_topology.png)

Kafka was created at LinkedIn for activity streams and is now the backbone of event-driven architectures at Uber, Netflix and most large banks and retailers. Its value here is decoupling: the API tier accepts a burst at memory speed while the fulfilment tier processes it at database speed, and neither needs to know the other's capacity.

### Why Kafka rather than Apache Pulsar

Pulsar was a serious alternative and was already installed on the development machine. It separates serving from storage, supports multi-tenancy and geo-replication natively, and has built-in delayed delivery that could have driven hold expiry. Kafka was chosen for three reasons. The pipeline is one ordered event type with one consumer group, so Pulsar's tenancy and tiering features would go unused. Hold expiry still needs a reconciler comparing Redis with PostgreSQL, because the stores can drift, so delayed delivery would not remove that component. And the deployment target has a first-party managed Kafka in Amazon MSK while Spring Boot 4 ships a Kafka starter, so the operational path is shorter. The decision is recorded in the repository so it can be revisited if the product grows into multi-tenant or multi-region use.

## Flyway

Flyway applies versioned SQL migrations in order and records them in a history table. The schema is created by `V1__initial_schema.sql` and evolved by numbered successors; the application refuses to start against an unexpected schema version. This puts the schema under code review, gives every environment an identical schema, and makes rollback a matter of deploying the previous version with a compensating migration. Flyway and Liquibase are the standard tools for this wherever teams practise continuous delivery against relational databases.

## Docker, Docker Compose and Testcontainers

Docker packages a service and its dependencies as an image that runs identically on any host. Compose declares the local PostgreSQL, Redis and Kafka containers with ports, volumes and health checks and starts them with one command; Spring Boot's Compose support does so automatically. Testcontainers starts the same images from inside JUnit, so integration tests run against real engines rather than mocks, with container lifecycle tied to the test lifecycle. That is what makes the concurrency tests trustworthy: the behaviour under test is that of the real database's locks and the real Redis script engine. Containers are the deployment unit for Kubernetes, Amazon ECS and, indirectly, Elastic Beanstalk, and Testcontainers is used in the test suites of Spring itself and of several database vendors.

## k6

k6 is an open-source load-testing tool whose scenarios are written in JavaScript and executed by a Go runtime. Its `shared-iterations` executor expresses the benchmark's intent directly, exactly one million iterations across a bounded pool of virtual users, and it reports latency percentiles, request rates and response-code counts in a form that is committed alongside the code. Load tests as versioned code, run in the same pipeline as functional tests, are standard release practice at companies that run sales and launches.

## Amazon Web Services

AWS supplies the production platform of the Deployment Flow chapter: Elastic Beanstalk for instances, scaling and deployments; RDS for PostgreSQL with failover and backups; ElastiCache for Redis with replication; MSK for Kafka; VPC, subnets and security groups for the private network; Secrets Manager for credentials; CloudWatch for logs and metrics. Managed services hand patching, backups, failover and capacity to the provider so a small team can concentrate on the application. AWS is used from start-ups to Netflix and Airbnb, and its managed database and cache services are the most common way PostgreSQL and Redis run in production today.

## Supporting tools

Maven, through the checked-in wrapper, builds without a local installation; JUnit 5 is the test framework; Micrometer provides vendor-neutral metrics that Actuator exposes; Mermaid, rendered locally with mermaid-cli, produced every diagram in this report from text sources committed with the code; Git and GitHub hold the code, with the commit history as the record of how the system was built.
