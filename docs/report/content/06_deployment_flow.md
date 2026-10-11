# Deployment Flow

## Goals

The deployment is designed for a small team: managed services wherever one exists, private networking for every data store, horizontal scaling of the stateless application tier, and releases that roll back without manual intervention. Figure 6.01 shows the design on Amazon Web Services. During the project the system ran locally with Docker Compose; each AWS component below maps one-to-one onto a service in the local stack.

![AWS deployment architecture](diagrams/fig_aws_deployment.png)

## Network: VPC and subnets

One VPC, 10.0.0.0/16, in ap-south-1 (Mumbai) hosts everything, divided into three subnet tiers across two availability zones so that losing a zone leaves a working copy of every tier.

Table: Subnet layout
| Tier | Subnets | Contents | Internet path |
|---|---|---|---|
| Public | 10.0.1.0/24, 10.0.2.0/24 | Application Load Balancer, NAT Gateway | Internet Gateway |
| Private application | 10.0.11.0/24, 10.0.12.0/24 | Elastic Beanstalk EC2 instances for API and worker | Outbound only via NAT |
| Private data | 10.0.21.0/24, 10.0.22.0/24 | RDS PostgreSQL, ElastiCache Redis, MSK brokers | None |

Only the load balancer has a public address. Application instances reach the internet, for updates and the payment gateway, through the NAT Gateway; nothing on the internet can initiate a connection to them. Data-tier subnets have no internet route at all. Interface endpoints for Secrets Manager, CloudWatch Logs and S3 keep that traffic inside AWS.

## Security groups

Security groups are stateful firewalls on network interfaces. They are chained so each tier admits traffic only from the security group of the tier in front of it, never from a CIDR range: a new application instance is authorised the moment it launches into `app-sg`, and nothing else in the VPC can reach the data tier even from the same subnet.

Table: Security group rules
| Security group | Attached to | Inbound | Source | Outbound |
|---|---|---|---|---|
| alb-sg | Application Load Balancer | TCP 443 | 0.0.0.0/0 | 8085 to app-sg |
| app-sg | API and worker instances | TCP 8085 | alb-sg | 5432 to rds-sg, 6379 to cache-sg, 9092 to msk-sg, 443 via NAT |
| rds-sg | RDS PostgreSQL | TCP 5432 | app-sg | none |
| cache-sg | ElastiCache Redis | TCP 6379 | app-sg | none |
| msk-sg | MSK brokers | TCP 9092, 9094 | app-sg, msk-sg | msk-sg (inter-broker) |

There is no SSH rule; instance access goes through Systems Manager Session Manager, which needs no open port and records every session. Port 80 is not opened; clients connect over TLS with a certificate from AWS Certificate Manager.

## Compute: Elastic Beanstalk on EC2

The application is packaged as one executable JAR and deployed to two Elastic Beanstalk environments on the Corretto 25 platform.

The **API environment** is a web-server environment behind the load balancer. Its Auto Scaling group runs at least two instances across the two zones and scales on request count per target, so capacity is added as attempts rise rather than after CPU saturates. Instances are `t3.medium` class; the service is I/O-bound on Redis. The health check targets `/actuator/health`, which reports PostgreSQL, Redis and Kafka connectivity, so an instance that has lost a dependency leaves rotation.

The **worker environment** runs the same JAR with a profile that disables the claim endpoints and enables the Kafka listener, the outbox relay and the reconciler (`flashsale.reconciler.enabled=true` on workers, `false` on the API tier). It is a worker-tier environment without a load balancer, scaling on the consumer-group lag metric the application publishes to CloudWatch. Keeping scheduled jobs on the worker tier leaves request-serving instances undisturbed. The relay claims outbox rows with `FOR UPDATE SKIP LOCKED`. The reconciler batches expired PENDING rows and reloads each reservation in a short transaction, so a second worker seeing the same row no-ops after the first has marked it EXPIRED.

Beanstalk manages the instances, launch template, Auto Scaling group, log shipping and platform patching. Configuration lives in `.ebextensions` and environment properties committed with the code, so an environment can be recreated from the repository.

## Data tier

