package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.function.UnaryOperator;

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
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.LoanMovementForm;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.PrincipalForm;
import com.sideproject.stockledger.dto.RateVersionForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.RateSetting;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 借款的新增、修改、利率、還本、動撥（設計文件 3、4、5、11）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class LoanServiceTest {

	@Autowired
	private LoanService service;

	@Autowired
	private CashAccountService accounts;

	@Autowired
	private CashMovementService movements;

	@Autowired
	private TotalAssetsService totalAssets;

	@Autowired
	private com.sideproject.stockledger.repository.LoanRepository loanRepository;

	@Autowired
	private com.sideproject.stockledger.repository.RateSettingRepository rateRepository;

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
	private YearMonth thisMonth;
	private Long bank;
	private Long settlement;

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
		createIfMissing("ln-amy");
		createIfMissing("ln-ben");
		loginAs("ln-amy");
		today = LocalDate.now(clock);
		thisMonth = YearMonth.from(today);
		bank = accounts.create(new CashAccountForm("土銀帳戶", CashAccountType.BANK_DEMAND, "土地銀行")).getId();
		settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	/** 既有借款：不記撥款；寫第一個利率版本，生效日 ＝ 起算日（設計文件 3）。 */
	@Test
	void createExistingCredit() {
		Loan loan = service.create(existingCredit());

		assertThat(loan.getStartPrincipal()).isEqualByComparingTo("400000");
		assertThat(count("SELECT COUNT(*) FROM cash_movement WHERE loan_id = ?", loan.getId())).isZero();
		assertThat(jdbc.queryForMap("SELECT value, effective_date FROM rate_setting WHERE loan_id = ?", loan.getId()))
			.satisfies(r -> assertThat(r.get("value").toString()).isEqualTo("0.025000"))
			.satisfies(r -> assertThat(r.get("effective_date").toString()).isEqualTo(today.toString()));
	}

	/** 新撥款：起算日記一筆借款撥款到撥入帳戶。 */
	@Test
	void createNewPledgeRecordsDisbursement() {
		Loan loan = service.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, today,
				new BigDecimal("400000"), new BigDecimal("3"), settlement, settlement, 25, null, null, null));

		assertThat(jdbc.queryForMap("SELECT type, account_id, amount, note FROM cash_movement WHERE loan_id = ?",
				loan.getId()))
			.containsEntry("type", "LOAN_DISBURSEMENT")
			.containsEntry("account_id", settlement)
			.containsEntry("note", "玉山質押");
		assertThat(loan.getOriginalAmount()).isEqualByComparingTo("400000");
	}

	@Test
	void validation() {
		rejects(f -> with(f, " ", null), "請輸入名稱");
		rejects(f -> withBank(f, " "), "請輸入銀行");
		rejects(f -> withPrincipal(f, "0"), "剩餘本金要大於 0");
		rejects(f -> withPrincipal(f, "10.5"), "金額只能是整數元");
		rejects(f -> withPrincipal(f, "500000"), "剩餘本金不能大於原始借款金額");
		rejects(f -> withRate(f, "-1"), "年利率要在 0～30% 之間");
		rejects(f -> withRate(f, "31"), "年利率要在 0～30% 之間");
		rejects(f -> withStart(f, today.plusDays(1)), "起算日不能晚於今天");
		rejects(f -> withPeriods(f, 0), "剩餘期數要在 1～600 期");
		rejects(f -> withFirstPeriod(f, null), "請選擇第一期扣款月份");
		rejects(f -> withFirstPeriod(f, thisMonth.minusMonths(1)), "第一期扣款日要在起算日之後");
		rejects(f -> withPayDay(f, 32), "扣款日要在 1～31 號");
		rejects(f -> withPayAccount(f, null), "請選擇扣款帳戶");
	}

	/**
	 * DR-047（2026-10-23 使用者決定，取代設計文件 11-7）：起算日可以早於扣款帳戶的初始現金餘額日期；
	 * 早於那天的借款紀錄不影響帳戶餘額，帳戶餘額仍是初始現金餘額。
	 */
	@Test
	void startBeforeOpeningAllowed() {
		movements.setOpening(bank, new OpeningForm(today, new BigDecimal("100000")));

		service.create(withStart(existingCredit(), today.minusDays(1)));

		assertThat(totalAssets.systemBalance(bank)).isEqualByComparingTo("100000");
	}

	/** 改扣款日：從當月起生效（同 DR-037）。 */
	@Test
	void editPayDayMovesPostingFrom() {
		Loan loan = service.create(withStart(existingCredit(), today.minusMonths(2)));

		service.edit(loan.getId(), withPayDay(withStart(existingCredit(), today.minusMonths(2)), 10));

		assertThat(service.find(loan.getId()).getPostingFrom()).isEqualTo(thisMonth);
		assertThat(service.find(loan.getId()).getPayDay()).isEqualTo(10);
	}

	/** 換到新開的帳戶：只從當月起扣款，新帳戶的初始現金餘額日期不用早於起算日（審查 Important 8）。 */
	@Test
	void editPayAccountToNewerAccount() {
		Loan loan = service.create(withStart(existingCredit(), today.minusMonths(2)));
		Long newer = accounts.create(new CashAccountForm("新帳戶", CashAccountType.BANK_DEMAND, "台銀")).getId();
		movements.setOpening(newer, new OpeningForm(thisMonth.atDay(1), new BigDecimal("1000")));

		service.edit(loan.getId(), withPayAccount(withStart(existingCredit(), today.minusMonths(2)), newer));

		assertThat(service.find(loan.getId()).getPayAccountId()).isEqualTo(newer);
		assertThat(service.find(loan.getId()).getPostingFrom()).isEqualTo(thisMonth);
	}

	/** 起點欄位有自動記帳後不能改（設計文件 11-5）。 */
	@Test
	void lockedFieldsAfterPosting() {
		Loan loan = service.create(withFirstPeriod(withStart(existingCredit(), today.minusMonths(2)),
				thisMonth.minusMonths(1)));

		assertThatThrownBy(() -> service.edit(loan.getId(), withPeriods(withFirstPeriod(withStart(existingCredit(),
				today.minusMonths(2)), thisMonth.minusMonths(1)), 59)))
			.hasMessage("起算日、起算本金、剩餘期數、第一期已經開始記帳，不能再改");
	}

	@Test
	void addRateVersion() {
		Loan loan = service.create(existingCredit());

		service.addRate(loan.getId(), new RateVersionForm(today, new BigDecimal("2.75")));

		assertThat(count("SELECT COUNT(*) FROM rate_setting WHERE loan_id = ?", loan.getId())).isEqualTo(2);
		assertThat(service.currentRate(service.find(loan.getId()), today)).isEqualByComparingTo("0.0275");
	}

	@Test
	void repayRules() {
		Loan pledge = service.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING,
				new BigDecimal("400000"), today, new BigDecimal("400000"), new BigDecimal("3"), null, settlement, 25,
				null, null, null));

		assertThatThrownBy(() -> service.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("400001"),
				settlement))).hasMessage("還本不能超過剩餘本金 400,000 元");
		service.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("100000"), settlement));
		assertThat(service.remainingPrincipal(service.find(pledge.getId()), today)).isEqualByComparingTo("300000");

		service.redraw(pledge.getId(), new PrincipalForm(today, new BigDecimal("50000"), settlement));
		assertThat(service.remainingPrincipal(service.find(pledge.getId()), today)).isEqualByComparingTo("350000");
	}

	/**
	 * 剩餘本金任何一天都不能變成負數：補記較早的還本、把還本改大、作廢撥款都要檢查（審查 Important 4）。
	 */
	@Test
	void principalNeverGoesNegative() {
		Loan credit = service.create(withFirstPeriod(withStart(existingCredit(), today.minusMonths(2)),
				thisMonth.minusMonths(1)));
		// 第一期已經自動記了本金 6,265；起算日當天的剩餘本金還是 40 萬
		assertThatThrownBy(() -> service.repay(credit.getId(), new PrincipalForm(today.minusMonths(2),
				new BigDecimal("400000"), bank))).isInstanceOf(LoanValidationException.class)
			.hasMessage("這樣之後的剩餘本金會變成負數");
		assertThat(count("SELECT COUNT(*) FROM cash_movement WHERE loan_id = ? AND period IS NULL", credit.getId()))
			.isZero();

		Loan pledge = service.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, today,
				new BigDecimal("400000"), new BigDecimal("3"), settlement, settlement, 25, null, null, null));
		CashMovement repaid = service.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("100000"),
				settlement));
		Long disbursement = jdbc.queryForObject("SELECT id FROM cash_movement WHERE loan_id = ? AND type = "
				+ "'LOAN_DISBURSEMENT'", Long.class, pledge.getId());
		assertThatThrownBy(() -> movements.voidMovement(disbursement, null)).isInstanceOf(CashValidationException.class)
			.hasMessage("這樣之後的剩餘本金會變成負數");
		assertThatThrownBy(() -> movements.editLoanMovement(repaid.getId(),
				new LoanMovementForm(today, new BigDecimal("400001")), null)).isInstanceOf(CashValidationException.class)
			.hasMessage("這樣之後的剩餘本金會變成負數");
		assertThat(service.remainingPrincipal(service.find(pledge.getId()), today)).isEqualByComparingTo("300000");
	}

	/** 新撥款的信貸：撥款日改成銀行實際入帳日（晚於起算日），攤還表照樣算（審查 Important 5）。 */
	@Test
	void creditDisbursementDateEditKeepsSchedule() {
		Loan loan = service.create(new LoanForm("土銀信貸", LoanType.CREDIT, "土地銀行", LoanOrigin.NEW, null,
				today.minusDays(2), new BigDecimal("400000"), new BigDecimal("2.5"), bank, bank, 5, 60,
				thisMonth.plusMonths(1), null));
		Long disbursement = jdbc.queryForObject("SELECT id FROM cash_movement WHERE loan_id = ?", Long.class,
				loan.getId());

		movements.editLoanMovement(disbursement, new LoanMovementForm(today, new BigDecimal("400000")), "入帳日");

		assertThat(service.fundingView().loans().get(0).nextAmount()).isEqualByComparingTo("7099");
	}

	/**
	 * 作廢借款（使用者決定，2026-10-23）：連同所有紀錄一起作廢並寫修改紀錄；之後不再出現、不再自動記帳、不能再操作。
	 */
	@Test
	void voidLoanVoidsMovementsAndHides() {
		Loan loan = service.create(new LoanForm("打錯的質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, today,
				new BigDecimal("400000"), new BigDecimal("3"), settlement, settlement, 25, null, null, null));
		service.repay(loan.getId(), new PrincipalForm(today, new BigDecimal("100000"), settlement));

		service.voidLoan(loan.getId(), "起算本金打錯");

		assertThat(count("SELECT COUNT(*) FROM cash_movement WHERE loan_id = ? AND voided = FALSE", loan.getId()))
			.isZero();
		assertThat(count("SELECT COUNT(*) FROM cash_movement_revision r JOIN cash_movement m "
				+ "ON r.cash_movement_id = m.id WHERE m.loan_id = ? AND r.action = 'VOID'", loan.getId())).isEqualTo(2);
		assertThat(service.listMine()).isEmpty();
		assertThat(service.fundingView().totalDebt()).isEqualByComparingTo("0");
		assertThat(service.find(loan.getId()).isVoided()).isTrue();
		assertThatThrownBy(() -> service.repay(loan.getId(), new PrincipalForm(today, BigDecimal.TEN, settlement)))
			.hasMessage("這筆借款已經作廢");
		assertThatThrownBy(() -> service.voidLoan(loan.getId(), null)).hasMessage("這筆借款已經作廢");
	}

	@Test
	void redrawOnlyForPledge() {
		Loan credit = service.create(existingCredit());

		assertThatThrownBy(() -> service.redraw(credit.getId(), new PrincipalForm(today, BigDecimal.TEN, bank)))
			.hasMessage("只有質押可以再動撥");
	}

	/** 本月利息：質押今天起算 → 1 天的應計（40 萬 × 3% ÷ 365 ＝ 32.88 → 33）；信貸下個月才扣 → 0。 */
	@Test
	void accruedInterestThisMonth() {
		service.create(existingCredit());
		service.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING,
				new BigDecimal("400000"), today, new BigDecimal("400000"), new BigDecimal("3"), null, settlement, 25,
				null, null, null));

		assertThat(service.accruedInterest(thisMonth)).isEqualByComparingTo("33");
	}

	/**
	 * 到期一次還本金＋利息（DR-048）：一般借款（不是質押）也可以。每月只算應計利息：
	 * 本月 1 號到今天（含）的天數 × 10 萬 × 4% ÷ 365，四捨五入到元。
	 */
	@Test
	void bulletAccruesMonthly() {
		Loan loan = loanRepository.save(Loan.create(users.findByUsername("ln-amy").orElseThrow().getId(), "親友借款", LoanType.CREDIT, RepaymentMethod.BULLET,
				"親友", new BigDecimal("100000"), LoanOrigin.EXISTING, today.minusMonths(3), new BigDecimal("100000"),
				null, settlement, 28, null, null, today.plusYears(1), LocalDateTime.now(clock)));
		rateRepository.save(RateSetting.loanRate(loan.getUserId(), loan.getId(), new BigDecimal("0.04"), today.minusMonths(3),
				LocalDateTime.now(clock)));

		long days = today.getDayOfMonth();
		assertThat(service.accruedInterest(thisMonth)).isEqualByComparingTo(new BigDecimal("100000")
			.multiply(new BigDecimal("0.04")).multiply(BigDecimal.valueOf(days))
			.divide(BigDecimal.valueOf(365), 0, java.math.RoundingMode.HALF_UP));
	}

	/** 借款的異動只能改日期與金額，並寫修改紀錄（spec 4.9）。 */
	@Test
	void editLoanMovementWritesRevision() {
		Loan loan = service.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, today,
				new BigDecimal("400000"), new BigDecimal("3"), settlement, settlement, 25, null, null, null));
		Long id = jdbc.queryForObject("SELECT id FROM cash_movement WHERE loan_id = ?", Long.class, loan.getId());

		movements.editLoanMovement(id, new LoanMovementForm(today, new BigDecimal("399000")), "撥款扣了開辦費");

		assertThat(jdbc.queryForMap("SELECT field, old_value, new_value FROM cash_movement_revision "
				+ "WHERE cash_movement_id = ?", id))
			.containsEntry("field", "amount")
			.containsEntry("old_value", "400000")
			.containsEntry("new_value", "399000");
		assertThat(service.remainingPrincipal(service.find(loan.getId()), today)).isEqualByComparingTo("399000");
		assertThatThrownBy(() -> movements.editLoanMovement(id, new LoanMovementForm(today, BigDecimal.ZERO), null))
			.isInstanceOf(CashValidationException.class);
	}

	/** 質押還清的那筆利息記在還清當天，期別是下一個繳息月份：仍然要能修改金額（審查 Important 3）。 */
	@Test
	void payoffInterestInPreviousMonthCanBeEdited() {
		Loan loan = service.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING,
				new BigDecimal("400000"), today, new BigDecimal("400000"), new BigDecimal("3"), null, settlement, 25,
				null, null, null));
		Long userId = jdbc.queryForObject("SELECT user_id FROM loan WHERE id = ?", Long.class, loan.getId());
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, note, voided, "
				+ "created_at, loan_id, period) VALUES (?, 'LOAN_INTEREST', ?, ?, 99, '玉山質押', FALSE, NOW(), ?, ?)",
				userId, today, settlement, loan.getId(), thisMonth.plusMonths(1).atDay(1));
		Long id = jdbc.queryForObject("SELECT id FROM cash_movement WHERE loan_id = ?", Long.class, loan.getId());

		movements.editLoanMovement(id, new LoanMovementForm(today, new BigDecimal("100")), "對上銀行");

		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE id = ?", BigDecimal.class, id))
			.isEqualByComparingTo("100");
		assertThatThrownBy(() -> movements.editLoanMovement(id,
				new LoanMovementForm(thisMonth.minusMonths(1).atDay(1), new BigDecimal("100")), null))
			.isInstanceOf(CashValidationException.class);
	}

	/** 「記一筆現金異動」不接受借款類型（要從資金來源頁記）。 */
	@Test
	void recordRejectsLoanTypes() {
		assertThatThrownBy(() -> movements.record(new com.sideproject.stockledger.dto.CashMovementForm(
				com.sideproject.stockledger.entity.CashMovementType.LOAN_INTEREST, today, bank, null, BigDecimal.TEN,
				null))).isInstanceOf(CashValidationException.class);
	}

	@Test
	void otherUsersLoanIsNotFound() {
		Loan loan = service.create(existingCredit());
		loginAs("ln-ben");

		assertThat(service.listMine()).isEmpty();
		assertThatThrownBy(() -> service.find(loan.getId())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.repay(loan.getId(), new PrincipalForm(today, BigDecimal.TEN, bank)))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.addRate(loan.getId(), new RateVersionForm(today, BigDecimal.ONE)))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.edit(loan.getId(), existingCredit())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.redraw(loan.getId(), new PrincipalForm(today, BigDecimal.TEN, bank)))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.form(loan.getId())).isInstanceOf(NotFoundException.class);
	}

	/** 土地銀行信貸：既有借款，今天起算、剩 60 期、下個月起扣款，年利率 2.5%。 */
	private LoanForm existingCredit() {
		return new LoanForm("土銀信貸", LoanType.CREDIT, "土地銀行", LoanOrigin.EXISTING, new BigDecimal("400000"), today,
				new BigDecimal("400000"), new BigDecimal("2.5"), null, bank, 5, 60, thisMonth.plusMonths(1), null);
	}

	private void rejects(UnaryOperator<LoanForm> change, String message) {
		assertThatThrownBy(() -> service.create(change.apply(existingCredit())))
			.isInstanceOf(LoanValidationException.class)
			.hasMessage(message);
	}

	private static LoanForm with(LoanForm f, String name, Object unused) {
		return new LoanForm(name, f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(), f.startPrincipal(),
				f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), f.remainingPeriods(),
				f.firstPeriod(), f.dueDate());
	}

	private static LoanForm withBank(LoanForm f, String bank) {
		return new LoanForm(f.name(), f.type(), bank, f.origin(), f.originalAmount(), f.startDate(), f.startPrincipal(),
				f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), f.remainingPeriods(),
				f.firstPeriod(), f.dueDate());
	}

	private static LoanForm withPrincipal(LoanForm f, String principal) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				new BigDecimal(principal), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				f.remainingPeriods(), f.firstPeriod(), f.dueDate());
	}

	private static LoanForm withRate(LoanForm f, String rate) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), new BigDecimal(rate), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				f.remainingPeriods(), f.firstPeriod(), f.dueDate());
	}

	private static LoanForm withStart(LoanForm f, LocalDate start) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), start, f.startPrincipal(),
				f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), f.remainingPeriods(),
				f.firstPeriod(), f.dueDate());
	}

	private static LoanForm withPeriods(LoanForm f, Integer periods) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), periods,
				f.firstPeriod(), f.dueDate());
	}

	private static LoanForm withFirstPeriod(LoanForm f, YearMonth first) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				f.remainingPeriods(), first, f.dueDate());
	}

	private static LoanForm withPayDay(LoanForm f, Integer day) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), day,
				f.remainingPeriods(), f.firstPeriod(), f.dueDate());
	}

	private static LoanForm withPayAccount(LoanForm f, Long account) {
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), account, f.payDay(),
				f.remainingPeriods(), f.firstPeriod(), f.dueDate());
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
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
