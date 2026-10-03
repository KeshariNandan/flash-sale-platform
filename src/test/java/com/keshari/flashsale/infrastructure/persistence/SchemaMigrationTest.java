package com.keshari.flashsale.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keshari.flashsale.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies that the Flyway migration applies cleanly to a real PostgreSQL and
 * that the constraints carrying business rules actually fire.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaMigrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void allTablesAreCreated() {
		List<String> tables = jdbc.queryForList(
				"select table_name from information_schema.tables where table_schema = 'public' order by 1",
				String.class);
		assertThat(tables).contains("app_user", "flash_sale", "inventory", "reservation", "customer_order",
				"outbox_event", "flyway_schema_history");
	}

	@Test
	void partialUniqueIndexAllowsRetryAfterExpiry() {
		String user = "u-" + UUID.randomUUID();
		UUID sale = insertOpenSale();
		jdbc.update("insert into app_user(user_id, email, saved_payment_token) values (?, ?, 'tok')", user,
				user + "@example.com");

		insertReservation(sale, user, "k1", "PENDING");

		// second active reservation for the same buyer in the same sale is rejected by ux_reservation_sale_user
		assertThatThrownBy(() -> insertReservation(sale, user, "k2", "PENDING"))
				.isInstanceOf(DataIntegrityViolationException.class);

		// once the first hold is EXPIRED the buyer may try again
		jdbc.update("update reservation set status = 'EXPIRED' where sale_id = ? and user_id = ?", sale, user);
		insertReservation(sale, user, "k3", "PENDING");
	}

	@Test
	void stockCannotGoNegative() {
		UUID sale = insertOpenSale();
		assertThatThrownBy(() -> jdbc.update("update inventory set stock_remaining = -1 where sale_id = ?", sale))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertOpenSale() {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into flash_sale(id, sku, name, unit_price, start_at, end_at, initial_stock, status)
				values (?, 'SKU-1', 'Test drop', 10.00, now(), now() + interval '1 hour', 5, 'OPEN')
				""", id);
		jdbc.update("insert into inventory(sale_id, stock_remaining) values (?, 5)", id);
		return id;
	}

	private void insertReservation(UUID sale, String user, String key, String status) {
		jdbc.update("""
				insert into reservation(id, sale_id, user_id, idempotency_key, status, expires_at)
				values (?, ?, ?, ?, ?, now() + interval '2 minutes')
				""", UUID.randomUUID(), sale, user, key, status);
	}
}
