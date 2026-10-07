package com.keshari.flashsale.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedisSaleKeysTest {

	@Test
	void everyKeySharesTheSaleHashTag() {
		UUID sale = UUID.fromString("11111111-1111-1111-1111-111111111111");
		String tag = "{sale:" + sale + "}";
		assertThat(RedisSaleKeys.stock(sale)).startsWith(tag).endsWith(":stock");
		assertThat(RedisSaleKeys.buyers(sale)).startsWith(tag);
		assertThat(RedisSaleKeys.hold(sale, "u1")).isEqualTo(tag + ":hold:u1");
		assertThat(RedisSaleKeys.idempotency(sale, "u1", "k")).isEqualTo(tag + ":idem:u1:k");
		assertThat(RedisSaleKeys.released(sale, sale)).isEqualTo(tag + ":released:" + sale);
	}
}
