package com.keshari.flashsale.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.keshari.flashsale.TestcontainersConfiguration;
import com.keshari.flashsale.domain.AppUser;
import com.keshari.flashsale.domain.FlashSale;
import com.keshari.flashsale.domain.ReservationStatus;
import com.keshari.flashsale.infrastructure.persistence.AppUserRepository;
import com.keshari.flashsale.infrastructure.persistence.ReservationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Same claim invariants as {@link ClaimServiceConcurrencyTest}, exercised
 * through RedisStockGate. Postgres inventory is not the admission counter
 * here; {@link StockGate#remaining} is.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "flashsale.gate=redis")
class ClaimServiceRedisConcurrencyTest {

	@Autowired ClaimService claims;
	@Autowired SaleService sales;
	@Autowired StockGate gate;
	@Autowired AppUserRepository users;
	@Autowired ReservationRepository reservations;

	FlashSale openSale(int stock) {
		Instant now = Instant.now();
		FlashSale sale = sales.create(new SaleService.CreateSale("SKU-RD", "Redis drop", new BigDecimal("9.99"),
				now.minusSeconds(1), now.plusSeconds(600), stock, 1, 120));
		return sales.open(sale.getId());
	}

	List<String> buyers(int n) {
		List<String> ids = new ArrayList<>();
		Instant now = Instant.now();
		for (int i = 0; i < n; i++) {
			String id = "rd-" + UUID.randomUUID();
			users.save(new AppUser(id, id + "@example.com", "tok", now));
			ids.add(id);
		}
		return ids;
	}

	<T> List<ClaimResult> stampede(List<T> inputs, Function<T, ClaimCommand> toCommand) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		List<Future<ClaimResult>> futures = new ArrayList<>();
		for (T in : inputs) {
			Callable<ClaimResult> task = () -> {
				start.await();
				return claims.claim(toCommand.apply(in));
			};
			futures.add(pool.submit(task));
		}
		start.countDown();
		List<ClaimResult> results = new ArrayList<>();
		for (Future<ClaimResult> f : futures) {
			results.add(f.get());
		}
		pool.shutdown();
		return results;
	}

	@Test
	void neverAcceptsMoreClaimsThanStock() throws Exception {
		int stock = 20;
		FlashSale sale = openSale(stock);
		List<String> buyers = buyers(200);

		List<ClaimResult> results = stampede(buyers,
				b -> new ClaimCommand(sale.getId(), b, "key-" + UUID.randomUUID(), 1));

		long accepted = results.stream().filter(ClaimResult.Accepted.class::isInstance).count();
		long soldOut = results.stream().filter(ClaimResult.SoldOut.class::isInstance).count();
		assertThat(accepted).isEqualTo(stock);
		assertThat(soldOut).isEqualTo(200 - stock);
		assertThat(gate.remaining(sale.getId())).isZero();
		assertThat(reservations.countBySaleIdAndStatus(sale.getId(), ReservationStatus.PENDING)).isEqualTo(stock);
		assertThat(sales.stats(sale.getId()).oversell()).isZero();
		assertThat(sales.stats(sale.getId()).accounted()).isEqualTo(stock);
	}

	@Test
	void oneBuyerManyParallelRequestsGetsOneUnit() throws Exception {
		FlashSale sale = openSale(50);
		String buyer = buyers(1).getFirst();
		List<Integer> attempts = java.util.stream.IntStream.range(0, 40).boxed().toList();

		List<ClaimResult> results = stampede(attempts,
				i -> new ClaimCommand(sale.getId(), buyer, "k-" + i + "-" + UUID.randomUUID(), 1));

		assertThat(results.stream().filter(ClaimResult.Accepted.class::isInstance).count()).isEqualTo(1);
		assertThat(results.stream().filter(ClaimResult.Duplicate.class::isInstance).count()).isEqualTo(39);
		assertThat(gate.remaining(sale.getId())).isEqualTo(49);
	}

	@Test
	void sameIdempotencyKeyReplaysOriginalOutcome() {
		FlashSale sale = openSale(5);
		String buyer = buyers(1).getFirst();
		String key = "idem-" + UUID.randomUUID();

		ClaimResult first = claims.claim(new ClaimCommand(sale.getId(), buyer, key, 1));
		assertThat(first).isInstanceOf(ClaimResult.Accepted.class);
		UUID originalId = ((ClaimResult.Accepted) first).reservation().getId();

		ClaimResult again = claims.claim(new ClaimCommand(sale.getId(), buyer, key, 1));
		assertThat(again).isInstanceOf(ClaimResult.Replayed.class);
		assertThat(((ClaimResult.Replayed) again).reservation().getId()).isEqualTo(originalId);
		assertThat(gate.remaining(sale.getId())).isEqualTo(4);
	}

	@Test
	void unregisteredBuyerIsRejectedAndUnitReturned() {
		FlashSale sale = openSale(2);
		ClaimResult r = claims.claim(
				new ClaimCommand(sale.getId(), "ghost-" + UUID.randomUUID(), "k-" + UUID.randomUUID(), 1));
		assertThat(r).isInstanceOf(ClaimResult.UnknownBuyer.class);
		assertThat(gate.remaining(sale.getId())).isEqualTo(2);
	}
}
