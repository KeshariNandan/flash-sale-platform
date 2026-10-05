package com.keshari.flashsale.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import com.keshari.flashsale.application.StockGate;
import com.keshari.flashsale.application.StockGate.GateDecision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Double-click, sold-out and release rules of the Lua gate on a real Redis 7. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "flashsale.gate=redis")
class RedisStockGateTest {

	@Autowired
	StockGate gate;

	static final Duration TTL = Duration.ofSeconds(60);

	@Test
	void lastUnitIsWonByExactlyOneCaller() throws Exception {
		UUID sale = UUID.randomUUID();
		gate.open(sale, 1);
		int n = 40;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		List<Future<GateDecision>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			String user = "u-" + i;
			String key = "k-" + i;
			UUID reservation = UUID.randomUUID();
			Callable<GateDecision> task = () -> {
				start.await();
				return gate.tryReserve(sale, user, key, reservation, TTL);
			};
			futures.add(pool.submit(task));
		}
		start.countDown();
		List<GateDecision> results = new ArrayList<>();
		for (Future<GateDecision> f : futures) {
			results.add(f.get());
		}
		pool.shutdown();

		assertThat(results.stream().filter(GateDecision.Accepted.class::isInstance).count()).isEqualTo(1);
		assertThat(results.stream().filter(GateDecision.SoldOut.class::isInstance).count()).isEqualTo(n - 1);
		assertThat(gate.remaining(sale)).isZero();
	}

	@Test
	void sameBuyerWithTwoKeysIsDuplicateAndDoesNotLeakStock() {
		UUID sale = UUID.randomUUID();
		gate.open(sale, 5);
		String buyer = "buyer-a";
		GateDecision first = gate.tryReserve(sale, buyer, "key-1", UUID.randomUUID(), TTL);
		GateDecision second = gate.tryReserve(sale, buyer, "key-2", UUID.randomUUID(), TTL);
		assertThat(first).isInstanceOf(GateDecision.Accepted.class);
		assertThat(second).isInstanceOf(GateDecision.Duplicate.class);
		assertThat(gate.remaining(sale)).isEqualTo(4);
	}

	@Test
	void sameIdempotencyKeyReplaysWithoutTakingAnotherUnit() {
		UUID sale = UUID.randomUUID();
		gate.open(sale, 3);
		UUID reservation = UUID.randomUUID();
		String buyer = "buyer-b";
		String key = "same-key-16chars";
		GateDecision first = gate.tryReserve(sale, buyer, key, reservation, TTL);
		GateDecision again = gate.tryReserve(sale, buyer, key, UUID.randomUUID(), TTL);
		assertThat(first).isEqualTo(new GateDecision.Accepted(reservation));
		assertThat(again).isEqualTo(new GateDecision.Replay(reservation));
		assertThat(gate.remaining(sale)).isEqualTo(2);
	}

	@Test
	void releaseReturnsTheUnitAndASecondReleaseIsANoOp() {
		UUID sale = UUID.randomUUID();
		gate.open(sale, 2);
		UUID reservation = UUID.randomUUID();
		String buyer = "buyer-c";
		gate.tryReserve(sale, buyer, "key-c", reservation, TTL);
		assertThat(gate.remaining(sale)).isEqualTo(1);
		gate.release(sale, buyer, reservation);
		assertThat(gate.remaining(sale)).isEqualTo(2);
		gate.release(sale, buyer, reservation);
		assertThat(gate.remaining(sale)).isEqualTo(2);
	}

	@Test
	void releaseOfADifferentReservationDoesNotTouchStock() {
		UUID sale = UUID.randomUUID();
		gate.open(sale, 1);
		UUID reservation = UUID.randomUUID();
		gate.tryReserve(sale, "buyer-d", "key-d", reservation, TTL);
		gate.release(sale, "buyer-d", UUID.randomUUID());
		assertThat(gate.remaining(sale)).isZero();
	}
}
