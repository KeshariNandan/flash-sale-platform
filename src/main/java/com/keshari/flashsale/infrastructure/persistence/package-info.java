/**
 * PostgreSQL persistence: Spring Data JPA repositories and the transactional
 * outbox table. Postgres is the durable source of truth for orders; Redis only
 * accelerates admission.
 */
package com.keshari.flashsale.infrastructure.persistence;
