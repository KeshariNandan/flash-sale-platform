package com.keshari.flashsale.infrastructure.cache;

/**
 * Hash-tagged rate-limit keys so user and global counters share one cluster slot.
 */
public final class RateLimitKeys {

	private RateLimitKeys() {
	}

	public static String user(String userId) {
		return "{rl}:user:" + userId;
	}

	public static String global() {
		return "{rl}:global";
	}
}
