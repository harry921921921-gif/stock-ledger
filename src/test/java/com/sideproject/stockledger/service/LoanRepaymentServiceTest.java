package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
import com.sideproject.stockledger.dto.CollateralPick;
import com.sideproject.stockledger.dto.LoanCard;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.PrincipalForm;
import com.sideproject.stockledger.dto.RateVersionForm;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 借款依還款方式新增（spec 4.8a、DR-048）。數字是使用者的土銀信貸與玉山質押。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class LoanRepaymentServiceTest {

	@Autowired
	private LoanService loans;

	@Autowired
	private TradeService trades;

	@Autowired
	private OverviewService overview;

	@Autowired
	private CashAccountService accounts;

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

	private Long bank;
	private Long etf;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM source_allocation");
		jdbc.update("DELETE FROM trade_revision");
		jdbc.update("DELETE FROM trade");
		jdbc.update("DELETE FROM pledge_collateral");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM rate_setting WHERE user_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		jdbc.update("DELETE FROM stock_price");
		jdbc.update("DELETE FROM dividend");
		jdbc.update("DELETE FROM stock");
		createIfMissing("rp-amy");
		createIfMissing("rp-ben");
		loginAs("rp-amy");
		bank = accounts.create(new CashAccountForm("土銀帳戶", CashAccountType.BANK_DEMAND, "土地銀行")).getId();
		jdbc.update("INSERT INTO stock (code, name, kind, updated_at) VALUES ('00830', '國泰費城半導體', 'ETF', NOW())");
		etf = jdbc.queryForObject("SELECT id FROM stock WHERE code = '00830'", Long.class);
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	/** 土銀信貸：40 萬、2.15%、銀行每月扣 5,133、1 號、84 期、2026-02-01 撥款 → 第一期 2026-03，扣款金額存成版本。 */
	@Test
	void monthlyFixedCreate() {
		Loan loan = loans.create(credit("2.15", "5133"));

		assertThat(loan.getRepaymentMethod()).isEqualTo(RepaymentMethod.MONTHLY_FIXED);
		assertThat(loan.getFirstPeriod()).isEqualTo(java.time.YearMonth.of(2026, 3));
		assertThat(loans.paymentWarning(loan.getId())).isNull();
		assertThat(jdbc.queryForObject("SELECT value FROM rate_setting WHERE loan_id = ? AND kind = "
				+ "'LOAN_MONTHLY_PAYMENT'", BigDecimal.class, loan.getId())).isEqualByComparingTo("5133");
	}

	/** 利率填成 2.45%：合約月付金 5,187，和銀行扣款差 54 → 提醒（不擋存檔）。 */
	@Test
	void paymentWarning() {
		Loan loan = loans.create(credit("2.45", "5133"));

		assertThat(loans.paymentWarning(loan.getId()))
			.isEqualTo("用金額、利率、期數算出來是 5,187，和銀行扣款 5,133 差 54；請確認金額或利率");
	}

	/** 玉山質押：10 萬、4%、到期 2027-07-28、股票質押、勾 00830 1,000 股。 */
	@Test
	void bulletCreate() {
		holding(1000);

		Loan loan = loans.create(bullet(List.of(new CollateralPick(etf, 1000L)), null));

		assertThat(loan.getType()).isEqualTo(LoanType.PLEDGE);
		assertThat(loan.getRepaymentMethod()).isEqualTo(RepaymentMethod.BULLET);
		assertThat(jdbc.queryForObject("SELECT shares FROM pledge_collateral WHERE loan_id = ?", Long.class,
				loan.getId())).isEqualTo(1000L);
	}

	/** 勾了質押但沒勾擔保股票：可以存，維持率是 —（null）。 */
	@Test
	void pledgeWithoutCollateral() {
		Loan loan = loans.create(bullet(List.of(), null));

		assertThat(card(loan).maintenancePercent()).isNull();
	}

	/** 開辦費 3,000：撥款日記一筆，算進已付利息（借款成本）。 */
	@Test
	void feeRecorded() {
		Loan loan = loans.create(bullet(List.of(), new BigDecimal("3000")));

		assertThat(jdbc.queryForObject("SELECT CONCAT(movement_date, ':', ROUND(amount)) FROM cash_movement "
				+ "WHERE loan_id = ? AND type = 'LOAN_FEE'", String.class, loan.getId())).isEqualTo("2026-07-28:3000");
		assertThat(card(loan).paidInterest()).isGreaterThanOrEqualTo(new BigDecimal("3000"));
	}

	/** 還款方式和欄位對不上：服務層擋（不能只靠畫面隱藏欄位）。 */
	@Test
	void fieldsMustMatchMethod() {
		LoanForm b = bullet(List.of(), null);
		assertThatThrownBy(() -> loans.create(withPeriods(b, 84))).hasMessage("到期一次還本金＋利息不用填總期數");
		assertThatThrownBy(() -> loans.create(credit("2.15", null))).hasMessage("請填銀行每月扣款金額");
		assertThatThrownBy(() -> loans.create(withDue(b, null))).hasMessage("請填到期日");
	}

	/** 扣款比當期利息少（例如填錯成 500）：擋下並說明。 */
	@Test
	void paymentBelowInterestRejected() {
		assertThatThrownBy(() -> loans.create(credit("2.15", "500")))
			.hasMessage("每月扣款 500 元比當期利息 717 元少");
	}

	/** 到期還本：本金 10 萬＋利息 4,000 一起記；借款還清。還本表單預設帶入本金與應計利息。 */
	@Test
	void bulletRepayWithInterest() {
		Loan loan = loans.create(bullet(List.of(), null));
		PrincipalForm defaults = loans.repayDefaults(loan.getId());
		assertThat(defaults.amount()).isEqualByComparingTo("100000");
		assertThat(defaults.interest()).isPositive();

		loans.repay(loan.getId(), new PrincipalForm(LocalDate.now(clock), new BigDecimal("100000"), bank,
				new BigDecimal("4000")));

		assertThat(jdbc.queryForList("SELECT CONCAT(type, ':', ROUND(amount)) FROM cash_movement WHERE loan_id = ? "
				+ "AND type IN ('LOAN_PRINCIPAL', 'LOAN_INTEREST') ORDER BY type", String.class, loan.getId()))
			.containsExactly("LOAN_INTEREST:4000", "LOAN_PRINCIPAL:100000");
		assertThat(card(loan).paidOff()).isTrue();
	}

	/** 第 13 期起利率 2.45%、銀行改扣 5,200：利率與扣款兩個版本一起寫。 */
	@Test
	void rateVersionWithPayment() {
		Loan loan = loans.create(credit("2.15", "5133"));

		loans.addRate(loan.getId(), new RateVersionForm(LocalDate.of(2026, 10, 1), new BigDecimal("2.45"),
				new BigDecimal("5200")));

		assertThat(jdbc.queryForList("SELECT CONCAT(kind, ':', effective_date) FROM rate_setting WHERE loan_id = ? "
				+ "AND effective_date = '2026-10-01' ORDER BY kind", String.class, loan.getId()))
			.containsExactly("LOAN_ANNUAL_RATE:2026-10-01", "LOAN_MONTHLY_PAYMENT:2026-10-01");
	}

	// ---------- 卡片（spec 4.8a-5、4.8a-8） ----------

	/** 到期要還 ＝ 10 萬 ＋ ceil(10 萬 × 4% × 365 天 ÷ 365) ＝ 104,000；還沒付的應計利息算進「賺贏利息」的成本。 */
	@Test
	void bulletCard() {
		Loan loan = loans.create(bullet(List.of(), null));
		LocalDate today = LocalDate.now(clock);

		LoanCard c = card(loan);
		assertThat(c.repaymentLabel()).isEqualTo("到期一次還本金＋利息");
		assertThat(c.dueDate()).isEqualTo(LocalDate.of(2027, 7, 28));
		assertThat(c.daysToDue()).isEqualTo((int) java.time.temporal.ChronoUnit.DAYS.between(today,
				LocalDate.of(2027, 7, 28)));
		assertThat(c.amountAtDue()).isEqualByComparingTo("104000");
		assertThat(c.accruedInterest()).isPositive();
		assertThat(c.paidInterest()).isZero();
		assertThat(c.netGain()).isEqualByComparingTo(c.accruedInterest().negate());
		assertThat(c.dueSoon()).isFalse();
	}

	/** 過了到期日還沒還：顯示已到期幾天，提醒不能消失。 */
	@Test
	void overdueBulletKeepsReminder() {
		LocalDate today = LocalDate.now(clock);
		Loan loan = loans.create(withDue(withStart(bullet(List.of(), null), today.minusMonths(6)), today.minusDays(5)));

		LoanCard c = card(loan);
		assertThat(c.daysToDue()).isEqualTo(-5);
		assertThat(c.dueSoon()).isTrue();
		assertThat(overview.view(null).dueReminders()).singleElement()
			.satisfies(r -> assertThat(r.daysToDue()).isEqualTo(-5));
	}

	@Test
	void monthlyCard() {
		LoanCard c = card(loans.create(credit("2.15", "5133")));

		assertThat(c.repaymentLabel()).isEqualTo("每月固定還款");
		assertThat(c.monthlyPayment()).isEqualByComparingTo("5133");
		assertThat(c.dueSoon()).isFalse();
	}

	// ---------- 審查修正 ----------

	/** 審查 I-1：利率調高到 30%、沒填新扣款：5,133 比當期利息少 → 說明訊息，不是錯誤頁。 */
	@Test
	void higherRateWithoutPaymentExplained() {
		Loan loan = loans.create(credit("2.15", "5133"));

		assertThatThrownBy(() -> loans.addRate(loan.getId(), new RateVersionForm(LocalDate.of(2026, 4, 1),
				new BigDecimal("30"))))
			.isInstanceOf(LoanValidationException.class)
			.hasMessageStartingWith("每月扣款 5133 元比當期利息");
	}

	/** 審查 I-2：股票質押但每月固定還款：再動撥會讓攤還表算錯 → 擋下。 */
	@Test
	void monthlyFixedCannotRedraw() {
		LoanForm f = credit("2.15", "5133");
		Loan loan = loans.create(new LoanForm(f.name(), LoanType.PLEDGE, f.bank(), f.origin(), f.originalAmount(),
				f.startDate(), f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(),
				f.payDay(), f.remainingPeriods(), f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(),
				f.fee(), f.collateral(), f.warnPercent(), f.callPercent()));

		assertThatThrownBy(() -> loans.redraw(loan.getId(), new PrincipalForm(LocalDate.now(clock),
				new BigDecimal("10000"), bank))).hasMessage("每月固定還款不能再動撥");
	}

	/** 審查 M-3：過期還沒還，到期要還 ＝ 本金 ＋ 算到今天的利息（不是只算到到期日）。 */
	@Test
	void overdueAmountIncludesInterestToToday() {
		LocalDate today = LocalDate.now(clock);
		Loan loan = loans.create(withDue(withStart(bullet(List.of(), null), today.minusMonths(6)), today.minusDays(5)));

		LoanCard c = card(loan);
		assertThat(c.amountAtDue()).isEqualByComparingTo(new BigDecimal("100000").add(c.accruedInterest()));
	}

	/** DR-051：到期日要晚於撥款日（等於也不行）。 */
	@Test
	void dueDateMustBeAfterStartDate() {
		LocalDate today = LocalDate.now(clock);
		assertThatThrownBy(() -> loans.create(dueBullet(today.minusDays(10), today.minusDays(10))))
			.hasMessage("到期日要晚於撥款日");
	}

	/** 到期日已過只提醒、不擋存檔。 */
	@Test
	void pastDueDateOnlyWarns() {
		LocalDate today = LocalDate.now(clock);
		LoanForm form = dueBullet(today.minusDays(100), today.minusDays(1));
		assertThat(loans.dueWarning(form)).isEqualTo("到期日 " + today.minusDays(1)
				+ " 已經過了。如果已經還清，這筆就不用記；如果有展延，請填展延後的到期日。");
		assertThat(loans.create(form).getId()).isNotNull();
		assertThat(loans.dueWarning(dueBullet(today.minusDays(100), today.plusDays(30)))).isNull();
	}

	private LoanForm dueBullet(LocalDate start, LocalDate due) {
		return withDue(withStart(bullet(List.of(), null), start), due);
	}

	@Test
	void otherUsersLoanNotFound() {
		Loan loan = loans.create(bullet(List.of(), null));

		loginAs("rp-ben");
		assertThatThrownBy(() -> loans.repayDefaults(loan.getId())).isInstanceOf(NotFoundException.class);
	}

	// ---------- 表單 ----------

	private LoanForm credit(String ratePercent, String payment) {
		return new LoanForm("土銀信貸", LoanType.CREDIT, "土地銀行", LoanOrigin.NEW, null, LocalDate.of(2026, 2, 1),
				new BigDecimal("400000"), new BigDecimal(ratePercent), bank, bank, 1, 84, null, null,
				RepaymentMethod.MONTHLY_FIXED, payment == null ? null : new BigDecimal(payment), null, List.of(), null,
				null);
	}

	private LoanForm bullet(List<CollateralPick> collateral, BigDecimal fee) {
		return new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, LocalDate.of(2026, 7, 28),
				new BigDecimal("100000"), new BigDecimal("4"), bank, bank, null, null, null, LocalDate.of(2027, 7, 28),
				RepaymentMethod.BULLET, null, fee, collateral, null, null);
	}

	private static LoanForm withPeriods(LoanForm f, Integer periods) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), periods,
				f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(), f.collateral(),
				f.warnPercent(), f.callPercent());
	}

	private static LoanForm withStart(LoanForm f, LocalDate start) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), start, f.startPrincipal(),
				f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), f.remainingPeriods(),
				f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(), f.collateral(),
				f.warnPercent(), f.callPercent());
	}

	private static LoanForm withDue(LoanForm f, LocalDate due) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				f.remainingPeriods(), f.firstPeriod(), due, f.repaymentMethod(), f.monthlyPayment(), f.fee(),
				f.collateral(), f.warnPercent(), f.callPercent());
	}

	private void holding(long shares) {
		trades.create(new TradeForm(TradeType.OPENING, LocalDate.of(2026, 7, 1), "00830", null, shares,
				new BigDecimal("78.95"), null, null, null, List.of()));
	}

	private LoanCard card(Loan loan) {
		return loans.fundingView().loans().stream().filter(c -> c.id().equals(loan.getId())).findFirst().orElseThrow();
	}

	private void loginAs(String username) {
		UserDetails details = userDetailsService.loadUserByUsername(username);
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	private void createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
	}

}
