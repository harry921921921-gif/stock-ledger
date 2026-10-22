package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.RateSetting;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.LoanRepository;
import com.sideproject.stockledger.repository.RateSettingRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/**
 * 借款自動記帳（設計文件 4、5、11）。借款直接用 repository 建立（起算日可以在過去、不受畫面規則限制），
 * 用 {@code postDue(loan, today)} 指定「今天」。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class LoanPostingServiceTest {

	@Autowired
	private LoanPostingService posting;

	@Autowired
	private LoanRepository loans;

	@Autowired
	private RateSettingRepository rates;

	@Autowired
	private CashMovementRepository cashMovements;

	@Autowired
	private CashAccountService accounts;

	@Autowired
	private CashMovementService movements;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private AppUserDetailsService userDetailsService;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Clock clock;

	private Long amyId;
	private Long bank;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		amyId = createIfMissing("lp-amy");
		createIfMissing("lp-ben");
		loginAs("lp-amy");
		bank = accounts.create(new CashAccountForm("土銀帳戶", CashAccountType.BANK_DEMAND, "土地銀行")).getId();
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	/** 既有信貸：40 萬、2.5%、剩 60 期、2026-09 起每月 5 號扣 → 兩期、每期本金與利息各一筆。 */
	@Test
	void creditPostsInstallments() {
		Loan loan = credit(amyId, bank, "2026-08-20", 60, "2026-09");

		int created = posting.postDue(loan, LocalDate.parse("2026-10-22"));

		assertThat(created).isEqualTo(4);
		assertThat(rows(loan)).extracting(r -> r.get("type"), r -> r.get("movement_date").toString(),
				r -> r.get("period").toString(), r -> amount(r))
			.containsExactly(
					tuple("LOAN_INTEREST", "2026-09-05", "2026-09-01", "834"),
					tuple("LOAN_PRINCIPAL", "2026-09-05", "2026-09-01", "6265"),
					tuple("LOAN_INTEREST", "2026-10-05", "2026-10-01", "821"),
					tuple("LOAN_PRINCIPAL", "2026-10-05", "2026-10-01", "6278"));
	}

	@Test
	void postingTwiceCreatesOnce() {
		Loan loan = credit(amyId, bank, "2026-08-20", 60, "2026-09");
		posting.postDue(loan, LocalDate.parse("2026-10-22"));

		assertThat(posting.postDue(loan, LocalDate.parse("2026-10-22"))).isZero();
		assertThat(rows(loan)).hasSize(4);
	}

	/** 作廢的期數不再自動補（同 DR-035）。 */
	@Test
	void voidedInstallmentNotRegenerated() {
		Loan loan = credit(amyId, bank, "2026-08-20", 60, "2026-09");
		posting.postDue(loan, LocalDate.parse("2026-09-30"));
		rows(loan).forEach(r -> movements.voidMovement((Long) r.get("id"), "銀行沒扣"));

		assertThat(posting.postDue(loan, LocalDate.parse("2026-09-30"))).isZero();
	}

	/** 把第 1 期本金改成銀行的數字：之後月付金不變（設計文件 11-1）。 */
	@Test
	void editedPrincipalKeepsLaterPayment() {
		Loan loan = credit(amyId, bank, "2026-08-20", 60, "2026-09");
		posting.postDue(loan, LocalDate.parse("2026-09-30"));
		Long principal = rows(loan).stream().filter(r -> r.get("type").equals("LOAN_PRINCIPAL"))
			.map(r -> (Long) r.get("id")).findFirst().orElseThrow();
		jdbc.update("UPDATE cash_movement SET amount = 6260 WHERE id = ?", principal);

		posting.postDue(loan, LocalDate.parse("2026-10-22"));

		List<Map<String, Object>> october = rows(loan).stream()
			.filter(r -> r.get("period").toString().equals("2026-10-01")).toList();
		BigDecimal payment = october.stream().map(r -> (BigDecimal) r.get("amount")).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		assertThat(payment).isEqualByComparingTo("7099");
		// 剩餘本金 393,740，利息 ＝ ceil(393740 × 0.025 ÷ 12) ＝ 821
		assertThat(october).filteredOn(r -> r.get("type").equals("LOAN_INTEREST"))
			.extracting(LoanPostingServiceTest::amount).containsExactly("821");
	}

	/** 質押：每月 25 號繳息，金額 ＝ 上次繳息日（第一期為起算日）到這次的利息。 */
	@Test
	void pledgePostsMonthlyInterest() {
		Loan loan = pledge(amyId, bank, "2026-09-25");

		posting.postDue(loan, LocalDate.parse("2026-10-25"));

		// 40 萬 × 3% × 30 天 ÷ 365 ＝ 986.30 → 987
		assertThat(rows(loan)).extracting(r -> r.get("type"), r -> r.get("movement_date").toString(),
				r -> r.get("period").toString(), LoanPostingServiceTest::amount)
			.containsExactly(tuple("LOAN_INTEREST", "2026-10-25", "2026-10-01", "987"));
	}

	/** 下一期的計息起點用上一筆實際繳息的日期（使用者改過日期也一樣）。 */
	@Test
	void pledgeUsesLastActualPaymentDate() {
		Loan loan = pledge(amyId, bank, "2026-09-25");
		posting.postDue(loan, LocalDate.parse("2026-10-25"));
		jdbc.update("UPDATE cash_movement SET movement_date = '2026-10-26' WHERE loan_id = ?", loan.getId());

		posting.postDue(loan, LocalDate.parse("2026-11-25"));

		// 10/26～11/25 共 30 天
		assertThat(rows(loan)).filteredOn(r -> r.get("period").toString().equals("2026-11-01"))
			.extracting(LoanPostingServiceTest::amount).containsExactly("987");
	}

	/** 全部還清：還清當天記最後一段利息，期別是下一個繳息月份；之後不再繳息（設計文件 11-4）。 */
	@Test
	void pledgePayoffPostsFinalInterest() {
		Loan loan = pledge(amyId, bank, "2026-09-25");
		posting.postDue(loan, LocalDate.parse("2026-10-25"));
		cashMovements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_PRINCIPAL,
				LocalDate.parse("2026-11-10"), bank, new BigDecimal("400000"), null, LocalDateTime.now(clock)));

		posting.postDue(loan, LocalDate.parse("2026-12-31"));

		// 10/25～11/10 共 16 天：400000 × 0.03 × 16 ÷ 365 ＝ 526.03 → 527
		assertThat(rows(loan)).filteredOn(r -> r.get("type").equals("LOAN_INTEREST"))
			.extracting(r -> r.get("movement_date").toString(), r -> r.get("period").toString(),
					LoanPostingServiceTest::amount)
			.containsExactly(tuple("2026-10-25", "2026-10-01", "987"), tuple("2026-11-10", "2026-11-01", "527"));
	}

	/** 還清日在當月繳息日之後（10/28）：當天就要記，不能等到下個月（審查 Important 2）。 */
	@Test
	void pledgePayoffAfterPayDayPostsSameDay() {
		Loan loan = pledge(amyId, bank, "2026-09-25");
		posting.postDue(loan, LocalDate.parse("2026-10-25"));
		cashMovements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_PRINCIPAL,
				LocalDate.parse("2026-10-28"), bank, new BigDecimal("400000"), null, LocalDateTime.now(clock)));

		posting.postDue(loan, LocalDate.parse("2026-10-28"));

		// 10/25～10/28 共 3 天：400000 × 0.03 × 3 ÷ 365 ＝ 98.63 → 99
		Map<String, Object> payoff = rows(loan).stream()
			.filter(r -> r.get("movement_date").toString().equals("2026-10-28") && r.get("type").equals("LOAN_INTEREST"))
			.findFirst().orElseThrow();
		assertThat(amount(payoff)).isEqualTo("99");
		assertThat(payoff.get("period").toString()).isEqualTo("2026-11-01");
	}

	/** 信貸全部提前還清：還清當天按天數記最後一段利息，期別是下一期的月份（使用者決定，2026-10-23）。 */
	@Test
	void creditFullPrepaymentPostsFinalInterest() {
		Loan loan = credit(amyId, bank, "2026-08-20", 60, "2026-09");
		posting.postDue(loan, LocalDate.parse("2026-09-30"));
		cashMovements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_PRINCIPAL,
				LocalDate.parse("2026-09-25"), bank, new BigDecimal("393735"), null, LocalDateTime.now(clock)));

		posting.postDue(loan, LocalDate.parse("2026-12-31"));

		// 9/5～9/25 共 20 天：393735 × 2.5% × 20 ÷ 365 ＝ 539.36 → 540；之後不再扣款
		assertThat(rows(loan)).filteredOn(r -> r.get("type").equals("LOAN_INTEREST"))
			.extracting(r -> r.get("movement_date").toString(), r -> r.get("period").toString(),
					LoanPostingServiceTest::amount)
			.containsExactly(tuple("2026-09-05", "2026-09-01", "834"), tuple("2026-09-25", "2026-10-01", "540"));
		assertThat(rows(loan)).filteredOn(r -> r.get("type").equals("LOAN_PRINCIPAL")).hasSize(2);
	}

	@Test
	void neverPostsFutureDates() {
		Loan loan = credit(amyId, bank, "2026-08-20", 60, "2026-09");

		assertThat(posting.postDue(loan, LocalDate.parse("2026-09-04"))).isZero();
	}

	/** 排程替每個使用者記到他自己的帳戶（DR-036）；沒有登入者也能跑。 */
	@Test
	void postsForEveryUser() {
		YearMonth thisMonth = YearMonth.now(clock);
		credit(amyId, bank, thisMonth.minusMonths(1).atDay(1).toString(), 60, thisMonth.toString(), 1);
		loginAs("lp-ben");
		Long benId = users.findByUsername("lp-ben").orElseThrow().getId();
		Long benBank = accounts.create(new CashAccountForm("班的帳戶", CashAccountType.BANK_DEMAND, "台銀")).getId();
		credit(benId, benBank, thisMonth.minusMonths(1).atDay(1).toString(), 60, thisMonth.toString(), 1);
		SecurityContextHolder.clearContext();

		assertThat(posting.postDue()).isEqualTo(4);
		assertThat(jdbc.queryForList("SELECT DISTINCT user_id, account_id FROM cash_movement ORDER BY user_id"))
			.extracting(r -> r.get("user_id"), r -> r.get("account_id"))
			.containsExactly(tuple(amyId, bank), tuple(benId, benBank));
	}

	private Loan credit(Long user, Long account, String start, int periods, String firstPeriod) {
		return credit(user, account, start, periods, firstPeriod, 5);
	}

	private Loan credit(Long user, Long account, String start, int periods, String firstPeriod, int payDay) {
		Loan loan = loans.save(Loan.create(user, "土銀信貸", LoanType.CREDIT, "土地銀行", new BigDecimal("400000"),
				LoanOrigin.EXISTING, LocalDate.parse(start), new BigDecimal("400000"), null, account, payDay, periods,
				YearMonth.parse(firstPeriod), null, LocalDateTime.now(clock)));
		rates.save(RateSetting.loanRate(user, loan.getId(), new BigDecimal("0.025"), LocalDate.parse(start),
				LocalDateTime.now(clock)));
		return loan;
	}

	private Loan pledge(Long user, Long account, String start) {
		Loan loan = loans.save(Loan.create(user, "玉山質押", LoanType.PLEDGE, "玉山銀行", new BigDecimal("400000"),
				LoanOrigin.EXISTING, LocalDate.parse(start), new BigDecimal("400000"), null, account, 25, null, null,
				null, LocalDateTime.now(clock)));
		rates.save(RateSetting.loanRate(user, loan.getId(), new BigDecimal("0.03"), LocalDate.parse(start),
				LocalDateTime.now(clock)));
		return loan;
	}

	private List<Map<String, Object>> rows(Loan loan) {
		return jdbc.queryForList("SELECT id, type, movement_date, period, amount FROM cash_movement WHERE loan_id = ? "
				+ "ORDER BY movement_date, type", loan.getId());
	}

	private static String amount(Map<String, Object> row) {
		return ((BigDecimal) row.get("amount")).stripTrailingZeros().toPlainString();
	}

	private void loginAs(String username) {
		UserDetails details = userDetailsService.loadUserByUsername(username);
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	private Long createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
		return users.findByUsername(username).orElseThrow().getId();
	}

}
