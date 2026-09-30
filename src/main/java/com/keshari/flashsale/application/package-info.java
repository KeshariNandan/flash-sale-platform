/**
 * Application services (use cases) that orchestrate the domain:
 * create a sale, claim a unit, confirm or release a reservation, reconcile
 * Redis against the Postgres ledger.
 * <p>
 * Services are transactional boundaries. They talk to infrastructure only
 * through interfaces (stock gate, event publisher, repositories) so the
 * baseline SQL path and the Redis + Kafka path can be swapped for benchmarking.
 */
package com.keshari.flashsale.application;
