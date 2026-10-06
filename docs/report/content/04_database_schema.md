# Database Schema Design

## Design goals

PostgreSQL is the durable record of truth for sales, reservations and orders. The schema was designed with four goals. It must make the correctness invariants enforceable by the database itself, through constraints, so that a bug in the application or a duplicate message cannot violate them. It must keep the rows touched on the hot path narrow, so that the baseline strategy is a fair representation of a well-tuned conventional design rather than a deliberately slow one. It must support the transactional outbox, which requires that a state change and the event describing it be written atomically. And it must be versioned and repeatable, which is achieved with Flyway migrations checked into the repository and applied at application start.

Figure 4.01 shows the entity-relationship diagram. The textual definitions follow, in the form requested by the template, and then the foreign keys, cardinalities and indexes.

![Entity-relationship diagram](diagrams/fig_er.png)

## Tables

Table: app_user
| Column | Type | Constraints | Description |
|---|---|---|---|
| user_id | varchar(64) | PRIMARY KEY | Stable buyer identifier; matches the JWT subject. |
| email | varchar(320) | NOT NULL, UNIQUE | Contact address. |
| saved_payment_token | varchar(128) | NOT NULL | Opaque token for the payment gateway; never the card number. |
| created_at | timestamptz | NOT NULL DEFAULT now() | Registration time. |

Table: flash_sale
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | uuid | PRIMARY KEY | Sale identifier used in URLs and Redis keys. |
| sku | varchar(64) | NOT NULL | Product code being sold. |
| name | varchar(200) | NOT NULL | Display name. |
| unit_price | numeric(12,2) | NOT NULL, CHECK > 0 | Price charged per unit. |
| start_at | timestamptz | NOT NULL | Sale opens. |
| end_at | timestamptz | NOT NULL, CHECK end_at > start_at | Sale closes. |
| initial_stock | integer | NOT NULL, CHECK > 0 | Units available at open. |
| per_user_limit | integer | NOT NULL DEFAULT 1, CHECK > 0 | Maximum units per buyer. |
| hold_ttl_seconds | integer | NOT NULL DEFAULT 120 | Time a hold stays valid before expiry. |
| status | varchar(16) | NOT NULL, CHECK IN (SCHEDULED, OPEN, SOLD_OUT, CLOSED) | Lifecycle state. |
| created_at | timestamptz | NOT NULL DEFAULT now() | Creation time. |

Table: inventory
| Column | Type | Constraints | Description |
|---|---|---|---|
| sale_id | uuid | PRIMARY KEY, FOREIGN KEY flash_sale(id) | One row per sale. |
| stock_remaining | integer | NOT NULL, CHECK >= 0 | Durable remaining stock. |
| version | bigint | NOT NULL DEFAULT 0 | Optimistic-locking version. |
| updated_at | timestamptz | NOT NULL DEFAULT now() | Last change. |

Table: reservation
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | uuid | PRIMARY KEY | Reservation identifier returned to the buyer. |
| sale_id | uuid | NOT NULL, FOREIGN KEY flash_sale(id) | Sale claimed. |
| user_id | varchar(64) | NOT NULL, FOREIGN KEY app_user(user_id) | Buyer. |
| idempotency_key | varchar(128) | NOT NULL | Client-supplied key; unique per buyer. |
| status | varchar(16) | NOT NULL, CHECK IN (PENDING, CONFIRMED, RELEASED, EXPIRED) | Lifecycle state. |
| expires_at | timestamptz | NOT NULL | Hold deadline. |
| created_at | timestamptz | NOT NULL DEFAULT now() | Acceptance time. |
| updated_at | timestamptz | NOT NULL DEFAULT now() | Last transition. |

