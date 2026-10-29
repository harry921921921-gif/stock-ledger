package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import com.sideproject.stockledger.dto.AllocationLine;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.OpeningForm;
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

/**
 * 引導的「預覽」只驗證、不存（設計文件 2026-10-25-onboarding 7a-1）：每個 validate 丟的訊息和存檔時一模一樣，
 * 合法時資料庫筆數不變。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ValidateOnlyTest {

	@Autowired
	private CashAccountService accounts;

	@Autowired
	private CashMovementService movements;

	@Autowired
	private LoanService loans;

	@Autowired
	private TradeService trades;

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

	private LocalDate today;

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
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0056', '元大高股息', "
				+ "'ETF', 4, NOW())");
		if (!users.existsByUsername("vo-amy")) {
			users.save(AppUser.create("vo-amy", encoder.encode("password123"), "vo-amy", Role.USER,
					LocalDateTime.now(clock)));
		}
		UserDetails details = userDetailsService.loadUserByUsername("vo-amy");
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
		today = LocalDate.now(clock);
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	// ---------- 帳戶 ----------

	@Test
	void accountValidateDoesNotSave() {
		accounts.validate(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券"));

		assertThat(count("cash_account")).isZero();
	}

	@Test
	void accountValidateRejectsDuplicateNameLikeCreate() {
		accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券"));
		CashAccountForm again = new CashAccountForm("玉山交割戶", CashAccountType.BANK_DEMAND, "玉山銀行");

		assertSameMessage(() -> accounts.validate(again), () -> accounts.create(again), "帳戶名稱已存在");
		assertThat(count("cash_account")).isEqualTo(1);
	}

	// ---------- 初始現金餘額 ----------

	@Test
	void openingValidateDoesNotSave() {
		movements.validateOpening(new OpeningForm(today, new BigDecimal("50000")));

		assertThat(count("cash_movement")).isZero();
	}

	@Test
	void openingValidateRejectsLikeSetOpening() {
		Long account = settlement();
		OpeningForm noAmount = new OpeningForm(today, null);
		OpeningForm future = new OpeningForm(today.plusDays(1), new BigDecimal("50000"));
		OpeningForm negative = new OpeningForm(today, new BigDecimal("-1"));

		assertSameMessage(() -> movements.validateOpening(noAmount), () -> movements.setOpening(account, noAmount),
				"請輸入金額");
		assertSameMessage(() -> movements.validateOpening(future), () -> movements.setOpening(account, future),
				"日期不能晚於今天");
		assertSameMessage(() -> movements.validateOpening(negative), () -> movements.setOpening(account, negative),
				"初始現金餘額不能是負數");
		assertThat(count("cash_movement")).isZero();
	}

	// ---------- 借款 ----------

	@Test
	void loanValidateDoesNotSave() {
		loans.validate(credit(settlement(), new BigDecimal("1367")));

		assertThat(count("loan")).isZero();
		assertThat(count("cash_movement")).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_setting WHERE loan_id IS NOT NULL", Integer.class))
			.isZero();
	}

	@Test
	void loanValidateRejectsMissingFieldLikeCreate() {
		Long account = settlement();
		LoanForm noBank = new LoanForm("土銀信貸", LoanType.CREDIT, " ", LoanOrigin.NEW, null, today.minusMonths(1),
				new BigDecimal("100000"), new BigDecimal("4"), null, account, 1, 84, null, null,
				RepaymentMethod.MONTHLY_FIXED, new BigDecimal("1367"), null, List.of(), null, null);
		LoanForm noDue = new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, today.minusMonths(1),
				new BigDecimal("100000"), new BigDecimal("2.5"), null, account, null, null, null, null,
				RepaymentMethod.BULLET, null, null, List.of(), null, null);

		assertSameMessage(() -> loans.validate(noBank), () -> loans.create(noBank), "請輸入銀行");
		assertSameMessage(() -> loans.validate(noDue), () -> loans.create(noDue), "請填到期日");
		assertThat(count("loan")).isZero();
	}

	/** 銀行扣款比利息少：create 原本存檔後才擋（攤還表），validate 不存也要擋下，訊息一樣。 */
	@Test
	void loanValidateRejectsPaymentBelowInterestLikeCreate() {
		LoanForm tooSmall = credit(settlement(), new BigDecimal("100"));

		Throwable fromValidate = catchThrowable(() -> loans.validate(tooSmall));
		Throwable fromCreate = catchThrowable(() -> loans.create(tooSmall));

		assertThat(fromValidate).isInstanceOf(LoanValidationException.class).hasMessageContaining("比當期利息");
		assertThat(fromCreate).isInstanceOf(LoanValidationException.class).hasMessage(fromValidate.getMessage());
		assertThat(count("loan")).isZero();
	}

	/** 合約月付金和銀行扣款差超過 1 元的提醒：還沒存的表單和存好的借款算出一樣的文字。 */
	@Test
	void paymentWarningFromFormMatchesSavedLoan() {
		LoanForm form = credit(settlement(), new BigDecimal("1500"));

		String fromForm = loans.paymentWarning(form);
		Loan saved = loans.create(form);

		assertThat(fromForm).isNotNull().contains("和銀行扣款 1,500");
		assertThat(fromForm).isEqualTo(loans.paymentWarning(saved.getId()));
		assertThat(loans.paymentWarning(credit(settlement2(), new BigDecimal("1367")))).isNull();
	}

	// ---------- 股票 ----------

	@Test
	void tradeValidateDoesNotSave() {
		trades.validate(buy(settlement(), 1000L, new BigDecimal("30"), List.of()));

		assertThat(count("trade")).isZero();
		assertThat(count("cash_movement")).isZero();
		assertThat(count("source_allocation")).isZero();
	}

	@Test
	void tradeValidateRejectsMissingPriceLikeCreate() {
		TradeForm noPrice = buy(settlement(), 1000L, null, List.of());

		assertSameMessage(() -> trades.validate(noPrice), () -> trades.create(noPrice), "請輸入成交價");
		assertThat(count("trade")).isZero();
	}

	@Test
	void tradeValidateRejectsLateOpeningLikeCreate() {
		trades.create(buy(settlement(), 1000L, new BigDecimal("30"), List.of()));
		TradeForm opening = new TradeForm(TradeType.OPENING, today, "0056", null, 2000L, new BigDecimal("28"), null,
				null, null, List.of());

		assertSameMessage(() -> trades.validate(opening), () -> trades.create(opening), "期初持股要早於這檔股票的所有交易");
		assertThat(count("trade")).isEqualTo(1);
	}

	@Test
	void tradeValidateRejectsLoanOverUndeployedLikeCreate() {
		Long account = settlement();
		Loan loan = loans.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null,
				today.minusMonths(1), new BigDecimal("100000"), new BigDecimal("2.5"), null, account, null, null, null,
				today.plusYears(1), RepaymentMethod.BULLET, null, null, List.of(), null, null));
		TradeForm tooMuch = buy(account, 10000L, new BigDecimal("30"),
				List.of(new AllocationLine(loan.getId(), new BigDecimal("200000"))));

		assertSameMessage(() -> trades.validate(tooMuch), () -> trades.create(tooMuch),
				"「玉山質押」出的錢超過它的尚未投入（差 100,000 元）");
		assertThat(count("trade")).isZero();
	}

	// ---------- 工具 ----------

	private void assertSameMessage(Runnable validate, Runnable save, String expected) {
		Throwable fromValidate = catchThrowable(validate::run);
		Throwable fromSave = catchThrowable(save::run);
		assertThat(fromValidate).as("validate").isNotNull().hasMessage(expected);
		assertThat(fromSave).as("save").isNotNull().hasMessage(expected);
		assertThat(fromValidate.getClass()).isEqualTo(fromSave.getClass());
	}

	private LoanForm credit(Long account, BigDecimal monthlyPayment) {
		return new LoanForm("土銀信貸", LoanType.CREDIT, "土地銀行", LoanOrigin.NEW, null, today.minusDays(3),
				new BigDecimal("100000"), new BigDecimal("4"), null, account, 1, 84, null, null,
				RepaymentMethod.MONTHLY_FIXED, monthlyPayment, null, List.of(), null, null);
	}

	private TradeForm buy(Long account, Long shares, BigDecimal price, List<AllocationLine> allocations) {
		return new TradeForm(TradeType.BUY, today, "0056", account, shares, price, null, null, null, allocations);
	}

	private Long settlement() {
		return accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
	}

	private Long settlement2() {
		return accounts.create(new CashAccountForm("元大交割戶", CashAccountType.SETTLEMENT, "元大證券")).getId();
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

}
