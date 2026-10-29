package com.sideproject.stockledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * V12 新增 app_user.onboarding_step（DR-050）。要測「既有帳號的預設值」，所以不啟動 Spring：
 * 每個測試開一個新資料庫，Flyway 先跑到 V11、寫入舊資料，再跑 V12 檢查結果。
 */
class OnboardingSchemaTest {

	private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withUsername("root");

	private static final AtomicInteger DB_COUNT = new AtomicInteger();

	private DriverManagerDataSource dataSource;
	private JdbcTemplate jdbc;

	@BeforeAll
	static void start() {
		MYSQL.start();
	}

	@AfterAll
	static void stop() {
		MYSQL.stop();
	}

	@BeforeEach
	void migrateToV11() {
		String db = "onbtest" + DB_COUNT.incrementAndGet();
		new JdbcTemplate(dataSource("")).execute("CREATE DATABASE " + db);
		dataSource = dataSource(db);
		jdbc = new JdbcTemplate(dataSource);
		migrate("11");
	}

	@Test
	void migrationMarksUsersWithAccountsDone() {
		Long withAccount = insertUser("has_account");
		Long withoutAccount = insertUser("no_account");
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '銀行', 'BANK', '台銀', NOW())", withAccount);

		migrate("12");

		assertThat(step(withAccount)).isEqualTo("DONE");
		assertThat(step(withoutAccount)).isEqualTo("WELCOME");
	}

	@Test
	void rejectsUnknownStep() {
		Long id = insertUser("u");
		migrate("12");

		assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET onboarding_step = 'X' WHERE id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_app_user_onboarding_step");
	}

	private Long insertUser(String username) {
		jdbc.update("INSERT INTO app_user (username, password_hash, display_name, role, created_at) "
				+ "VALUES (?, 'x', ?, 'USER', NOW())", username, username);
		return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
	}

	private String step(Long userId) {
		return jdbc.queryForObject("SELECT onboarding_step FROM app_user WHERE id = ?", String.class, userId);
	}

	private void migrate(String target) {
		Flyway.configure().dataSource(dataSource).target(target).load().migrate();
	}

	private static DriverManagerDataSource dataSource(String db) {
		String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + db;
		return new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
	}

}
