package com.sideproject.stockledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * V7 把既有的生活費轉成支出（DR-035）。要測「轉換既有資料」，所以不啟動 Spring：
 * 每個測試開一個新的資料庫，Flyway 先跑到 V6、寫入舊資料，再跑 V7 檢查結果。
 */
class V7MigrationTest {

	private static final String REASON = "生活費類型取消，改為支出（DR-035）";

	private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withUsername("root");

	private static final AtomicInteger DB_COUNT = new AtomicInteger();

	private DriverManagerDataSource dataSource;
	private JdbcTemplate jdbc;
	private Long userId;
	private Long accountId;

	@BeforeAll
	static void start() {
		MYSQL.start();
	}

	@AfterAll
	static void stop() {
		MYSQL.stop();
	}

	@BeforeEach
	void migrateToV6() {
		String db = "v7test" + DB_COUNT.incrementAndGet();
		new JdbcTemplate(dataSource("")).execute("CREATE DATABASE " + db);
		dataSource = dataSource(db);
		jdbc = new JdbcTemplate(dataSource);
		migrate("6");
		jdbc.update("INSERT INTO app_user (username, password_hash, display_name, role, created_at) "
				+ "VALUES ('u', 'x', 'u', 'USER', NOW())");
		userId = jdbc.queryForObject("SELECT id FROM app_user", Long.class);
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '銀行', 'BANK', '台銀', NOW())", userId);
		accountId = jdbc.queryForObject("SELECT id FROM cash_account", Long.class);
	}

	@Test
	void livingExpenseBecomesWithdrawal() {
		Long id = insertLiving("27000", null, false);

		migrate("7");

		Map<String, Object> row = movement(id);
		assertThat(row.get("type")).isEqualTo("WITHDRAWAL");
		assertThat(row.get("note")).isEqualTo("生活費");
		assertThat(row.get("amount").toString()).isEqualTo("27000.0000");
		assertThat(revisions(id)).containsExactly(
				List.of("EDIT", "type", "LIVING_EXPENSE", "WITHDRAWAL", REASON),
				List.of("EDIT", "note", "null", "生活費", REASON));
	}

	@Test
	void existingNoteKept() {
		Long id = insertLiving("27000", "10 月", false);

		migrate("7");

		assertThat(movement(id).get("note")).isEqualTo("10 月");
		assertThat(revisions(id)).containsExactly(List.of("EDIT", "type", "LIVING_EXPENSE", "WITHDRAWAL", REASON));
	}

	@Test
	void voidedLivingExpenseAlsoConverted() {
		Long id = insertLiving("500", "記錯", true);

		migrate("7");

		Map<String, Object> row = movement(id);
		assertThat(row.get("type")).isEqualTo("WITHDRAWAL");
		assertThat(row.get("voided")).isEqualTo(true);
	}

	@Test
	void otherTypesUntouched() {
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, note, voided, "
				+ "created_at) VALUES (?, 'DEPOSIT', '2026-10-05', ?, 62000, NULL, FALSE, NOW())", userId, accountId);
		Long id = jdbc.queryForObject("SELECT MAX(id) FROM cash_movement", Long.class);

		migrate("7");

		assertThat(movement(id).get("type")).isEqualTo("DEPOSIT");
		assertThat(movement(id).get("note")).isNull();
		assertThat(revisions(id)).isEmpty();
	}

	@Test
	void typeCheckRejectsLivingExpense() {
		migrate("7");

		assertThatThrownBy(() -> insertLiving("100", null, false)).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_cash_movement_type");
	}

	private Long insertLiving(String amount, String note, boolean voided) {
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, note, voided, "
				+ "created_at) VALUES (?, 'LIVING_EXPENSE', '2026-10-31', ?, ?, ?, ?, NOW())", userId, accountId,
				new java.math.BigDecimal(amount), note, voided);
		return jdbc.queryForObject("SELECT MAX(id) FROM cash_movement", Long.class);
	}

	private Map<String, Object> movement(Long id) {
		return jdbc.queryForMap("SELECT type, note, amount, voided FROM cash_movement WHERE id = ?", id);
	}

	/** 每列：action, field, old_value, new_value, reason（null 寫成 "null" 方便比對）。 */
	private List<List<String>> revisions(Long id) {
		return jdbc.query("SELECT action, field, old_value, new_value, reason FROM cash_movement_revision "
				+ "WHERE cash_movement_id = ? AND user_id = ? ORDER BY id", (rs, n) -> List.of(rs.getString(1),
						rs.getString(2), String.valueOf(rs.getString(3)), String.valueOf(rs.getString(4)),
						String.valueOf(rs.getString(5))), id, userId);
	}

	private void migrate(String target) {
		Flyway.configure().dataSource(dataSource).target(target).load().migrate();
	}

	private static DriverManagerDataSource dataSource(String db) {
		String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + db;
		return new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
	}

}
