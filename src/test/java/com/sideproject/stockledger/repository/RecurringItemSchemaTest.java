package com.sideproject.stockledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;

/** V8：固定項目與「自動產生的異動」的規則由資料庫本身保證（設計文件 4.2、7）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RecurringItemSchemaTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private Clock clock;

	private Long userId;
	private Long otherUserId;
	private Long account;
	private Long otherAccount;

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
		userId = user("ri-schema");
		otherUserId = user("ri-schema-other");
		account = insertAccount(userId, "A");
		otherAccount = insertAccount(otherUserId, "B");
	}

	@Test
	void validItemsAccepted() {
		assertThatCode(() -> item(userId, account, "EXPENSE", "FIXED", "8000", "MONTHLY", null, 5, "2026-01-01", null))
			.doesNotThrowAnyException();
		assertThatCode(() -> item(userId, account, "EXPENSE", "MANUAL", null, "MONTHLY", null, 31, "2026-01-01",
				"2026-12-01")).doesNotThrowAnyException();
		assertThatCode(() -> item(userId, account, "INCOME", "FIXED", "48000", "YEARLY", 3, 15, "2026-01-01", null))
			.doesNotThrowAnyException();
	}

	@Test
	void dbRejectsSecondActivePeriod() {
		Long item = item(userId, account, "EXPENSE", "FIXED", "8000", "MONTHLY", null, 5, "2026-01-01", null);
		Long first = movement(item, "2026-10-01", false);

		assertThatThrownBy(() -> movement(item, "2026-10-01", false))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("uk_cash_movement_recurring_period");

		jdbc.update("UPDATE cash_movement SET voided = TRUE WHERE id = ?", first);
		assertThatCode(() -> movement(item, "2026-10-01", false)).doesNotThrowAnyException();
		assertThatCode(() -> movement(item, "2026-11-01", false)).doesNotThrowAnyException();
	}

	@Test
	void dbRejectsManualWithAmountOrNonMonthly() {
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "MANUAL", "100", "MONTHLY", null, 5, "2026-01-01", null))
			.satisfies(violates("ck_recurring_item_amount"));
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "MANUAL", null, "YEARLY", 3, 5, "2026-01-01", null))
			.satisfies(violates("ck_recurring_item_amount"));
	}

	@Test
	void dbRejectsFixedWithoutValidAmount() {
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", null, "MONTHLY", null, 5, "2026-01-01", null))
			.satisfies(violates("ck_recurring_item_amount"));
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", "0", "MONTHLY", null, 5, "2026-01-01", null))
			.satisfies(violates("ck_recurring_item_amount"));
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", "10.5", "MONTHLY", null, 5, "2026-01-01",
				null)).satisfies(violates("ck_recurring_item_amount"));
	}

	@Test
	void dbRejectsBadAnchorAndDay() {
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", "1", "MONTHLY", 3, 5, "2026-01-01", null))
			.satisfies(violates("ck_recurring_item_anchor"));
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", "1", "YEARLY", null, 5, "2026-01-01", null))
			.satisfies(violates("ck_recurring_item_anchor"));
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", "1", "YEARLY", 13, 5, "2026-01-01", null))
			.satisfies(violates("ck_recurring_item_anchor"));
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", "1", "MONTHLY", null, 0, "2026-01-01", null))
			.satisfies(violates("ck_recurring_item_day"));
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", "1", "MONTHLY", null, 32, "2026-01-01", null))
			.satisfies(violates("ck_recurring_item_day"));
	}

	@Test
	void dbRejectsBadMonths() {
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", "1", "MONTHLY", null, 5, "2026-01-02", null))
			.satisfies(violates("ck_recurring_item_months"));
		assertThatThrownBy(() -> item(userId, account, "EXPENSE", "FIXED", "1", "MONTHLY", null, 5, "2026-05-01",
				"2026-04-01")).satisfies(violates("ck_recurring_item_months"));
	}

	@Test
	void dbRejectsPeriodNotFirstDay() {
		Long item = item(userId, account, "EXPENSE", "FIXED", "8000", "MONTHLY", null, 5, "2026-01-01", null);

		assertThatThrownBy(() -> movement(item, "2026-10-05", false)).satisfies(violates("ck_cash_movement_recurring"));
	}

	@Test
	void dbRejectsItemWithoutPeriodAndRecurringTransfer() {
		Long item = item(userId, account, "EXPENSE", "FIXED", "8000", "MONTHLY", null, 5, "2026-01-01", null);

		assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, "
				+ "amount, voided, created_at, recurring_item_id) VALUES (?, 'WITHDRAWAL', '2026-10-05', ?, 1, FALSE, "
				+ "NOW(), ?)", userId, account, item)).satisfies(violates("ck_cash_movement_recurring"));
		Long second = insertAccount(userId, "C");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, "
				+ "to_account_id, amount, voided, created_at, recurring_item_id, period) VALUES (?, 'TRANSFER', "
				+ "'2026-10-05', ?, ?, 1, FALSE, NOW(), ?, '2026-10-01')", userId, account, second, item))
			.satisfies(violates("ck_cash_movement_recurring"));
	}

	/** 項目只能用自己的帳戶；異動只能指向自己的項目。 */
	@Test
	void dbRejectsOtherUsersAccountOrItem() {
		assertThatThrownBy(() -> item(userId, otherAccount, "EXPENSE", "FIXED", "1", "MONTHLY", null, 5, "2026-01-01",
				null)).isInstanceOf(DataAccessException.class).hasMessageContaining("fk_recurring_item_account");

		Long othersItem = item(otherUserId, otherAccount, "EXPENSE", "FIXED", "1", "MONTHLY", null, 5, "2026-01-01",
				null);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, "
				+ "amount, voided, created_at, recurring_item_id, period) VALUES (?, 'WITHDRAWAL', '2026-10-05', ?, 1, "
				+ "FALSE, NOW(), ?, '2026-10-01')", userId, account, othersItem))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("fk_cash_movement_recurring_item");
	}

	private static ThrowingConsumer<Throwable> violates(String constraint) {
		return ex -> assertThat(ex).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
	}

	private Long item(Long user, Long acc, String direction, String mode, String amount, String cycle, Integer anchor,
			int day, String start, String end) {
		jdbc.update("INSERT INTO recurring_item (user_id, name, direction, amount_mode, amount, cycle, anchor_month, "
				+ "day_of_month, account_id, start_month, end_month, posting_from, created_at) VALUES (?, '項目', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
				user, direction, mode, amount == null ? null : new BigDecimal(amount), cycle, anchor, day, acc, start, end,
				start.substring(0, 8) + "01");
		return jdbc.queryForObject("SELECT MAX(id) FROM recurring_item", Long.class);
	}

	private Long movement(Long item, String period, boolean voided) {
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, voided, created_at, "
				+ "recurring_item_id, period) VALUES (?, 'WITHDRAWAL', '2026-10-05', ?, 8000, ?, NOW(), ?, ?)", userId,
				account, voided, item, period);
		return jdbc.queryForObject("SELECT MAX(id) FROM cash_movement", Long.class);
	}

	private Long user(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
		return users.findByUsername(username).orElseThrow().getId();
	}

	private Long insertAccount(Long user, String name) {
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, ?, 'BANK_DEMAND', '台銀', NOW())", user, name);
		return jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ? AND name = ?", Long.class, user, name);
	}

}
