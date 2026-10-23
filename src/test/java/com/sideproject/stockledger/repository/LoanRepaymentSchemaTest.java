package com.sideproject.stockledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;

import org.assertj.core.api.ThrowingConsumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.mysql.MySQLContainer;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;

/** V11：借款還款方式、開辦費、每月扣款金額版本（spec 4.8a、DR-048）由資料庫保證。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class LoanRepaymentSchemaTest {

	private static final String LEGACY_DB = "legacy_v10";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MySQLContainer mysql;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private Clock clock;

	private Long userId;
	private Long account;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM source_allocation");
		jdbc.update("DELETE FROM trade_revision");
		jdbc.update("DELETE FROM trade");
		jdbc.update("DELETE FROM pledge_collateral");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		if (!users.existsByUsername("repay-schema")) {
			users.save(AppUser.create("repay-schema", encoder.encode("password123"), "repay-schema", Role.USER,
					LocalDateTime.now(clock)));
		}
		userId = users.findByUsername("repay-schema").orElseThrow().getId();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '土銀帳戶', 'BANK_DEMAND', '土銀', NOW())", userId);
		account = jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ?", Long.class, userId);
	}

	/** V11 之前建立的信貸與質押：信貸對應每月固定還款，質押對應每月繳息、到期還本（spec 4.8a-9）。 */
	@Test
	void legacyLoansMapped() throws SQLException {
		String root = mysql.getJdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/" + "$1");
		try (Connection c = DriverManager.getConnection(root, "root", mysql.getPassword());
				Statement s = c.createStatement()) {
			s.execute("DROP DATABASE IF EXISTS " + LEGACY_DB);
			s.execute("CREATE DATABASE " + LEGACY_DB);
		}
		String url = root.replace("/?", "/" + LEGACY_DB + "?").replaceAll("/$", "/" + LEGACY_DB);
		DriverManagerDataSource ds = new DriverManagerDataSource(url, "root", mysql.getPassword());
		Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("10").load().migrate();
		JdbcTemplate legacy = new JdbcTemplate(ds);
		legacy.update("INSERT INTO app_user (username, password_hash, display_name, role, created_at) "
				+ "VALUES ('old', 'x', 'old', 'USER', NOW())");
		Long user = legacy.queryForObject("SELECT id FROM app_user", Long.class);
		legacy.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '帳戶', 'BANK_DEMAND', '銀行', NOW())", user);
		Long acc = legacy.queryForObject("SELECT id FROM cash_account", Long.class);
		legacy.update("INSERT INTO loan (user_id, name, loan_type, bank, original_amount, origin, start_date, "
				+ "start_principal, pay_account_id, pay_day, remaining_periods, first_period, posting_from, created_at) "
				+ "VALUES (?, '信貸', 'CREDIT', '土銀', 400000, 'EXISTING', '2026-10-01', 400000, ?, 1, 84, "
				+ "'2026-11-01', '2026-10-01', NOW())", user, acc);
		legacy.update("INSERT INTO loan (user_id, name, loan_type, bank, original_amount, origin, start_date, "
				+ "start_principal, pay_account_id, pay_day, posting_from, created_at) "
				+ "VALUES (?, '質押', 'PLEDGE', '玉山', 100000, 'EXISTING', '2026-10-01', 100000, ?, 28, "
				+ "'2026-10-01', NOW())", user, acc);

		Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

		assertThat(legacy.queryForList("SELECT CONCAT(loan_type, ':', repayment_method) FROM loan ORDER BY id",
				String.class)).containsExactly("CREDIT:MONTHLY_FIXED", "PLEDGE:INTEREST_ONLY");
	}

	@Test
	void bulletNeedsDueDate() {
		assertThatThrownBy(() -> loan("PLEDGE", "BULLET", null, null, null)).satisfies(violates("ck_loan_method"));
		assertThatCode(() -> loan("PLEDGE", "BULLET", null, null, "'2027-07-28'")).doesNotThrowAnyException();
		assertThatThrownBy(() -> loan("PLEDGE", "BULLET", 84, "'2026-11-01'", "'2027-07-28'"))
			.satisfies(violates("ck_loan_method"));
	}

	@Test
	void monthlyNeedsPeriods() {
		assertThatThrownBy(() -> loan("CREDIT", "MONTHLY_FIXED", null, null, null))
			.satisfies(violates("ck_loan_method"));
		assertThatCode(() -> loan("CREDIT", "MONTHLY_FIXED", 84, "'2026-11-01'", null)).doesNotThrowAnyException();
		// 股票質押也可以每月固定還款（「股票質押」只決定擔保品，和怎麼還分開）
		assertThatCode(() -> loan("PLEDGE", "MONTHLY_FIXED", 84, "'2026-11-01'", null)).doesNotThrowAnyException();
	}

	@Test
	void loanFeeMovement() {
		Long loan = loan("CREDIT", "MONTHLY_FIXED", 84, "'2026-11-01'", null);
		assertThatCode(() -> jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, "
				+ "voided, created_at, loan_id) VALUES (?, 'LOAN_FEE', '2026-10-01', ?, 3000, FALSE, NOW(), ?)", userId,
				account, loan)).doesNotThrowAnyException();
		assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, "
				+ "amount, voided, created_at) VALUES (?, 'LOAN_FEE', '2026-10-01', ?, 3000, FALSE, NOW())", userId,
				account)).satisfies(violates("ck_cash_movement_loan"));
	}

	@Test
	void monthlyPaymentRate() {
		Long loan = loan("CREDIT", "MONTHLY_FIXED", 84, "'2026-11-01'", null);
		assertThatCode(() -> jdbc.update("INSERT INTO rate_setting (user_id, kind, value, effective_date, created_at, "
				+ "loan_id) VALUES (?, 'LOAN_MONTHLY_PAYMENT', 5133, '2026-10-01', NOW(), ?)", userId, loan))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> jdbc.update("INSERT INTO rate_setting (user_id, kind, value, effective_date, "
				+ "created_at) VALUES (?, 'LOAN_MONTHLY_PAYMENT', 5133, '2026-10-01', NOW())", userId))
			.satisfies(violates("ck_rate_setting_loan"));
	}

	private Long loan(String type, String method, Integer periods, String firstPeriod, String dueDate) {
		jdbc.update("INSERT INTO loan (user_id, name, loan_type, repayment_method, bank, original_amount, origin, "
				+ "start_date, start_principal, disburse_account_id, pay_account_id, pay_day, remaining_periods, "
				+ "first_period, due_date, posting_from, created_at) VALUES (?, '借款', '" + type + "', '" + method
				+ "', '銀行', 400000, 'NEW', '2026-10-01', 400000, ?, ?, 1, " + periods + ", "
				+ (firstPeriod == null ? "NULL" : firstPeriod) + ", " + (dueDate == null ? "NULL" : dueDate)
				+ ", '2026-10-01', NOW())", userId, account, account);
		return jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
	}

	private static ThrowingConsumer<Throwable> violates(String constraint) {
		return ex -> assertThat(ex).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
	}

}
