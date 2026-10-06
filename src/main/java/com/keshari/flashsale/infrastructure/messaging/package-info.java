/**
 * Kafka integration: outbox relay that publishes accepted reservations to the
 * {@code sale.reservations} topic (keyed by reservation id), and the order worker that
 * consumes them with manual acks and an idempotent insert
 * ({@code ON CONFLICT DO NOTHING}) so at-least-once delivery never duplicates
 * an order.
 */
package com.keshari.flashsale.infrastructure.messaging;
