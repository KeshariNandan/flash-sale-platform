/**
 * Redis stock gate. A single atomic Lua script checks remaining stock, the
 * per-user limit and the idempotency key, then decrements stock and records a
 * short-lived hold. Redis executes the script on one thread, so two concurrent
 * claims cannot interleave and oversell.
 */
package com.keshari.flashsale.infrastructure.cache;
