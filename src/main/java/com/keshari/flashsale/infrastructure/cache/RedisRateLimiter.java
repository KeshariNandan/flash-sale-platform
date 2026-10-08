package com.keshari.flashsale.infrastructure.cache;

import com.keshari.flashsale.config.FlashSaleProperties;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Per-user and global token buckets in Redis. One EVAL, one-second window.
 */
@Component
public class RedisRateLimiter {

	public enum Decision {
		ALLOWED, USER, GLOBAL
	}

	private final StringRedisTemplate redis;
	private final FlashSaleProperties properties;
	private final DefaultRedisScript<Long> script;

	public RedisRateLimiter(StringRedisTemplate redis, FlashSaleProperties properties) {
		this.redis = redis;
		this.properties = properties;
		this.script = new DefaultRedisScript<>();
		this.script.setLocation(new ClassPathResource("redis/ratelimit.lua"));
		this.script.setResultType(Long.class);
	}

	public Decision allow(String userId) {
		FlashSaleProperties.RateLimit limit = properties.rateLimit();
		Long code = redis.execute(script, List.of(RateLimitKeys.user(userId), RateLimitKeys.global()),
				Integer.toString(limit.userPerSecond()), Integer.toString(limit.globalPerSecond()), "1");
		if (code == null) {
			return Decision.USER;
		}
		return switch (code.intValue()) {
			case 1 -> Decision.USER;
			case 2 -> Decision.GLOBAL;
			default -> Decision.ALLOWED;
		};
	}
}
