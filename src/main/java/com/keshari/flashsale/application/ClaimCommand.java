package com.keshari.flashsale.application;

import java.util.UUID;

/**
 * A buyer's request for one unit. {@code userId} comes from the authenticated
 * principal, never from the request body; {@code idempotencyKey} is generated
 * once per purchase intent by the client and re-sent unchanged on retries.
 */
public record ClaimCommand(UUID saleId, String userId, String idempotencyKey, int quantity) {
}
