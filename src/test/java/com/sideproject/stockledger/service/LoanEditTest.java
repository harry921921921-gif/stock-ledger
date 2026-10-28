package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
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
import com.sideproject.stockledger.dto.LoanEditResult;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.LoanMovementForm;
import com.sideproject.stockledger.dto.PrincipalForm;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.RateSetting;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.LoanRepository;
import com.sideproject.stockledger.repository.RateSettingRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 借款的每個欄位都能改（spec 4.8a-10、DR-051）：撥款、開辦費、每期紀錄跟著新合約重新對照。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class LoanEditTest {

	@Autowired
	private LoanService loans;

	@Autowired
	private CashMovementService cashMovements;

	@Autowired
	private LoanPostingService postingService;

	@Autowired
	private LoanFactsReader factsReader;

	@Autowired
	private CashAccountService accounts;

	@Autowired
	private TradeService trades;

	@Autowired
	private LoanRepository loanRepository;

	@Autowired
	private CashMovementRepository movementRepository;

	@Autowired
	private RateSettingRepository rateRepository;

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
	private Long userId;
	private Long bank;

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
		createIfMissing("le-amy");
		loginAs("le-amy");
		userId = users.findByUsername("le-amy").orElseThrow().getId();
		today = LocalDate.now(clock);
		bank = accounts.create(new CashAccountForm("土銀帳戶", CashAccountType.BANK_DEMAND, "土地銀行")).getId();
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void editNameOnlyTouchesNoMovement() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
		int revisionsBefore = count("cash_movement_revision");
		int movementsBefore = count("cash_movement");
		int ratesBefore = count("rate_setting");
		LoanEditResult r = loans.edit(loan.getId(), withName(loans.form(loan.getId()), "土銀信貸（改名）"), false);
		assertThat(r.loan().getName()).isEqualTo("土銀信貸（改名）");
		assertThat(r.kept()).isEmpty();
		assertThat(count("cash_movement_revision")).isEqualTo(revisionsBefore);
		assertThat(count("cash_movement")).isEqualTo(movementsBefore);
		assertThat(count("rate_setting")).isEqualTo(ratesBefore);
	}

	/** 修改畫面對新撥款不送第一期月份：存的第一期和推算的不同時，只改名稱不能觸發重算。 */
	@Test
	void nameOnlyEditWithoutFirstPeriodKeepsStoredFirstPeriod() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
		jdbc.update("UPDATE loan SET first_period = ? WHERE id = ?",
				java.time.YearMonth.from(today).plusMonths(1).atDay(1), loan.getId());
		Object postingFrom = jdbc.queryForObject("SELECT posting_from FROM loan WHERE id = ?", Object.class, loan.getId());
		int revisionsBefore = count("cash_movement_revision");
		int movementsBefore = count("cash_movement");
		int ratesBefore = count("rate_setting");
		LoanForm f = loans.form(loan.getId());
		LoanForm noFirst = new LoanForm("改名", f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				f.remainingPeriods(), null, f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(), List.of(),
				null, null);

		loans.edit(loan.getId(), noFirst, false);

		assertThat(count("cash_movement_revision")).isEqualTo(revisionsBefore);
		assertThat(count("cash_movement")).isEqualTo(movementsBefore);
		assertThat(count("rate_setting")).isEqualTo(ratesBefore);
		assertThat(jdbc.queryForObject("SELECT posting_from FROM loan WHERE id = ?", Object.class, loan.getId()))
			.isEqualTo(postingFrom);
		assertThat(jdbc.queryForObject("SELECT name FROM loan WHERE id = ?", String.class, loan.getId())).isEqualTo("改名");
		assertThat(jdbc.queryForObject("SELECT first_period FROM loan WHERE id = ?", java.sql.Date.class, loan.getId())
			.toLocalDate()).isEqualTo(java.time.YearMonth.from(today).plusMonths(1).atDay(1));
	}

	@Test
	void editAmountFollowsDisbursementAndReposts() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));          // 400000、2.15%、84 期、月付 5133
		loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "300000", "3850"), false);
		assertThat(amountOf(loan.getId(), CashMovementType.LOAN_DISBURSEMENT)).isEqualByComparingTo("300000");
		assertThat(firstInterest(loan.getId())).isEqualByComparingTo(
				new BigDecimal("300000").multiply(new BigDecimal("0.0215")).divide(BigDecimal.valueOf(12), 0, RoundingMode.UP));
		assertThat(revisionReasons(loan.getId())).containsOnly(LoanService.EDIT_REASON);
	}

	@Test
	void keepsUserEditedPeriods() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
		CashMovement first = firstMovement(loan.getId(), CashMovementType.LOAN_INTEREST);
		cashMovements.editLoanMovement(first.getId(), new LoanMovementForm(first.getMovementDate(), new BigDecimal("700")), "照銀行");
		LoanEditResult r = loans.edit(loan.getId(), withRate(loans.form(loan.getId()), "3"), false);
		assertThat(amountOf(first.getId())).isEqualByComparingTo("700");
		assertThat(r.kept()).extracting(LoanEditResult.KeptPeriod::movementId).containsExactly(first.getId());
		assertThat(r.payAccountChanged()).isFalse();
	}

	@Test
	void changeMethodToBulletVoidsAutoPeriods() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
		loans.edit(loan.getId(), withMethod(loans.form(loan.getId()), RepaymentMethod.BULLET, today.plusYears(1)), false);
		assertThat(activeCount(loan.getId(), CashMovementType.LOAN_PRINCIPAL)).isZero();
		assertThat(activeCount(loan.getId(), CashMovementType.LOAN_INTEREST)).isZero();
		assertThat(postingService.postDue(loanRepository.findByIdAndUserId(loan.getId(), userId).orElseThrow(), today)).isZero();
	}

	@Test
	void correctingRateUsesNewRateFromStart() {
		Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(2)));   // 4%
		loans.edit(loan.getId(), withRate(loans.form(loan.getId()), "4.25"), false);
		assertThat(loans.currentRate(loanRepository.findByIdAndUserId(loan.getId(), userId).orElseThrow(), loan.getStartDate()))
			.isEqualByComparingTo("0.0425");
	}

	// ---------- 補充 ----------

	/** 同一個生效日有好幾個版本時只留 id 最大的（修改原始利率是在同一天新增一個版本，DR-051）。 */
	@Test
	void factsKeepLatestVersionPerEffectiveDate() {
		Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(2)));
		rateRepository.save(RateSetting.loanRate(userId, loan.getId(), new BigDecimal("0.05"), loan.getStartDate(),
				LocalDateTime.now(clock)));

		LoanFacts f = factsReader.read(loanRepository.findByIdAndUserId(loan.getId(), userId).orElseThrow());

		assertThat(f.rates()).singleElement()
			.satisfies(r -> assertThat(r.annualRate()).isEqualByComparingTo("0.05"));
	}

	/** 每月固定還款 → 到期一次 → 再改回來：系統重算作廢的期數可以再記（不是使用者作廢的，不受 DR-040 限制）。 */
	@Test
	void methodRoundTripRestoresPeriods() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
		int periods = activeCount(loan.getId(), CashMovementType.LOAN_INTEREST);
		LoanForm original = loans.form(loan.getId());

		loans.edit(loan.getId(), withMethod(original, RepaymentMethod.BULLET, today.plusYears(1)), false);
		loans.edit(loan.getId(), original, false);

		assertThat(periods).isPositive();
		assertThat(activeCount(loan.getId(), CashMovementType.LOAN_INTEREST)).isEqualTo(periods);
		assertThat(activeCount(loan.getId(), CashMovementType.LOAN_PRINCIPAL)).isEqualTo(periods);
	}

	/** 每月繳息：改利率後每一期利息照新利率按日重算（計息起點是上一期應有的繳息日）。 */
	@Test
	void interestOnlyRateCorrectionRecomputesEachPeriod() {
		LocalDate start = today.minusMonths(3);
		Loan loan = loans.create(interestOnly("玉山質押", start));
		List<CashMovement> before = active(loan.getId(), CashMovementType.LOAN_INTEREST);

		loans.edit(loan.getId(), withRate(loans.form(loan.getId()), "6"), false);

		List<CashMovement> after = active(loan.getId(), CashMovementType.LOAN_INTEREST);
		assertThat(after).hasSameSizeAs(before).isNotEmpty();
		LocalDate from = start;
		for (CashMovement m : after) {
			BigDecimal expected = new BigDecimal("100000").multiply(new BigDecimal("0.06"))
				.multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(from, m.getMovementDate())))
				.divide(BigDecimal.valueOf(365), 0, RoundingMode.UP);
			assertThat(m.getAmount()).isEqualByComparingTo(expected);
			from = m.getMovementDate();
		}
	}

	/** 改扣款日：自動記的期數改到新的扣款日，同一期只動日期。 */
	@Test
	void payDayChangeRedatesPeriods() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(4)));
		LoanForm f = loans.form(loan.getId());
		LoanForm moved = new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), 2,
				f.remainingPeriods(), f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(),
				f.collateral(), f.warnPercent(), f.callPercent());

		loans.edit(loan.getId(), moved, false);

		assertThat(active(loan.getId(), CashMovementType.LOAN_INTEREST))
			.allSatisfy(m -> assertThat(m.getMovementDate().getDayOfMonth()).isEqualTo(2));
	}

	// ---------- Fix round 1 ----------

	/** 舊借款（DR-037 時改過扣款日，記帳起點在當月）只改名稱：一筆紀錄都不動。 */
	@Test
	void legacyPayDayChangeThenRenameTouchesNothing() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(4)));
		jdbc.update("UPDATE loan SET pay_day = 15, posting_from = ? WHERE id = ?", YearMonth.from(today).atDay(1),
				loan.getId());
		List<String> before = snapshot(loan.getId());
		int revisionsBefore = count("cash_movement_revision");

		LoanEditResult r = loans.edit(loan.getId(), withName(loans.form(loan.getId()), "土銀信貸（改名）"), false);

		assertThat(r.kept()).isEmpty();
		assertThat(snapshot(loan.getId())).isEqualTo(before);
		assertThat(count("cash_movement_revision")).isEqualTo(revisionsBefore);
		assertThat(loanRepository.findByIdAndUserId(loan.getId(), userId).orElseThrow().getPostingFrom())
			.isEqualTo(YearMonth.from(today));
	}

	/** 每月繳息：手改過日期的那一期保留，下一期的計息起點是它實際的日期。 */
	@Test
	void interestOnlyKeptPeriodDateDrivesNextPeriod() {
		Loan loan = loans.create(interestOnly("玉山質押", today.minusMonths(4)));
		List<CashMovement> periods = active(loan.getId(), CashMovementType.LOAN_INTEREST);
		assertThat(periods).hasSizeGreaterThanOrEqualTo(2);
		CashMovement first = periods.get(0);
		LocalDate bankDate = first.getMovementDate().minusDays(3);
		cashMovements.editLoanMovement(first.getId(), new LoanMovementForm(bankDate, new BigDecimal("999")), "照銀行");

		LoanEditResult r = loans.edit(loan.getId(), withRate(loans.form(loan.getId()), "6"), false);

		assertThat(amountOf(first.getId())).isEqualByComparingTo("999");
		assertThat(r.kept()).extracting(LoanEditResult.KeptPeriod::movementId).containsExactly(first.getId());
		CashMovement second = active(loan.getId(), CashMovementType.LOAN_INTEREST).stream()
			.filter(m -> m.getPeriod().equals(periods.get(1).getPeriod()))
			.findFirst()
			.orElseThrow();
		assertThat(second.getAmount()).isEqualByComparingTo(new BigDecimal("100000").multiply(new BigDecimal("0.06"))
			.multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(bankDate, second.getMovementDate())))
			.divide(BigDecimal.valueOf(365), 0, RoundingMode.UP));
	}

	/** 使用者作廢的那一期：修改借款後仍然作廢、不補記（DR-040）。 */
	@Test
	void userVoidedPeriodStaysVoided() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
		int interests = activeCount(loan.getId(), CashMovementType.LOAN_INTEREST);
		CashMovement first = firstMovement(loan.getId(), CashMovementType.LOAN_INTEREST);
		cashMovements.voidMovement(first.getId(), "銀行沒扣");

		loans.edit(loan.getId(), withRate(loans.form(loan.getId()), "3"), false);

		assertThat(jdbc.queryForObject("SELECT voided FROM cash_movement WHERE id = ?", Boolean.class, first.getId()))
			.isTrue();
		assertThat(jdbc.queryForObject("SELECT period FROM cash_movement WHERE id = ?", LocalDate.class,
				first.getId())).isEqualTo(first.getPeriod().atDay(1));
		assertThat(activeCount(loan.getId(), CashMovementType.LOAN_INTEREST)).isEqualTo(interests - 1);
		assertThat(active(loan.getId(), CashMovementType.LOAN_INTEREST))
			.noneMatch(m -> m.getPeriod().equals(first.getPeriod()));
	}

	/** 換往來帳戶：撥款、開辦費、沒手改的期數跟著換；手改過的期數留在原本的帳戶。 */
	@Test
	void payAccountChangeMovesEverythingButKept() {
		Loan loan = loans.create(withFee(monthly("土銀信貸", today.minusMonths(3)), "3000"));
		CashMovement kept = firstMovement(loan.getId(), CashMovementType.LOAN_INTEREST);
		cashMovements.editLoanMovement(kept.getId(), new LoanMovementForm(kept.getMovementDate(), new BigDecimal("700")),
				"照銀行");
		Long other = accounts.create(new CashAccountForm("台銀帳戶", CashAccountType.BANK_DEMAND, "台灣銀行")).getId();

		loans.edit(loan.getId(), withPayAccount(loans.form(loan.getId()), other), false);

		assertThat(accountOf(active(loan.getId(), CashMovementType.LOAN_DISBURSEMENT).get(0).getId())).isEqualTo(other);
		assertThat(accountOf(active(loan.getId(), CashMovementType.LOAN_FEE).get(0).getId())).isEqualTo(other);
		assertThat(accountOf(kept.getId())).isEqualTo(bank);
		List<CashMovement> periods = new java.util.ArrayList<>(active(loan.getId(), CashMovementType.LOAN_INTEREST));
		periods.addAll(active(loan.getId(), CashMovementType.LOAN_PRINCIPAL));
		assertThat(periods).filteredOn(m -> !m.getId().equals(kept.getId())).isNotEmpty()
			.allSatisfy(m -> assertThat(accountOf(m.getId())).isEqualTo(other));
	}

	/** 手改過的期數金額、日期都和新合約一樣，但留在原本的帳戶：一樣列出請使用者確認。 */
	@Test
	void keptPeriodLeftInOldAccountIsListed() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
		CashMovement kept = firstMovement(loan.getId(), CashMovementType.LOAN_INTEREST);
		BigDecimal amount = amountOf(kept.getId()).setScale(0, RoundingMode.UNNECESSARY);
		cashMovements.editLoanMovement(kept.getId(), new LoanMovementForm(kept.getMovementDate(), new BigDecimal("700")),
				"照銀行");
		cashMovements.editLoanMovement(kept.getId(), new LoanMovementForm(kept.getMovementDate(), amount), "改回來");
		Long other = accounts.create(new CashAccountForm("台銀帳戶", CashAccountType.BANK_DEMAND, "台灣銀行")).getId();

		LoanEditResult r = loans.edit(loan.getId(), withPayAccount(loans.form(loan.getId()), other), false);

		assertThat(accountOf(kept.getId())).isEqualTo(bank);
		assertThat(r.kept()).extracting(LoanEditResult.KeptPeriod::movementId).containsExactly(kept.getId());
		assertThat(r.payAccountChanged()).isTrue();
	}

	/** 開辦費：新增、跟著改（同一筆）、清空就作廢。 */
	@Test
	void feeAddFollowVoid() {
		Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(2)));

		loans.edit(loan.getId(), withFee(loans.form(loan.getId()), "2000"), false);
		assertThat(amountOf(loan.getId(), CashMovementType.LOAN_FEE)).isEqualByComparingTo("2000");
		Long feeId = active(loan.getId(), CashMovementType.LOAN_FEE).get(0).getId();

		loans.edit(loan.getId(), withFee(loans.form(loan.getId()), "2500"), false);
		assertThat(active(loan.getId(), CashMovementType.LOAN_FEE)).singleElement()
			.satisfies(m -> assertThat(m.getId()).isEqualTo(feeId));
		assertThat(amountOf(feeId)).isEqualByComparingTo("2500");

		loans.edit(loan.getId(), withFee(loans.form(loan.getId()), null), false);
		assertThat(activeCount(loan.getId(), CashMovementType.LOAN_FEE)).isZero();
		assertThat(jdbc.queryForList("SELECT reason FROM cash_movement_revision WHERE cash_movement_id = ? "
				+ "AND action = 'VOID'", String.class, feeId)).containsExactly(LoanService.EDIT_REASON);
	}

	/** 質押再動撥：撥款日改到再動撥之後，再改一次金額，動撥那筆不動（撥款那筆用 id 認，不是日期）。 */
	@Test
	void redrawUntouchedAfterStartMovesPastIt() {
		Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(3)));
		Long original = active(loan.getId(), CashMovementType.LOAN_DISBURSEMENT).get(0).getId();
		CashMovement redraw = loans.redraw(loan.getId(), new PrincipalForm(today.minusMonths(1),
				new BigDecimal("20000"), bank));

		loans.edit(loan.getId(), withStart(loans.form(loan.getId()), today.minusDays(10)), false);
		loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "90000", null), false);

		assertThat(amountOf(redraw.getId())).isEqualByComparingTo("20000");
		assertThat(dateOf(redraw.getId())).isEqualTo(today.minusMonths(1));
		assertThat(amountOf(original)).isEqualByComparingTo("90000");
		assertThat(dateOf(original)).isEqualTo(today.minusDays(10));
	}

	/** 攤還表把所有撥款當成第 1 期起的本金：有再動撥的質押不能改成每月固定還款（不然整張重算）。 */
	@Test
	void redrawnPledgeCannotSwitchToMonthlyFixed() {
		Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(3)));
		loans.redraw(loan.getId(), new PrincipalForm(today.minusMonths(1), new BigDecimal("20000"), bank));
		LoanForm f = loans.form(loan.getId());
		LoanForm toMonthly = new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), 1, 24,
				YearMonth.from(f.startDate()).plusMonths(1), null, RepaymentMethod.MONTHLY_FIXED,
				new BigDecimal("4500"), f.fee(), f.collateral(), f.warnPercent(), f.callPercent());
		List<String> before = snapshot(loan.getId());

		assertThatThrownBy(() -> loans.edit(loan.getId(), toMonthly, false))
			.isInstanceOf(LoanValidationException.class)
			.hasMessage("有再動撥的借款不能改成每月固定還款");
		assertThat(snapshot(loan.getId())).isEqualTo(before);
		assertThat(loanRepository.findByIdAndUserId(loan.getId(), userId).orElseThrow().getRepaymentMethod())
			.isEqualTo(RepaymentMethod.BULLET);
	}

	/** 每月固定還款用不到到期日：留著過去（甚至早於撥款日）的到期日照樣存，也不提醒到期日已過。 */
	@Test
	void monthlyIgnoresLeftoverDueDate() {
		Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
		LoanForm f = loans.form(loan.getId());
		LoanForm leftover = new LoanForm("土銀信貸（改）", f.type(), f.bank(), f.origin(), f.originalAmount(),
				f.startDate(), f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(),
				f.payDay(), f.remainingPeriods(), f.firstPeriod(), f.startDate().minusDays(5), f.repaymentMethod(),
				f.monthlyPayment(), f.fee(), f.collateral(), f.warnPercent(), f.callPercent());

		loans.edit(loan.getId(), leftover, false);

		assertThat(loanRepository.findByIdAndUserId(loan.getId(), userId).orElseThrow().getName())
			.isEqualTo("土銀信貸（改）");
		assertThat(loans.dueWarning(leftover)).isNull();
	}

	@Test
	void shortfallWithoutConfirmChangesNothing() {
		Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(2)));             // 100000
		buyWithLoan(loan, today.minusMonths(1), "0050", 1000, "73.5", "73500");
		List<String> allocationsBefore = allocationRows();
		int tradeRevisionsBefore = count("trade_revision");
		assertThatThrownBy(() -> loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "50000", null), false))
			.isInstanceOfSatisfying(LoanShortfallException.class, ex -> {
				assertThat(ex.getMessage()).isEqualTo("借款金額小於已經用它買股票的金額，差 23,500 元");
				assertThat(ex.cuts()).singleElement().satisfies(c -> {
					assertThat(c.from()).isEqualByComparingTo("73500");
					assertThat(c.to()).isEqualByComparingTo("50000");
				});
			});
		assertThat(amountOf(loan.getId(), CashMovementType.LOAN_DISBURSEMENT)).isEqualByComparingTo("100000");
		assertThat(allocationRows()).isEqualTo(allocationsBefore);
		assertThat(count("trade_revision")).isEqualTo(tradeRevisionsBefore);
	}

	/** 兩筆買進中間有再動撥：只調剛好需要的 10,000（不是兩筆都調滿的 12,000）。 */
	@Test
	void redrawBetweenBuysCutsOnlyTheGap() {
		Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(3)));             // 100000
		Long a = buyWithLoan(loan, today.minusDays(80), "0050", 1000, "52", "52000");
		loans.redraw(loan.getId(), new PrincipalForm(today.minusDays(60), new BigDecimal("5000"), bank, null));
		Long b = buyWithLoan(loan, today.minusDays(40), "0050", 200, "65", "13000");
		// 改成 50000：50000 → 買 A -2000 → 再動撥 3000 → 買 B -10000
		assertThatThrownBy(() -> loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "50000", null), false))
			.isInstanceOfSatisfying(LoanShortfallException.class,
					ex -> assertThat(ex.getMessage()).isEqualTo("借款金額小於已經用它買股票的金額，差 10,000 元"));
		loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "50000", null), true);
		assertThat(loanAllocation(a, loan)).isEqualByComparingTo("50000");
		assertThat(loanAllocation(b, loan)).isEqualByComparingTo("5000");
	}

	/** 兩筆買進中間有再動撥、還本：一輪不夠時再調，確定後所有借款都夠付。 */
	@Test
	void repaymentAndRedrawBetweenBuysStillCovers() {
		Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(3)));             // 100000
		Long a = buyWithLoan(loan, today.minusDays(80), "0050", 1000, "55", "55000");
		loans.redraw(loan.getId(), new PrincipalForm(today.minusDays(60), new BigDecimal("7000"), bank, null));
		loans.repay(loan.getId(), new PrincipalForm(today.minusDays(40), new BigDecimal("5000"), bank, null));
		Long b = buyWithLoan(loan, today.minusDays(20), "0050", 50, "60", "3000");
		// 改成 50000：50000 → 買 A -5000 → 再動撥 2000 → 還本 -3000 → 買 B -6000；借到 52000、用掉 58000，至少要調 6000
		assertThatThrownBy(() -> loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "50000", null), false))
			.isInstanceOfSatisfying(LoanShortfallException.class,
					ex -> assertThat(ex.getMessage()).isEqualTo("借款金額小於已經用它買股票的金額，差 6,000 元"));
		loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "50000", null), true);
		assertThat(loanAllocation(a, loan)).isEqualByComparingTo("50000");
		assertThat(loanAllocation(b, loan)).isEqualByComparingTo("2000");
		trades.checkLoansCovered();
	}

	/** 不夠的是別筆借款：照原本的訊息擋下，不調任何交易。 */
	@Test
	void otherLoansShortfallKeepsOldMessage() {
		Loan edited = loans.create(bulletPledge("玉山質押", today.minusMonths(2)));
		Loan other = loans.create(bulletPledge("台新質押", today.minusMonths(2)));
		buyWithLoan(other, today.minusMonths(1), "0050", 1000, "80", "80000");
		// 資料本來就不一致（例如舊版留下的）：別筆借款的撥款只剩 50000
		jdbc.update("UPDATE cash_movement SET amount = 50000 WHERE loan_id = ? AND type = 'LOAN_DISBURSEMENT'",
				other.getId());
		List<String> allocationsBefore = allocationRows();
		assertThatThrownBy(() -> loans.edit(edited.getId(), withAmount(loans.form(edited.getId()), "90000", null), true))
			.isExactlyInstanceOf(LoanValidationException.class)
			.hasMessage("這樣「台新質押」買股票用掉的錢會超過那時已經借到的錢（差 30,000 元）；請先修改那些交易的資金來源");
		assertThat(allocationRows()).isEqualTo(allocationsBefore);
		assertThat(amountOf(edited.getId(), CashMovementType.LOAN_DISBURSEMENT)).isEqualByComparingTo("100000");
	}

	@Test
	void confirmedShortfallMovesDifferenceToOwnMoney() {
		Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(2)));
		Long older = buyWithLoan(loan, today.minusMonths(1), "0050", 500, "70", "35000");
		Long newer = buyWithLoan(loan, today.minusDays(5), "0050", 500, "76", "38000");
		loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "50000", null), true);
		assertThat(loanAllocation(older, loan)).isEqualByComparingTo("35000");
		assertThat(loanAllocation(newer, loan)).isEqualByComparingTo("15000");
		assertThat(tradeRevisionReasons(newer)).containsExactly(LoanService.EDIT_REASON);
		assertThat(jdbc.queryForMap("SELECT field, old_value, new_value FROM trade_revision WHERE trade_id = ?", newer))
			.containsEntry("field", "allocation")
			.containsEntry("old_value", loan.getId() + ":38000")
			.containsEntry("new_value", loan.getId() + ":15000");
		assertThat(tradeRevisionReasons(older)).isEmpty();
		assertThat(settlementBalanceChangedBy(loan)).isEqualByComparingTo("0");   // 不另外扣帳戶的錢
	}

	private static LoanForm withFee(LoanForm f, String fee) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				f.remainingPeriods(), f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(),
				fee == null ? null : new BigDecimal(fee), f.collateral(), f.warnPercent(), f.callPercent());
	}

	/** 往來帳戶：撥入與扣款同一個（撥入帳戶不填，照新增的規則補成往來帳戶）。 */
	private static LoanForm withPayAccount(LoanForm f, Long account) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), null, account, f.payDay(), f.remainingPeriods(),
				f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(), f.collateral(),
				f.warnPercent(), f.callPercent());
	}

	private static LoanForm withStart(LoanForm f, LocalDate start) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), start, f.startPrincipal(),
				f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), f.remainingPeriods(),
				f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(), f.collateral(),
				f.warnPercent(), f.callPercent());
	}

	private Long accountOf(Long movementId) {
		return jdbc.queryForObject("SELECT account_id FROM cash_movement WHERE id = ?", Long.class, movementId);
	}

	private LocalDate dateOf(Long movementId) {
		return jdbc.queryForObject("SELECT movement_date FROM cash_movement WHERE id = ?", LocalDate.class, movementId);
	}

	/** 這筆借款所有現金紀錄的內容（含作廢的），比較前後是否完全一樣。 */
	private List<String> snapshot(Long loanId) {
		return jdbc.queryForList("SELECT CONCAT(id, '|', type, '|', movement_date, '|', account_id, '|', amount, '|', "
				+ "voided, '|', IFNULL(period, '')) FROM cash_movement WHERE loan_id = ? ORDER BY id", String.class,
				loanId);
	}

	// ---------- 表單 ----------

	/** 土銀信貸：40 萬、2.15%、84 期、銀行每月扣 5,133、每月 1 號扣款。 */
	private LoanForm monthly(String name, LocalDate start) {
		return new LoanForm(name, LoanType.CREDIT, "土地銀行", LoanOrigin.NEW, null, start, new BigDecimal("400000"),
				new BigDecimal("2.15"), bank, bank, 1, 84, null, null, RepaymentMethod.MONTHLY_FIXED,
				new BigDecimal("5133"), null, List.of(), null, null);
	}

	/** 玉山質押：10 萬、4%、到期一次還本金＋利息、一年後到期。 */
	private LoanForm bulletPledge(String name, LocalDate start) {
		return new LoanForm(name, LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, start, new BigDecimal("100000"),
				new BigDecimal("4"), bank, bank, null, null, null, today.plusYears(1), RepaymentMethod.BULLET, null, null,
				List.of(), null, null);
	}

	/** 每月繳息、到期還本：10 萬、4%、每月 10 號繳息、一年後到期。 */
	private LoanForm interestOnly(String name, LocalDate start) {
		return new LoanForm(name, LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, start, new BigDecimal("100000"),
				new BigDecimal("4"), bank, bank, 10, null, null, today.plusYears(1), RepaymentMethod.INTEREST_ONLY, null,
				null, List.of(), null, null);
	}

	private static LoanForm withName(LoanForm f, String name) {
		return new LoanForm(name, f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(), f.startPrincipal(),
				f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), f.remainingPeriods(),
				f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(), f.collateral(),
				f.warnPercent(), f.callPercent());
	}

	private static LoanForm withAmount(LoanForm f, String principal, String payment) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), null, f.startDate(), new BigDecimal(principal),
				f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), f.remainingPeriods(),
				f.firstPeriod(), f.dueDate(), f.repaymentMethod(), payment == null ? null : new BigDecimal(payment),
				f.fee(), f.collateral(), f.warnPercent(), f.callPercent());
	}

	private static LoanForm withRate(LoanForm f, String percent) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), new BigDecimal(percent), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				f.remainingPeriods(), f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(),
				f.collateral(), f.warnPercent(), f.callPercent());
	}

	private static LoanForm withMethod(LoanForm f, RepaymentMethod method, LocalDate due) {
		boolean monthly = method == RepaymentMethod.MONTHLY_FIXED;
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				monthly ? f.remainingPeriods() : null, monthly ? f.firstPeriod() : null, due, method,
				monthly ? f.monthlyPayment() : null, f.fee(), f.collateral(), f.warnPercent(), f.callPercent());
	}

	// ---------- 股票（借款改小、不夠付股票，Task 5） ----------

	/** 用這筆借款出 loanAmount 元買進（手續費自動算，其餘是自有）；回傳交易 id。 */
	private Long buyWithLoan(Loan loan, LocalDate date, String code, long shares, String price, String loanAmount) {
		jdbc.update("INSERT IGNORE INTO stock (code, name, kind, dividends_per_year, updated_at) "
				+ "VALUES (?, '元大台灣50', 'ETF', 2, NOW())", code);
		return trades.create(new TradeForm(TradeType.BUY, date, code, bank, shares, new BigDecimal(price), null, null,
				null, List.of(new AllocationLine(loan.getId(), new BigDecimal(loanAmount))))).getId();
	}

	private BigDecimal loanAllocation(Long tradeId, Loan loan) {
		return jdbc.queryForObject("SELECT amount FROM source_allocation WHERE trade_id = ? AND loan_id = ?",
				BigDecimal.class, tradeId, loan.getId());
	}

	/** 所有資金來源分攤「交易:借款:金額」，比較前後有沒有變。 */
	private List<String> allocationRows() {
		return jdbc.queryForList("SELECT CONCAT(trade_id, ':', loan_id, ':', amount) FROM source_allocation "
				+ "ORDER BY trade_id, loan_id", String.class);
	}

	private List<String> tradeRevisionReasons(Long tradeId) {
		return jdbc.queryForList("SELECT reason FROM trade_revision WHERE trade_id = ? ORDER BY id", String.class,
				tradeId);
	}

	/**
	 * 借款以外的現金異動合計，減掉買進的總成本：買進只記自己那一筆交割，所以是 0；
	 * 借款改小時如果另外扣了帳戶的錢（例如補一筆自有出的錢），這裡就不是 0。
	 */
	private BigDecimal settlementBalanceChangedBy(Loan loan) {
		BigDecimal cash = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM cash_movement "
				+ "WHERE user_id = ? AND voided = FALSE AND (loan_id IS NULL OR loan_id <> ?)", BigDecimal.class, userId,
				loan.getId());
		BigDecimal bought = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM trade "
				+ "WHERE user_id = ? AND voided = FALSE", BigDecimal.class, userId);
		return cash.subtract(bought);
	}

	// ---------- 查詢 ----------

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private List<CashMovement> active(Long loanId, CashMovementType type) {
		return movementRepository.findByUserIdAndLoanIdOrderByMovementDateAscIdAsc(userId, loanId).stream()
			.filter(m -> !m.isVoided() && m.getType() == type)
			.toList();
	}

	private BigDecimal amountOf(Long loanId, CashMovementType type) {
		List<CashMovement> list = active(loanId, type);
		assertThat(list).hasSize(1);
		return list.get(0).getAmount();
	}

	private BigDecimal amountOf(Long movementId) {
		return jdbc.queryForObject("SELECT amount FROM cash_movement WHERE id = ?", BigDecimal.class, movementId);
	}

	private CashMovement firstMovement(Long loanId, CashMovementType type) {
		return active(loanId, type).stream()
			.filter(m -> m.getPeriod() != null)
			.min(Comparator.comparing(CashMovement::getPeriod))
			.orElseThrow();
	}

	private BigDecimal firstInterest(Long loanId) {
		return firstMovement(loanId, CashMovementType.LOAN_INTEREST).getAmount();
	}

	private int activeCount(Long loanId, CashMovementType type) {
		return active(loanId, type).size();
	}

	private List<String> revisionReasons(Long loanId) {
		return jdbc.queryForList("SELECT r.reason FROM cash_movement_revision r JOIN cash_movement m "
				+ "ON r.cash_movement_id = m.id WHERE m.loan_id = ?", String.class, loanId);
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