Table: customer_order
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | uuid | PRIMARY KEY | Order identifier. |
| reservation_id | uuid | NOT NULL, UNIQUE, FOREIGN KEY reservation(id) | The reservation fulfilled; uniqueness prevents duplicate orders. |
| sale_id | uuid | NOT NULL, FOREIGN KEY flash_sale(id) | Denormalised for reporting queries. |
| user_id | varchar(64) | NOT NULL, FOREIGN KEY app_user(user_id) | Buyer. |
| amount | numeric(12,2) | NOT NULL | Amount charged. |
| payment_ref | varchar(128) | NOT NULL | Gateway reference. |
| status | varchar(16) | NOT NULL, CHECK IN (PAID, FAILED) | Payment outcome. |
| created_at | timestamptz | NOT NULL DEFAULT now() | Order time. |

Table: outbox_event
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | bigint | PRIMARY KEY, GENERATED ALWAYS AS IDENTITY | Monotonic publication order. |
| aggregate_type | varchar(64) | NOT NULL | Always RESERVATION in this project. |
| aggregate_id | uuid | NOT NULL | Reservation id; used as the Kafka key. |
| event_type | varchar(64) | NOT NULL | ReservationAccepted. |
| payload | jsonb | NOT NULL | Event body as published. |
| created_at | timestamptz | NOT NULL DEFAULT now() | Written with the reservation. |
| published_at | timestamptz | NULL | Set by the relay after a successful publish. |

## Foreign keys

Table: Foreign key relationships
| Referencing column | Referenced column | On delete |
|---|---|---|
| inventory(sale_id) | flash_sale(id) | CASCADE |
| reservation(sale_id) | flash_sale(id) | RESTRICT |
| reservation(user_id) | app_user(user_id) | RESTRICT |
| customer_order(reservation_id) | reservation(id) | RESTRICT |
| customer_order(sale_id) | flash_sale(id) | RESTRICT |
| customer_order(user_id) | app_user(user_id) | RESTRICT |

`outbox_event.aggregate_id` intentionally has no foreign key. The outbox is an append-only log that may outlive or be archived independently of the rows it describes, and a constraint would couple the two lifecycles.

## Cardinality of relations

Table: Cardinality between entities
| Relationship | Cardinality | Reasoning |
|---|---|---|
| flash_sale to inventory | 1 : 1 | Exactly one stock row per sale; separated to keep the hot row narrow. |
| flash_sale to reservation | 1 : m | A sale receives many claims. |
| app_user to reservation | 1 : m | A buyer may participate in many sales; within one sale the unique index below caps them at the per-user limit. |
| reservation to customer_order | 1 : 0..1 | A reservation yields at most one order, and none if released or expired. |
| flash_sale to customer_order | 1 : m | A sale produces up to initial_stock orders. |
| app_user to customer_order | 1 : m | Across sales. |
| reservation to outbox_event | 1 : 1 | One ReservationAccepted event per reservation. |

## Indexes and constraints that carry business rules

Table: Indexes
| Index | Definition | Purpose |
|---|---|---|
| ux_reservation_sale_user | UNIQUE (sale_id, user_id) WHERE status IN (PENDING, CONFIRMED) | Database-level enforcement of the per-user limit of one active or confirmed reservation. A partial unique index lets a buyer whose earlier claim expired try again. |
| ux_reservation_user_key | UNIQUE (user_id, idempotency_key) | Backstop for idempotency if the Redis record has expired. |
| ux_order_reservation | UNIQUE (reservation_id) | One order per reservation; the target of ON CONFLICT DO NOTHING. |
| ix_reservation_expiry | (expires_at) WHERE status = PENDING | Lets the reconciler find stale holds without scanning confirmed rows. |
| ix_outbox_unpublished | (id) WHERE published_at IS NULL | Relay reads only unpublished events in insertion order. |
| ix_order_sale | (sale_id) | Statistics and invariant queries per sale. |

The partial unique indexes deserve emphasis because they encode business rules that would otherwise live only in application code. Even if the Redis gate were bypassed entirely, a second concurrent insert for the same buyer in the same sale would fail on `ux_reservation_sale_user`, and a duplicate Kafka delivery inserting a second order would be silently ignored because of `ux_order_reservation`. This is the layered defence the project relies on: Redis for speed, PostgreSQL for the last word.

