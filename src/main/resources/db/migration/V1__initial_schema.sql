-- Flash sale platform: initial schema.
--
-- PostgreSQL is the durable record of truth. Constraints and partial unique
-- indexes below carry business rules so that a bug in the application layer or
-- a duplicate Kafka delivery cannot violate them:
--   * one active (PENDING/CONFIRMED) reservation per buyer per sale
--   * one order per reservation
--   * stock never negative

CREATE TABLE app_user (
    user_id              varchar(64)  PRIMARY KEY,
    email                varchar(320) NOT NULL UNIQUE,
    saved_payment_token  varchar(128) NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE flash_sale (
    id                uuid          PRIMARY KEY,
    sku               varchar(64)   NOT NULL,
    name              varchar(200)  NOT NULL,
    unit_price        numeric(12,2) NOT NULL CHECK (unit_price > 0),
    start_at          timestamptz   NOT NULL,
    end_at            timestamptz   NOT NULL,
    initial_stock     integer       NOT NULL CHECK (initial_stock > 0),
    per_user_limit    integer       NOT NULL DEFAULT 1 CHECK (per_user_limit > 0),
    hold_ttl_seconds  integer       NOT NULL DEFAULT 120 CHECK (hold_ttl_seconds > 0),
    status            varchar(16)   NOT NULL
                      CHECK (status IN ('SCHEDULED', 'OPEN', 'SOLD_OUT', 'CLOSED')),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_flash_sale_window CHECK (end_at > start_at)
);

-- Separate 1:1 row so the baseline strategy's hot UPDATE touches the narrowest
-- possible row and optimistic locking applies to stock alone.
CREATE TABLE inventory (
    sale_id          uuid        PRIMARY KEY REFERENCES flash_sale(id) ON DELETE CASCADE,
    stock_remaining  integer     NOT NULL CHECK (stock_remaining >= 0),
    version          bigint      NOT NULL DEFAULT 0,
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reservation (
    id               uuid         PRIMARY KEY,
    sale_id          uuid         NOT NULL REFERENCES flash_sale(id) ON DELETE RESTRICT,
    user_id          varchar(64)  NOT NULL REFERENCES app_user(user_id) ON DELETE RESTRICT,
    idempotency_key  varchar(128) NOT NULL,
    status           varchar(16)  NOT NULL
                     CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    expires_at       timestamptz  NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now()
);

-- Per-user limit (of one) enforced by the database: a buyer may have at most
-- one PENDING or CONFIRMED reservation per sale. Partial, so a buyer whose
-- earlier hold expired or was released may try again.
CREATE UNIQUE INDEX ux_reservation_sale_user
    ON reservation (sale_id, user_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

-- Idempotency backstop if the Redis idempotency record has expired.
CREATE UNIQUE INDEX ux_reservation_user_key
    ON reservation (user_id, idempotency_key);

-- Reconciler looks only at pending holds; keep that index tiny.
CREATE INDEX ix_reservation_expiry
    ON reservation (expires_at)
    WHERE status = 'PENDING';

CREATE TABLE customer_order (
    id              uuid          PRIMARY KEY,
    reservation_id  uuid          NOT NULL REFERENCES reservation(id) ON DELETE RESTRICT,
    sale_id         uuid          NOT NULL REFERENCES flash_sale(id) ON DELETE RESTRICT,
    user_id         varchar(64)   NOT NULL REFERENCES app_user(user_id) ON DELETE RESTRICT,
    amount          numeric(12,2) NOT NULL,
    payment_ref     varchar(128)  NOT NULL,
    status          varchar(16)   NOT NULL CHECK (status IN ('PAID', 'FAILED')),
    created_at      timestamptz   NOT NULL DEFAULT now()
);

-- One order per reservation; target of INSERT ... ON CONFLICT DO NOTHING.
CREATE UNIQUE INDEX ux_order_reservation ON customer_order (reservation_id);
CREATE INDEX ix_order_sale ON customer_order (sale_id);

-- Transactional outbox. No FK to reservation on purpose: the outbox is an
-- append-only log whose lifecycle (archival) is independent of the rows it
-- describes.
CREATE TABLE outbox_event (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type  varchar(64)  NOT NULL,
    aggregate_id    uuid         NOT NULL,
    event_type      varchar(64)  NOT NULL,
    payload         jsonb        NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    published_at    timestamptz  NULL
);

-- Relay reads unpublished events in insertion order.
CREATE INDEX ix_outbox_unpublished
    ON outbox_event (id)
    WHERE published_at IS NULL;