**Amazon RDS for PostgreSQL 16** runs Multi-AZ: a synchronous standby in the second zone takes over automatically with the same endpoint. Storage is gp3 with seven-day automated backups and point-in-time recovery. Flyway runs at application start; migrations are backward compatible with the previous version so rolling deployments never run two incompatible versions.

**Amazon ElastiCache for Redis 7** runs as a replication group with one primary and one replica across the zones, cluster mode disabled, with automatic failover. Append-only-file persistence keeps the counter across restarts; the reconciler's invariant check would report any discrepancy. Because a sale's keys share a hash tag, cluster mode can be enabled later without changing the Lua script.

**Amazon MSK** provides Kafka with two brokers across the zones and replication factor two for `sale.reservations`, handling patching, storage and metrics, with IAM authentication so no Kafka passwords exist. For a small deployment, MSK Serverless or one self-managed broker on EC2 are drop-in alternatives; the application needs only a bootstrap address.

## Secrets, configuration and observability

Database credentials, the JWT signing secret and the payment gateway key live in **Secrets Manager** and are injected at start-up, never appearing in the repository, the JAR or the console. Other configuration is Beanstalk environment properties mapped onto Spring properties.

Logs ship to **CloudWatch Logs**; Micrometer publishes gate outcome counters, claim latency histograms, outbox and consumer lag, payment results and the invariant gauge to CloudWatch metrics. Alarms fire on a non-zero invariant gauge, elevated 5xx rate at the load balancer, and consumer lag above threshold for several minutes, routing to an SNS topic that pages the on-call engineer.

## Release process

Figure 6.02 shows the pipeline. Every push runs the full suite in GitHub Actions, including Testcontainers tests, since the runners have Docker. A green build on main packages the JAR, uploads it to S3 as a Beanstalk application version, and deploys to both environments with a rolling policy plus an additional batch, so full capacity is kept throughout. Beanstalk waits for the health check per batch and rolls back automatically if new instances fail to become healthy.

![Continuous integration and deployment pipeline](diagrams/fig_cicd.png)

## Component summary and indicative cost

Table: AWS components and their role
| Component | Service | Role | Local equivalent |
|---|---|---|---|
| Edge | Route 53, ACM, ALB | DNS, TLS, health-checked routing | localhost:8085 |
| Compute | Elastic Beanstalk on EC2 | Runs API and worker JARs; scales, patches, ships logs | `./mvnw spring-boot:run` |
| Relational store | RDS PostgreSQL 16 Multi-AZ | Sales, reservations, orders, outbox | postgres:16 container |
| Cache and gate | ElastiCache Redis 7 with replica | Stock counter, holds, idempotency, rate limits | redis:7 container |
| Messaging | Amazon MSK | sale.reservations and dead-letter topic | apache/kafka container |
| Secrets | Secrets Manager | Credentials and signing keys | Environment variables |
| Monitoring | CloudWatch, SNS | Logs, metrics, alarms, paging | Actuator endpoints |
| Delivery | GitHub Actions, S3 | Build, test, package, deploy | Local Maven build |

Table 6.04 gives an order-of-magnitude monthly estimate for a small production footprint at on-demand prices. Prices change and vary by configuration; the figures show where the money goes and must be re-checked against the AWS pricing pages before any decision.

Table: Indicative monthly cost, small production footprint
| Component | Configuration | Approximate USD per month |
|---|---|---|
| EC2 for API (Beanstalk) | 2 x t3.medium | about 65 |
| EC2 for worker | 1 x t3.small | about 18 |
| Application Load Balancer | 1 ALB plus LCU usage | about 25 |
| NAT Gateway | 1 gateway plus data processing | about 42 |
| RDS PostgreSQL | db.t4g.medium Multi-AZ, 50 GB gp3 | about 125 |
| ElastiCache Redis | 2 x cache.t4g.small | about 55 |
| Amazon MSK | 2 x kafka.t3.small, 100 GB | about 90 |
| CloudWatch, Secrets Manager, S3 | Typical small usage | about 20 |
| Total | | about 440 |

The largest recurring items are the Multi-AZ database, the Kafka cluster and the NAT Gateway. For a system idle between sales, the natural optimisation is to scale the API and worker tiers to a minimum between events and to consider MSK Serverless, billed on throughput; the Conclusion discusses these trade-offs.