## Migration

The schema is created by a single Flyway migration, `V1__initial_schema.sql`, and evolved by numbered successors. An excerpt covering the two tables most relevant to the invariants is shown below; the full file is in the repository under `src/main/resources/db/migration`.

```
CREATE TABLE reservation (
    id               uuid PRIMARY KEY,
    sale_id          uuid NOT NULL REFERENCES flash_sale(id),
    user_id          varchar(64) NOT NULL REFERENCES app_user(user_id),
    idempotency_key  varchar(128) NOT NULL,
    status           varchar(16) NOT NULL
                     CHECK (status IN ('PENDING','CONFIRMED','RELEASED','EXPIRED')),
    expires_at       timestamptz NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_reservation_sale_user
    ON reservation (sale_id, user_id)
    WHERE status IN ('PENDING','CONFIRMED');

CREATE UNIQUE INDEX ux_reservation_user_key
    ON reservation (user_id, idempotency_key);

CREATE TABLE customer_order (
    id              uuid PRIMARY KEY,
    reservation_id  uuid NOT NULL UNIQUE REFERENCES reservation(id),
    sale_id         uuid NOT NULL REFERENCES flash_sale(id),
    user_id         varchar(64) NOT NULL REFERENCES app_user(user_id),
    amount          numeric(12,2) NOT NULL,
    payment_ref     varchar(128) NOT NULL,
    status          varchar(16) NOT NULL CHECK (status IN ('PAID','FAILED')),
    created_at      timestamptz NOT NULL DEFAULT now()
);
```

## Redis data model

Although Redis is not a relational store, its keys are part of the schema because the invariants span both systems. All keys for a sale share a hash tag `{sale:<id>}` so that they land on the same slot in a clustered deployment and the Lua script can touch them atomically.

Table: Redis keys per sale
| Key | Type | Content and lifetime |
|---|---|---|
| {sale:<id>}:stock | string (integer) | Remaining units. Set on open, decremented by the reserve script, incremented by the release script. Deleted on close. |
| {sale:<id>}:buyers | set | user ids that hold or bought a unit. Membership check enforces the per-user limit. Expires with the sale. |
| {sale:<id>}:hold:<userId> | string | Reservation id, with TTL equal to the sale's hold TTL. Its expiry is the trigger the reconciler confirms against the database. |
| {sale:<id>}:idem:<userId>:<key> | string | Original reservation id for an idempotency key, TTL 24 hours. Hash-tagged with the sale so EVAL stays in one cluster slot. |
| rl:user:<userId> and rl:global | string (integer) | Token-bucket counters for rate limiting, TTL one second. |

The `buyers` set rather than a per-user counter is used because the per-user limit in this project is one; the design note in the Conclusion describes the small change needed for higher limits.

## Messaging keys

The outbox row and the Kafka record share one identifier so a retry can be matched to the reservation it describes.

Table: Publication keys
| Where | Key | Why this key |
|---|---|---|
| outbox_event.aggregate_id | reservation id | One ReservationAccepted per reservation; the relay publishes in id order. |
| Kafka record key on `sale.reservations` | reservation id | Spreads a sale across six partitions. Keying by sale id would pin every claim to one partition and one worker thread. |
| Kafka record value | jsonb payload of ReservationAccepted | Same bytes the outbox stored; the worker does not re-derive the event. |
| customer_order.reservation_id | reservation id (UNIQUE) | Target of `INSERT … ON CONFLICT DO NOTHING` so a second delivery cannot create a second order. |

Cardinality used when reading the ER diagram: sale 1:1 inventory, sale 1:m reservation, reservation 1:0..1 order, reservation 1:1 outbox event. Those ratios are enforced by the primary keys and unique indexes above, not only by application code.
