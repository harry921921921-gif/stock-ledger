package com.sideproject.stockledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** V9：借款、借款利率、借款現金異動的規則由資料庫本身保證（設計文件 8）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class LoanSchemaTest {

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
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		userId = user("loan-schema");
		otherUserId = user("loan-schema-other");
		account = insertAccount(userId, "A");
		otherAccount = insertAccount(otherUserId, "B");
	}

	@Test
	void validLoansAccepted() {
		assertThatCode(() -> credit(userId, account, "EXISTING", null, 60, "2026-11-01")).doesNotThrowAnyException();
		assertThatCode(() -> pledge(userId, account, "NEW", account)).doesNotThrowAnyException();
	}

	@Test
	void creditNeedsPeriods() {
		assertThatThrownBy(() -> credit(userId, account, "EXISTING", null, null, "2026-11-01"))
			.satisfies(violates("ck_loan_credit"));
		assertThatThrownBy(() -> credit(userId, account, "EXISTING", null, 0, "2026-11-01"))
			.satisfies(violates("ck_loan_credit"));
		assertThatThrownBy(() -> credit(userId, account, "EXISTING", null, 60, null))
			.satisfies(violates("ck_loan_credit"));
		assertThatThrownBy(() -> credit(userId, account, "EXISTING", null, 60, "2026-11-05"))
			.satisfies(violates("ck_loan_credit"));
	}

	@Test
	void pledgeHasNoPeriods() {
		assertThatThrownBy(() -> jdbc.update(insertLoan("PLEDGE", "EXISTING", null, 60, "2026-11-01"), userId,
				account)).satisfies(violates("ck_loan_credit"));
	}

	@Test
	void disburseAccountOnlyForNewLoans() {
		assertThatThrownBy(() -> credit(userId, account, "EXISTING", account, 60, "2026-11-01"))
			.satisfies(violates("ck_loan_origin"));
		assertThatThrownBy(() -> credit(userId, account, "NEW", null, 60, "2026-11-01"))
			.satisfies(violates("ck_loan_origin"));
	}

	@Test
	void dbRejectsOtherUsersAccountOrLoan() {
		assertThatThrownBy(() -> credit(userId, otherAccount, "EXISTING", null, 60, "2026-11-01"))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("fk_loan_pay_account");
		Long othersLoan = pledge(otherUserId, otherAccount, "EXISTING", null);
		assertThatThrownBy(() -> loanMovement(userId, account, othersLoan, "LOAN_INTEREST", "2026-10-01", false))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("fk_cash_movement_loan");
	}

	@Test
	void loanMovementNeedsLoanId() {
		Long loan = pledge(userId, account, "EXISTING", null);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, "
				+ "amount, voided, created_at) VALUES (?, 'LOAN_INTEREST', '2026-10-25', ?, 900, FALSE, NOW())", userId,
				account)).satisfies(violates("ck_cash_movement_loan"));
		assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, "
				+ "amount, voided, created_at, loan_id) VALUES (?, 'DEPOSIT', '2026-10-25', ?, 900, FALSE, NOW(), ?)",
				userId, account, loan)).satisfies(violates("ck_cash_movement_loan"));
		// 還本、撥款可以沒有期別（使用者自己記的）
		assertThatCode(() -> loanMovement(userId, account, loan, "LOAN_PRINCIPAL", null, false))
			.doesNotThrowAnyException();
	}

	@Test
	void dbRejectsSecondActiveInstallment() {
		Long loan = credit(userId, account, "EXISTING", null, 60, "2026-11-01");
		Long first = loanMovement(userId, account, loan, "LOAN_INTEREST", "2026-11-01", false);

		assertThatThrownBy(() -> loanMovement(userId, account, loan, "LOAN_INTEREST", "2026-11-01", false))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("uk_cash_movement_loan_period");
		// 同一期不同類型可以（本金、利息各一筆）
		assertThatCode(() -> loanMovement(userId, account, loan, "LOAN_PRINCIPAL", "2026-11-01", false))
			.doesNotThrowAnyException();
		jdbc.update("UPDATE cash_movement SET voided = TRUE WHERE id = ?", first);
		assertThatCode(() -> loanMovement(userId, account, loan, "LOAN_INTEREST", "2026-11-01", false))
			.doesNotThrowAnyException();
	}

	/** 固定收支的期別規則仍然有效；借款的期別不需要固定項目。 */
	@Test
	void periodRulesForRecurringStillHold() {
		Long loan = credit(userId, account, "EXISTING", null, 60, "2026-11-01");
		assertThatThrownBy(() -> loanMovement(userId, account, loan, "LOAN_INTEREST", "2026-11-05", false))
			.satisfies(violates("ck_cash_movement_recurring"));
		assertThatThrownBy(() -> jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, "
				+ "amount, voided, created_at, period) VALUES (?, 'WITHDRAWAL', '2026-10-25', ?, 900, FALSE, NOW(), "
				+ "'2026-10-01')", userId, account)).satisfies(violates("ck_cash_movement_recurring"));
	}

	@Test
	void loanRateNeedsLoanId() {
		Long loan = pledge(userId, account, "EXISTING", null);
		assertThatCode(() -> jdbc.update("INSERT INTO rate_setting (user_id, kind, value, effective_date, created_at, "
				+ "loan_id) VALUES (?, 'LOAN_ANNUAL_RATE', 0.03, '2026-10-01', NOW(), ?)", userId, loan))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> jdbc.update("INSERT INTO rate_setting (user_id, kind, value, effective_date, "
				+ "created_at) VALUES (?, 'LOAN_ANNUAL_RATE', 0.03, '2026-10-01', NOW())", userId))
			.satisfies(violates("ck_rate_setting_loan"));
		assertThatThrownBy(() -> jdbc.update("INSERT INTO rate_setting (user_id, kind, value, effective_date, "
				+ "created_at, loan_id) VALUES (?, 'BROKERAGE_DISCOUNT', 0.3, '2026-10-01', NOW(), ?)", userId, loan))
			.satisfies(violates("ck_rate_setting_loan"));
	}

	private static ThrowingConsumer<Throwable> violates(String constraint) {
		return ex -> assertThat(ex).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
	}

	private static String insertLoan(String type, String origin, Long disburse, Integer periods, String firstPeriod) {
		return "INSERT INTO loan (user_id, name, loan_type, bank, original_amount, origin, start_date, "
				+ "start_principal, disburse_account_id, pay_account_id, pay_day, remaining_periods, first_period, "
				+ "posting_from, created_at) VALUES (?, '借款', '" + type + "', '銀行', 400000, '" + origin
				+ "', '2026-10-01', 400000, " + disburse + ", ?, 5, " + periods + ", "
				+ (firstPeriod == null ? "NULL" : "'" + firstPeriod + "'") + ", '2026-10-01', NOW())";
	}

	private Long credit(Long user, Long acc, String origin, Long disburse, Integer periods, String firstPeriod) {
		jdbc.update(insertLoan("CREDIT", origin, disburse, periods, firstPeriod), user, acc);
		return jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
	}

	private Long pledge(Long user, Long acc, String origin, Long disburse) {
		jdbc.update(insertLoan("PLEDGE", origin, disburse, null, null), user, acc);
		return jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
	}

	private Long loanMovement(Long user, Long acc, Long loan, String type, String period, boolean voided) {
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, voided, created_at, "
				+ "loan_id, period) VALUES (?, ?, '2026-10-25', ?, 900, ?, NOW(), ?, ?)", user, type, acc, voided, loan,
				period);
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
