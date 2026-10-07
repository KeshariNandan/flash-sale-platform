package com.keshari.flashsale.infrastructure.cache;

import com.keshari.flashsale.application.StockGate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Optimised admission adapter. {@code tryReserve} and {@code release} are each
 * one EVAL of a script loaded from the classpath; Redis executes that script
 * atomically. Selected by {@code flashsale.gate=redis}.
 */
@Component
@ConditionalOnProperty(name = "flashsale.gate", havingValue = "redis")
public class RedisStockGate implements StockGate {

	private static final int ACCEPTED = 1;
	private static final int REPLAY = 0;
	private static final int SOLD_OUT = -1;
	private static final int DUPLICATE = -2;

	private final StringRedisTemplate redis;
	private final DefaultRedisScript<List> reserve;
	private final DefaultRedisScript<Long> release;

	public RedisStockGate(StringRedisTemplate redis) {
		this.redis = redis;
		this.reserve = script("redis/reserve.lua", List.class);
		this.release = script("redis/release.lua", Long.class);
	}

	private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(path));
		script.setResultType(resultType);
		return script;
	}

	@Override
	public void open(UUID saleId, int initialStock) {
		// SETNX: a retried open after a crash must not reset a live counter.
		redis.opsForValue().setIfAbsent(RedisSaleKeys.stock(saleId), Integer.toString(initialStock));
	}

	@Override
	public GateDecision tryReserve(UUID saleId, String userId, String idempotencyKey, UUID reservationId,
			Duration holdTtl) {
		long ttlSeconds = Math.max(1, holdTtl.toSeconds());
		List<?> raw = redis.execute(reserve, reserveKeys(saleId, userId, idempotencyKey), userId,
				reservationId.toString(), Long.toString(ttlSeconds));
		if (raw == null || raw.isEmpty()) {
			throw new IllegalStateException("reserve script returned no result");
		}
		int code = asInt(raw.get(0));
		return switch (code) {
			case ACCEPTED -> new GateDecision.Accepted(reservationId);
			case REPLAY -> new GateDecision.Replay(UUID.fromString(String.valueOf(raw.get(1))));
			case SOLD_OUT -> new GateDecision.SoldOut();
			case DUPLICATE -> new GateDecision.Duplicate();
			default -> throw new IllegalStateException("unknown reserve code " + code);
		};
	}

	@Override
	public void release(UUID saleId, String userId, UUID reservationId) {
		redis.execute(release, releaseKeys(saleId, userId, reservationId), userId, reservationId.toString());
	}

	@Override
	public long remaining(UUID saleId) {
		String value = redis.opsForValue().get(RedisSaleKeys.stock(saleId));
		return value == null ? 0L : Long.parseLong(value);
	}

	@Override
	public void close(UUID saleId) {
		redis.delete(List.of(RedisSaleKeys.stock(saleId), RedisSaleKeys.buyers(saleId)));
	}

	private static List<String> reserveKeys(UUID saleId, String userId, String key) {
		return List.of(RedisSaleKeys.stock(saleId), RedisSaleKeys.buyers(saleId), RedisSaleKeys.hold(saleId, userId),
				RedisSaleKeys.idempotency(saleId, userId, key));
	}

	/** Idempotency key is unknown on release; the released marker plus hold id are the authority. */
	private static List<String> releaseKeys(UUID saleId, String userId, UUID reservationId) {
		return List.of(RedisSaleKeys.stock(saleId), RedisSaleKeys.buyers(saleId), RedisSaleKeys.hold(saleId, userId),
				"", RedisSaleKeys.released(saleId, reservationId));
	}

	private static int asInt(Object value) {
		if (value instanceof Number n) {
			return n.intValue();
		}
		return Integer.parseInt(String.valueOf(value));
	}
}
