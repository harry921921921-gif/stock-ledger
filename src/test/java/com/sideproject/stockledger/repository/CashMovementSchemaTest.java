package com.sideproject.stockledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.assertj.core.api.ThrowingConsumer;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;

/** 資料庫本身就要擋得住錯誤的現金事件，不只靠程式檢查（CLAUDE.md：讓資料庫保證關聯正確）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CashMovementSchemaTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private Clock clock;

	private Long userId;
	private Long accountA;
	private Long accountB;

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
		if (!users.existsByUsername("cm-schema")) {
			users.save(AppUser.create("cm-schema", encoder.encode("password123"), "cm-schema", Role.USER,
					LocalDateTime.now(clock)));
		}
		userId = users.findByUsername("cm-schema").orElseThrow().getId();
		accountA = insertAccount("A");
		accountB = insertAccount("B");
	}

	@Test
	void dbRejectsSecondActiveOpening() {
		insert("OPENING", accountA, null, "1000", null, false);

		assertThatThrownBy(() -> insert("OPENING", accountA, null, "5", null, false))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uk_cash_movement_opening");
	}

	/** 作廢的初始現金餘額不佔位置：作廢後可以重新設定。 */
	@Test
	void voidedOpeningDoesNotBlockNewOne() {
		insert("OPENING", accountA, null, "1000", null, true);

		assertThatCode(() -> insert("OPENING", accountA, null, "5", null, false)).doesNotThrowAnyException();
	}

	@Test
	void dbRejectsSecondActiveReconcileSameDay() {
		insert("RECONCILE", accountA, null, null, "900", false);

		assertThatThrownBy(() -> insert("RECONCILE", accountA, null, null, "800", false))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uk_cash_movement_reconcile_day");
	}

	@Test
	void voidedReconcileDoesNotBlockSameDay() {
		insert("RECONCILE", accountA, null, null, "900", true);

		assertThatCode(() -> insert("RECONCILE", accountA, null, null, "800", false)).doesNotThrowAnyException();
	}

	@Test
	void dbRejectsNegativeOpening() {
		assertThatThrownBy(() -> insert("OPENING", accountA, null, "-1", null, false))
			.satisfies(violates("ck_cash_movement_amount"));
	}

	@Test
	void dbRejectsDepositWithActualBalance() {
		assertThatThrownBy(() -> insert("DEPOSIT", accountA, null, "100", "900", false))
			.satisfies(violates("ck_cash_movement_reconcile"));
	}

	@Test
	void dbRejectsReconcileWithoutActualBalance() {
		assertThatThrownBy(() -> insert("RECONCILE", accountA, null, null, null, false))
			.satisfies(violates("ck_cash_movement_reconcile"));
	}

	/** 新台幣只記整數元，資料庫也要擋（審核建議）。 */
	@Test
	void dbRejectsFractionalAmounts() {
		assertThatThrownBy(() -> insert("DEPOSIT", accountA, null, "100.5", null, false))
			.satisfies(violates("ck_cash_movement_whole_dollars"));
		assertThatThrownBy(() -> insert("RECONCILE", accountA, null, null, "900.5", false))
			.satisfies(violates("ck_cash_movement_whole_dollars"));
	}

	/** 異動和帳戶一定屬於同一個人：用別人的帳戶寫入，資料庫本身就擋下（審核 Important 2）。 */
	@Test
	void dbRejectsMovementOnOtherUsersAccount() {
		Long otherUser = otherUserId();

		assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, "
				+ "amount, voided, created_at) VALUES (?, 'DEPOSIT', '2026-10-01', ?, 100, FALSE, NOW())",
				otherUser, accountA))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("fk_cash_movement_account");
	}

	@Test
	void dbRejectsTransferIntoOtherUsersAccount() {
		Long otherUser = otherUserId();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, 'other', 'SETTLEMENT', '台銀', NOW())", otherUser);
		Long otherAccount = jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ?", Long.class, otherUser);

		assertThatThrownBy(() -> insert("TRANSFER", accountA, otherAccount, "100", null, false))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("fk_cash_movement_to_account");
	}

	@Test
	void dbRejectsRevisionByOtherUser() {
		insert("DEPOSIT", accountA, null, "100", null, false);
		Long movementId = jdbc.queryForObject("SELECT id FROM cash_movement", Long.class);

		assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movement_revision (cash_movement_id, user_id, revised_at, "
				+ "action, field) VALUES (?, ?, NOW(), 'VOID', 'voided')", movementId, otherUserId()))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("fk_cash_movement_revision_movement");
	}

	@Test
	void dbRejectsTransferWithoutTarget() {
		assertThatThrownBy(() -> insert("TRANSFER", accountA, null, "100", null, false))
			.satisfies(violates("ck_cash_movement_transfer"));
	}

	@Test
	void dbRejectsTransferToSameAccount() {
		assertThatThrownBy(() -> insert("TRANSFER", accountA, accountA, "100", null, false))
			.satisfies(violates("ck_cash_movement_transfer"));
	}

	@Test
	void dbRejectsTargetOnNonTransfer() {
		assertThatThrownBy(() -> insert("DEPOSIT", accountA, accountB, "100", null, false))
			.satisfies(violates("ck_cash_movement_transfer"));
	}

	@Test
	void dbRejectsNonPositiveDeposit() {
		assertThatThrownBy(() -> insert("DEPOSIT", accountA, null, "0", null, false))
			.satisfies(violates("ck_cash_movement_amount"));
	}

	@Test
	void dbRejectsReconcileWithAmount() {
		assertThatThrownBy(() -> insert("RECONCILE", accountA, null, "100", "900", false))
			.satisfies(violates("ck_cash_movement_reconcile"));
	}

	/** V7 取消生活費類型（DR-035）：資料庫不再接受 LIVING_EXPENSE。 */
	@Test
	void dbRejectsLivingExpense() {
		assertThatThrownBy(() -> insert("LIVING_EXPENSE", accountA, null, "27000", null, false))
			.satisfies(violates("ck_cash_movement_type"));
	}

	@Test
	void dbRejectsUnknownType() {
		assertThatThrownBy(() -> insert("SALARY", accountA, null, "100", null, false))
			.satisfies(violates("ck_cash_movement_type"));
	}

	/**
	 * MySQL 違反 CHECK 時是錯誤碼 3819，Spring 包成 UncategorizedSQLException（不是 DataIntegrityViolationException），
	 * 所以檢查「是資料庫錯誤，而且是這一條限制擋下的」。
	 */
	private static ThrowingConsumer<Throwable> violates(String constraint) {
		return ex -> assertThat(ex).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
	}

	private void insert(String type, Long account, Long to, String amount, String actual, boolean voided) {
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, to_account_id, amount, "
				+ "actual_balance, voided, created_at) VALUES (?, ?, '2026-10-01', ?, ?, ?, ?, ?, NOW())",
				userId, type, account, to, amount == null ? null : new java.math.BigDecimal(amount),
				actual == null ? null : new java.math.BigDecimal(actual), voided);
	}

	private Long otherUserId() {
		if (!users.existsByUsername("cm-schema-other")) {
			users.save(AppUser.create("cm-schema-other", encoder.encode("password123"), "other", Role.USER,
					LocalDateTime.now(clock)));
		}
		return users.findByUsername("cm-schema-other").orElseThrow().getId();
	}

	private Long insertAccount(String name) {
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, ?, 'SETTLEMENT', '國泰證券', NOW())", userId, name);
		return jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ? AND name = ?", Long.class, userId,
				name);
	}

}
