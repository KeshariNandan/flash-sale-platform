package com.keshari.flashsale.infrastructure.cache;

import java.util.UUID;

/**
 * Redis key layout for one sale. Every key is hash-tagged {@code {sale:<id>}}
 * so a cluster EVAL of the reserve/release scripts addresses a single slot.
 */
public final class RedisSaleKeys {

	private RedisSaleKeys() {
	}

	public static String stock(UUID saleId) {
		return tag(saleId) + ":stock";
	}

	public static String buyers(UUID saleId) {
		return tag(saleId) + ":buyers";
	}

	public static String hold(UUID saleId, String userId) {
		return tag(saleId) + ":hold:" + userId;
	}

	public static String idempotency(UUID saleId, String userId, String key) {
		return tag(saleId) + ":idem:" + userId + ":" + key;
	}

	private static String tag(UUID saleId) {
		return "{sale:" + saleId + "}";
	}
}
