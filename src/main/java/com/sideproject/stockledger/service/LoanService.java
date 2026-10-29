package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.DailyInterestCalculator;
import com.sideproject.stockledger.calculator.Installment;
import com.sideproject.stockledger.calculator.InstallmentCalculator;
import com.sideproject.stockledger.calculator.LoanAccrualCalculator;
import com.sideproject.stockledger.calculator.LoanRateCalculator;
import com.sideproject.stockledger.calculator.LoanUsage;
import com.sideproject.stockledger.calculator.PaymentChange;
import com.sideproject.stockledger.calculator.Portfolio;
import com.sideproject.stockledger.calculator.Position;
import com.sideproject.stockledger.calculator.StockMetricsCalculator;
import com.sideproject.stockledger.calculator.RateChange;
import com.sideproject.stockledger.dto.AllocationCut;
import com.sideproject.stockledger.dto.CollateralLine;
import com.sideproject.stockledger.dto.CollateralPick;
import com.sideproject.stockledger.dto.FundingView;
import com.sideproject.stockledger.dto.LoanCard;
import com.sideproject.stockledger.dto.LoanEditResult;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.LoanHoldingLine;
import com.sideproject.stockledger.dto.LoanPurchaseLine;
import com.sideproject.stockledger.dto.PrincipalForm;
import com.sideproject.stockledger.dto.RateVersionForm;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.PledgeCollateral;
import com.sideproject.stockledger.entity.RateKind;
import com.sideproject.stockledger.entity.RateSetting;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.entity.Stock;
import com.sideproject.stockledger.repository.CashAccountRepository;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.CashMovementRevisionRepository;
import com.sideproject.stockledger.repository.LoanRepository;
import com.sideproject.stockledger.repository.PledgeCollateralRepository;
import com.sideproject.stockledger.repository.RateSettingRepository;
import com.sideproject.stockledger.repository.StockRepository;
import com.sideproject.stockledger.security.CurrentUser;

/**
 * 借款（設計文件 2026-10-22-funding-sources）：新增（新撥款、既有借款）、修改、利率版本、還本、質押再動撥。
 * 方法參數故意沒有 userId：一律用 {@link CurrentUser#id()}；別人的借款或帳戶在這裡等於「不存在」（404）。
 */
@Service
public class LoanService {

	private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e12");
	private static final BigDecimal MAX_RATE_PERCENT = new BigDecimal("30");
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	/** 維持率門檻沒設定時（DR-007）：提醒線 150%、追繳線 130%。 */
	private static final BigDecimal DEFAULT_WARN = new BigDecimal("150");
	private static final BigDecimal DEFAULT_CALL = new BigDecimal("130");
	/** 到期前幾天開始提醒（spec 4.8a-5）。 */
	static final int DUE_REMINDER_DAYS = 30;
	private static final int MAX_NAME_LENGTH = 50;
	private static final int MAX_PERIODS = 600;

	/** 修改借款後系統重算時寫的修改紀錄原因；有這個原因的紀錄不算使用者手改過（DR-051）。 */
	public static final String EDIT_REASON = "借款資料修改後重算";

	static final String NEGATIVE_PRINCIPAL = "這樣之後的剩餘本金會變成負數";

	private final LoanRepository loans;
	private final RateSettingRepository rates;
	private final CashAccountRepository accounts;
	private final CashMovementRepository movements;
	private final LoanFactsReader facts;
	private final LoanPostingService posting;
	private final CashMovementService cashMovements;
	private final MoneyFormatter money;
	private final CurrentUser currentUser;
	private final NoticeService noticeService;
	private final Clock clock;
	private final TradeService tradeService;
	private final StockRepository stocks;
	private final PledgeCollateralRepository collaterals;
	private final CashMovementRevisionRepository revisions;

	public LoanService(LoanRepository loans, RateSettingRepository rates, CashAccountRepository accounts,
			CashMovementRepository movements, LoanFactsReader facts, LoanPostingService posting,
			CashMovementService cashMovements, MoneyFormatter money, CurrentUser currentUser, Clock clock,
			TradeService tradeService, StockRepository stocks, PledgeCollateralRepository collaterals,
			CashMovementRevisionRepository revisions, NoticeService noticeService) {
		this.noticeService = noticeService;
		this.revisions = revisions;
		this.tradeService = tradeService;
		this.stocks = stocks;
		this.collaterals = collaterals;
		this.cashMovements = cashMovements;
		this.loans = loans;
		this.rates = rates;
		this.accounts = accounts;
		this.movements = movements;
		this.facts = facts;
		this.posting = posting;
		this.money = money;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	// ---------- 新增、修改 ----------

	/**
	 * 新增借款。新撥款在起算日記一筆借款撥款；既有借款只是起點，不記現金異動（設計文件 3）。
	 * 兩種都寫第一個利率版本（生效日 ＝ 起算日），並立刻補記已到期的期數。
	 */
	@Transactional
	public Loan create(LoanForm submitted) {
		Long userId = currentUser.id();
		Checked checked = check(submitted);
		LoanForm form = checked.form();
		RepaymentMethod method = checked.method();
		BigDecimal rate = checked.rate();
		BigDecimal original = form.origin() == LoanOrigin.NEW && form.originalAmount() == null ? form.startPrincipal()
				: form.originalAmount();
		LocalDateTime now = LocalDateTime.now(clock);
		Loan loan = loans.save(Loan.create(userId, form.name(), form.type(), method, form.bank(), original,
				form.origin(), form.startDate(), form.startPrincipal(), form.disburseAccountId(), form.payAccountId(),
				form.payDay(), form.remainingPeriods(), form.firstPeriod(), form.dueDate(), now));
		rates.save(RateSetting.loanRate(userId, loan.getId(), rate, form.startDate(), now));
		if (method == RepaymentMethod.MONTHLY_FIXED && form.monthlyPayment() != null) {
			rates.save(RateSetting.loanPayment(userId, loan.getId(), form.monthlyPayment(), form.startDate(), now));
		}
		if (form.origin() == LoanOrigin.NEW) {
			movements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_DISBURSEMENT, form.startDate(),
					form.disburseAccountId(), form.startPrincipal(), null, now));
		}
		if (form.fee() != null && form.fee().signum() > 0) {
			// 開辦費記在撥款日，算進借款成本（DR-048）
			movements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_FEE, form.startDate(),
					form.payAccountId(), form.fee(), null, now));
		}
		posting.postDue(loan, today());
		for (CollateralPick pick : form.collateral()) {
			if (!picked(pick)) {
				continue;
			}
			Stock stock = stocks.findById(pick.stockId()).orElseThrow(() -> new NotFoundException("找不到這檔股票"));
			setCollateral(loan.getId(), stock.getCode(), pick.shares());
		}
		if (form.type() == LoanType.PLEDGE && (form.warnPercent() != null || form.callPercent() != null)) {
			setThresholds(loan.getId(), form.warnPercent() == null ? DEFAULT_WARN : form.warnPercent(),
					form.callPercent() == null ? DEFAULT_CALL : form.callPercent());
		}
		return loan;
	}

	/**
	 * 表單上省略的欄位補成存檔需要的值（spec 4.8a-2）：往來帳戶同時是撥入與扣款帳戶；到期一次付清沒有扣款日，
	 * 用撥款日的日子；每月固定還款的第一期是撥款日之後的第一個扣款日。
	 */
	private static LoanForm normalize(LoanForm f, RepaymentMethod method) {
		Long disburse = f.origin() == LoanOrigin.NEW && f.disburseAccountId() == null ? f.payAccountId()
				: f.disburseAccountId();
		Integer payDay = f.payDay() == null && method == RepaymentMethod.BULLET && f.startDate() != null
				? Integer.valueOf(f.startDate().getDayOfMonth()) : f.payDay();
		YearMonth first = f.firstPeriod();
		if (first == null && f.repaymentMethod() == RepaymentMethod.MONTHLY_FIXED && payDay != null
				&& f.startDate() != null) {
			YearMonth m = YearMonth.from(f.startDate());
			first = m.atDay(Math.min(payDay, m.lengthOfMonth())).isAfter(f.startDate()) ? m : m.plusMonths(1);
		}
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), disburse, f.payAccountId(), payDay, f.remainingPeriods(), first,
				f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(), f.collateral(), f.warnPercent(),
				f.callPercent());
	}

	/**
	 * 只檢查、不存（引導的「預覽」，設計文件 2026-10-25-onboarding 7a-1）：和 {@link #create} 走同一段
	 * {@link #check}，丟出的訊息一模一樣。擔保股票的股數要等借款存好才能檢查（setCollateral），不在這裡。
	 */
	@Transactional(readOnly = true)
	public void validate(LoanForm submitted) {
		check(submitted);
	}

	/** 新增前的所有檢查都在這裡：create 與 validate 共用。 */
	private Checked check(LoanForm submitted) {
		RepaymentMethod method = submitted.repaymentMethod() != null ? submitted.repaymentMethod()
				: RepaymentMethod.legacyOf(submitted.type());
		LoanForm form = normalize(submitted, method);
		BigDecimal rate = validate(form, method);
		if (form.type() != LoanType.PLEDGE && form.collateral().stream().anyMatch(LoanService::picked)) {
			throw new LoanValidationException("只有股票質押要選擔保股票");
		}
		if (method == RepaymentMethod.MONTHLY_FIXED) {
			checkSchedule(form, rate);
		}
		return new Checked(form, method, rate);
	}

	/** 檢查過的表單（已補上省略的欄位）、還款方式、轉成比率的年利率。 */
	private record Checked(LoanForm form, RepaymentMethod method, BigDecimal rate) {
	}

	private static boolean picked(CollateralPick pick) {
		return pick.stockId() != null && pick.shares() != null && pick.shares() != 0;
	}

	/**
	 * 銀行扣款比當期利息少（填錯）時，攤還表會出現負的本金：擋下並說明（DR-048）。
	 * 用表單算，不必先存：新借款的攤還表只看起算本金、第一個利率版本與銀行扣款（都是起算日生效），還沒有已記的期數，
	 * 和存好後由 LoanFacts 讀出來的輸入相同。
	 */
	private static void checkSchedule(LoanForm form, BigDecimal rate) {
		List<PaymentChange> payments = form.monthlyPayment() == null ? List.of()
				: List.of(new PaymentChange(form.startDate(), form.monthlyPayment()));
		checkSchedule(() -> new InstallmentCalculator.Input(form.startPrincipal(), form.remainingPeriods(),
				form.firstPeriod(), form.payDay(), List.of(new RateChange(form.startDate(), rate)), List.of(), Map.of(),
				payments));
	}

	/** 已經存好的借款（修改、還本後）：照資料庫裡的事件重算攤還表。 */
	private void checkSchedule(Loan loan) {
		checkSchedule(() -> facts.read(loan).installmentInput());
	}

	/** 建立輸入（期數、扣款日不合理也會丟 IllegalArgumentException）和算攤還表都轉成同樣的借款錯誤訊息。 */
	private static void checkSchedule(Supplier<InstallmentCalculator.Input> input) {
		try {
			InstallmentCalculator.schedule(input.get());
		}
		catch (IllegalArgumentException ex) {
			throw new LoanValidationException(ex.getMessage());
		}
	}

	/**
	 * 到期日已經過了的提醒（不擋存檔，DR-051）。沒有到期日、到期日還沒到、或每月固定還款（用不到到期日）為 null。
	 * 還款方式沒填的是 V11 以前的寫法，照類型推（信貸是每月固定還款）。
	 */
	@Transactional(readOnly = true)
	public String dueWarning(LoanForm form) {
		RepaymentMethod method = form.repaymentMethod() != null ? form.repaymentMethod()
				: RepaymentMethod.legacyOf(form.type());
		if (method == RepaymentMethod.MONTHLY_FIXED || form.dueDate() == null || !form.dueDate().isBefore(today())) {
			return null;
		}
		return "到期日 " + form.dueDate() + " 已經過了。如果已經還清，這筆就不用記；如果有展延，請填展延後的到期日。";
	}

	/**
	 * 每月固定還款：用金額、利率、期數算出的合約月付金和銀行扣款差超過 1 元時的提醒（不擋存檔，DR-048）。
	 * 其他還款方式、或沒有填銀行扣款的舊借款為 null。
	 */
	@Transactional(readOnly = true)
	public String paymentWarning(Long id) {
		Loan loan = find(id);
		if (loan.getRepaymentMethod() != RepaymentMethod.MONTHLY_FIXED) {
			return null;
		}
		LoanFacts f = facts.read(loan);
		if (f.payments().isEmpty()) {
			return null;
		}
		BigDecimal bank = originalPayment(f.payments(), loan.getStartDate()).orElseThrow();
		return contractPaymentWarning(f.installmentInput().startPrincipal(),
				originalRate(f.rates(), loan.getStartDate()), loan.getRemainingPeriods(), bank);
	}

	/**
	 * 和 {@link #paymentWarning(Long)} 一樣的提醒，但用還沒存的表單算（引導的確認畫面）。
	 * 不是每月固定還款、沒填銀行扣款、或算不出來（金額、利率、期數沒填或不合理，存檔時會擋）時為 null。
	 */
	public String paymentWarning(LoanForm form) {
		RepaymentMethod method = form.repaymentMethod() != null ? form.repaymentMethod()
				: RepaymentMethod.legacyOf(form.type());
		if (method != RepaymentMethod.MONTHLY_FIXED || form.monthlyPayment() == null || form.startPrincipal() == null
				|| form.startPrincipal().signum() <= 0 || form.remainingPeriods() == null || form.remainingPeriods() < 1
				|| form.remainingPeriods() > MAX_PERIODS) {
			return null;
		}
		BigDecimal rate;
		try {
			rate = toRate(form.annualRatePercent());
		}
		catch (LoanValidationException ex) {
			return null;
		}
		return contractPaymentWarning(form.startPrincipal(), rate, form.remainingPeriods(), form.monthlyPayment());
	}

	/** 合約月付金（金額、利率、期數算出來的）和銀行扣款差超過 1 元時的提醒文字；否則 null。 */
	private String contractPaymentWarning(BigDecimal principal, BigDecimal rate, int periods, BigDecimal bank) {
		BigDecimal expected = InstallmentCalculator.monthlyPayment(principal, rate, periods);
		BigDecimal diff = expected.subtract(bank).abs();
		if (diff.compareTo(BigDecimal.ONE) <= 0) {
			return null;
		}
		return "用金額、利率、期數算出來是 " + money.plain(expected) + "，和銀行扣款 " + money.plain(bank) + " 差 "
				+ money.plain(diff) + "；請確認金額或利率";
	}

	/** 還本表單的預設值：剩餘本金；到期一次還本金＋利息另外帶入上次付息（或撥款日）到今天的應計利息。 */
	@Transactional(readOnly = true)
	public PrincipalForm repayDefaults(Long id) {
		Loan loan = find(id);
		LoanFacts f = facts.read(loan);
		LocalDate today = today();
		BigDecimal interest = loan.getRepaymentMethod() != RepaymentMethod.BULLET ? null
				: DailyInterestCalculator.roundUp(DailyInterestCalculator.interest(f.startBalance(),
						f.principalChanges(), f.rates(), f.lastInterestPaidBefore(today), today));
		return new PrincipalForm(today, whole(f.remaining(today)), loan.getPayAccountId(), interest);
	}

	/**
	 * 修改借款：每個欄位隨時都能改（spec 4.8a-10、DR-051），只有新撥款或既有借款不能改。改了合約資料時：
	 * 撥款與開辦費跟著改；原始利率、扣款金額改了，在起算日新增一個版本（同一天 id 大的優先，等於改正第一個版本）；
	 * 自動記的每期依新合約重新對照（{@link LoanPostingService#reconcile}），使用者手改過的不動，和新合約不同的放進結果列出。
	 * 表單沒送的類型、還款方式、利率當作沒改（之後的 REST API）。
	 *
	 * @param confirmShortfall 借款改小、不夠付已經用它買的股票時，是否確定把差額改算自有；false 時丟
	 *                         {@link LoanShortfallException}（見 {@link #coverShortfall}）
	 */
	@Transactional
	public LoanEditResult edit(Long id, LoanForm submitted, boolean confirmShortfall) {
		Long userId = currentUser.id();
		Loan loan = findForUpdate(id);
		if (changed(submitted.origin(), loan.getOrigin())) {
			throw new LoanValidationException("新撥款或既有借款建立後不能改");
		}
		LoanFacts before = facts.read(loan);
		RepaymentMethod method = orElse(submitted.repaymentMethod(), loan.getRepaymentMethod());
		boolean monthly = method == RepaymentMethod.MONTHLY_FIXED;
		BigDecimal principal = orElse(submitted.startPrincipal(), loan.getStartPrincipal());
		BigDecimal original = submitted.originalAmount() != null ? submitted.originalAmount()
				: loan.getOrigin() == LoanOrigin.NEW ? principal : loan.getOriginalAmount();
		BigDecimal ratePercent = submitted.annualRatePercent() != null ? submitted.annualRatePercent()
				: exactPercent(originalRate(before.rates(), loan.getStartDate()));
		LoanForm form = normalize(new LoanForm(submitted.name(), orElse(submitted.type(), loan.getType()),
				submitted.bank(), loan.getOrigin(), original, orElse(submitted.startDate(), loan.getStartDate()),
				principal, ratePercent, submitted.disburseAccountId(), submitted.payAccountId(), submitted.payDay(),
				monthly ? orElse(submitted.remainingPeriods(), loan.getRemainingPeriods()) : submitted.remainingPeriods(),
				keptFirstPeriod(loan, submitted), submitted.dueDate(), submitted.repaymentMethod(), submitted.monthlyPayment(),
				submitted.fee(), submitted.collateral(), submitted.warnPercent(), submitted.callPercent()), method);
		BigDecimal rate = validate(form, method);
		if (monthly && before.active().stream().filter(m -> m.getType() == CashMovementType.LOAN_DISBURSEMENT).count() > 1) {
			// 攤還表把所有撥款當成第 1 期起的本金：再動撥那筆會被當成第 1 期就有，整張重算（和 redraw 的檢查同理）
			throw new LoanValidationException("有再動撥的借款不能改成每月固定還款");
		}

		LocalDate oldStart = loan.getStartDate();
		BigDecimal oldPrincipal = loan.getStartPrincipal();
		Long oldDisburse = loan.getDisburseAccountId();
		Long oldPay = loan.getPayAccountId();
		LoanType oldType = loan.getType();
		LocalDateTime now = LocalDateTime.now(clock);
		boolean contractChanged = contractChanged(loan, before, form, method, rate);
		if (!contractChanged) {
			// 只改名稱、銀行、到期日、原始金額（或開辦費）：每期紀錄一筆都不動，照平常補記（舊借款 DR-037 的起點也不動）
			loan.change(form.name(), form.bank(), form.originalAmount(), form.payAccountId(), form.payDay(),
					form.dueDate());
			reviseFee(loan, before, form.fee(), false, false, now);
			posting.postDue(loan, today());
			requireLoansCovered();
			return new LoanEditResult(loan, List.of(), false);
		}
		loan.reviseContract(form.type(), method, form.startDate(), form.startPrincipal(), form.disburseAccountId(),
				form.remainingPeriods(), form.firstPeriod());
		// 到期一次還本金要有到期日（資料庫 ck_loan_method）：同一個交易裡接著設定
		loan.change(form.name(), form.bank(), form.originalAmount(), form.payAccountId(), form.payDay(),
				form.dueDate());

		// 撥款與開辦費：只跟著改了的欄位走，沒改的欄位保留使用者對過銀行明細的值
		boolean startChanged = !loan.getStartDate().equals(oldStart);
		disbursement(before).ifPresent(m -> cashMovements.followLoan(m,
				startChanged ? loan.getStartDate() : m.getMovementDate(),
				Objects.equals(loan.getDisburseAccountId(), oldDisburse) ? m.getAccountId() : loan.getDisburseAccountId(),
				loan.getStartPrincipal().compareTo(oldPrincipal) == 0 ? m.getAmount() : loan.getStartPrincipal(),
				EDIT_REASON));
		reviseFee(loan, before, form.fee(), startChanged, !Objects.equals(loan.getPayAccountId(), oldPay), now);

		correctRate(loan, before.rates(), rate, oldStart, now);
		if (monthly && form.monthlyPayment() != null) {
			correctPayment(loan, before.payments(), form.monthlyPayment(), oldStart, now);
		}
		if (oldType == LoanType.PLEDGE && loan.getType() != LoanType.PLEDGE) {
			// 不再是股票質押：擔保股票是設定不是事件（DR-046），直接拿掉，不然會繼續擋住賣出
			collaterals.findByUserIdAndLoanId(userId, loan.getId()).forEach(collaterals::delete);
		}
		if (monthly) {
			checkSchedule(loan);
		}
		List<CashMovement> kept;
		try {
			kept = posting.reconcile(loan, today(),
					new HashSet<>(revisions.findUserEditedMovementIds(userId, EDIT_REASON)));
		}
		catch (IllegalArgumentException ex) {
			// 攤還表算不下去（例如保留的期數之後扣款比利息少）
			throw new LoanValidationException(ex.getMessage());
		}
		if (facts.read(loan).minRemaining().signum() < 0) {
			throw new LoanValidationException(NEGATIVE_PRINCIPAL);
		}
		coverShortfall(loan, confirmShortfall);
		return new LoanEditResult(loan, kept.stream().map(LoanEditResult.KeptPeriod::of).toList(),
				!Objects.equals(loan.getPayAccountId(), oldPay));
	}

	/** 表單沒送第一期月份（修改畫面對新撥款不送）時：撥款日和扣款日都沒改就沿用原本的，只有改了才重新推算。 */
	private static YearMonth keptFirstPeriod(Loan loan, LoanForm submitted) {
		if (submitted.firstPeriod() != null) {
			return submitted.firstPeriod();
		}
		boolean startKept = submitted.startDate() == null || submitted.startDate().equals(loan.getStartDate());
		return startKept && Objects.equals(submitted.payDay(), loan.getPayDay()) ? loan.getFirstPeriod() : null;
	}

	/**
	 * 合約資料有沒有改（spec 4.8a-10）：撥款日、金額、還款方式、是否股票質押、原始利率、期數、第一期、扣款金額、
	 * 扣款日、往來帳戶。只有改了才重新對照每期紀錄；只改名稱、銀行、到期日、原始金額時一筆紀錄都不動。
	 */
	private static boolean contractChanged(Loan loan, LoanFacts before, LoanForm form, RepaymentMethod method,
			BigDecimal rate) {
		boolean monthly = method == RepaymentMethod.MONTHLY_FIXED;
		boolean paymentChanged = monthly && form.monthlyPayment() != null
				&& originalPayment(before.payments(), loan.getStartDate())
					.map(p -> p.compareTo(form.monthlyPayment()) != 0)
					.orElse(true);
		return !form.startDate().equals(loan.getStartDate())
				|| form.startPrincipal().compareTo(loan.getStartPrincipal()) != 0
				|| method != loan.getRepaymentMethod()
				|| form.type() != loan.getType()
				|| rate.compareTo(originalRate(before.rates(), loan.getStartDate())) != 0
				|| (monthly && !Objects.equals(form.remainingPeriods(), loan.getRemainingPeriods()))
				|| (monthly && !Objects.equals(form.firstPeriod(), loan.getFirstPeriod()))
				|| paymentChanged
				|| !Objects.equals(form.payDay(), loan.getPayDay())
				|| !Objects.equals(form.payAccountId(), loan.getPayAccountId())
				|| !Objects.equals(form.disburseAccountId(), loan.getDisburseAccountId());
	}

	/** 撥款那一筆：和借款一起建立的那筆（id 最小）；之後的是質押再動撥，撥款日改了也不會搞混。既有借款沒有。 */
	private static Optional<CashMovement> disbursement(LoanFacts f) {
		// 含作廢的一起找：原本的撥款被使用者作廢時，不能改到再動撥那筆
		return f.movements().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_DISBURSEMENT && m.getPeriod() == null)
			.min(java.util.Comparator.comparing(CashMovement::getId))
			.filter(m -> !m.isVoided());
	}

	/** 開辦費：原本有、新的是 0 或空 → 作廢；原本沒有、新的有 → 新增；都有 → 跟著改（記在撥款日、往來帳戶）。 */
	private void reviseFee(Loan loan, LoanFacts before, BigDecimal fee, boolean startChanged, boolean accountChanged,
			LocalDateTime now) {
		Optional<CashMovement> current = before.active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_FEE)
			.findFirst();
		boolean wanted = fee != null && fee.signum() > 0;
		if (current.isPresent() && !wanted) {
			cashMovements.voidForLoanEdit(current.get(), EDIT_REASON);
		}
		else if (current.isPresent()) {
			CashMovement m = current.get();
			cashMovements.followLoan(m, startChanged ? loan.getStartDate() : m.getMovementDate(),
					accountChanged ? loan.getPayAccountId() : m.getAccountId(), fee, EDIT_REASON);
		}
		else if (wanted) {
			movements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_FEE, loan.getStartDate(),
					loan.getPayAccountId(), fee, null, now));
		}
	}

	/**
	 * 修改原始利率（改正打錯的第一個版本，和「新增利率版本」分開，DR-051）：在起算日新增一個版本，同一天 id 大的優先。
	 * 起算日提前時，新的起算日一定要有版本；利率也改了的話，原本起算日的那個版本也要蓋掉。
	 */
	private void correctRate(Loan loan, List<RateChange> versions, BigDecimal rate, LocalDate oldStart,
			LocalDateTime now) {
		LocalDate start = loan.getStartDate();
		boolean changedRate = rate.compareTo(originalRate(versions, oldStart)) != 0;
		boolean covered = versions.stream().anyMatch(r -> !r.effectiveDate().isAfter(start));
		if (changedRate || !covered) {
			rates.save(RateSetting.loanRate(currentUser.id(), loan.getId(), rate, start, now));
		}
		if (changedRate && start.isBefore(oldStart)) {
			rates.save(RateSetting.loanRate(currentUser.id(), loan.getId(), rate, oldStart, now));
		}
	}

	/** 修改銀行每月扣款金額（第一個版本），做法同 {@link #correctRate}。 */
	private void correctPayment(Loan loan, List<PaymentChange> versions, BigDecimal payment, LocalDate oldStart,
			LocalDateTime now) {
		LocalDate start = loan.getStartDate();
		Optional<BigDecimal> original = originalPayment(versions, oldStart);
		boolean changedPayment = original.map(o -> o.compareTo(payment) != 0).orElse(true);
		boolean covered = versions.stream().anyMatch(p -> !p.effectiveDate().isAfter(start));
		if (changedPayment || !covered) {
			rates.save(RateSetting.loanPayment(currentUser.id(), loan.getId(), payment, start, now));
		}
		if (changedPayment && original.isPresent() && start.isBefore(oldStart)) {
			rates.save(RateSetting.loanPayment(currentUser.id(), loan.getId(), payment, oldStart, now));
		}
	}

	/** 原始利率：起算日適用的版本；起算日以前沒有版本時是最早的版本。 */
	private static BigDecimal originalRate(List<RateChange> versions, LocalDate start) {
		boolean covered = versions.stream().anyMatch(r -> !r.effectiveDate().isAfter(start));
		return covered ? RateChange.rateOn(versions, start) : versions.get(0).annualRate();
	}

	/** 原始的銀行每月扣款：起算日適用的版本，沒有就是最早的版本；V11 以前的借款沒有版本（empty）。 */
	private static Optional<BigDecimal> originalPayment(List<PaymentChange> versions, LocalDate start) {
		return PaymentChange.amountOn(versions, start)
			.or(() -> versions.stream().findFirst().map(PaymentChange::amount));
	}

	/** 新的年利率版本：信貸從生效日之後的第一期起重算月付金（DR-018）；質押從生效日起按日計息。 */
	@Transactional
	public void addRate(Long id, RateVersionForm form) {
		Loan loan = findForUpdate(id);
		if (form.effectiveDate() == null) {
			throw new LoanValidationException("請選擇生效日");
		}
		if (form.effectiveDate().isBefore(loan.getStartDate())) {
			throw new LoanValidationException("生效日不能早於起算日");
		}
		BigDecimal rate = toRate(form.annualRatePercent());
		rates.save(RateSetting.loanRate(currentUser.id(), loan.getId(), rate, form.effectiveDate(),
				LocalDateTime.now(clock)));
		if (form.monthlyPayment() != null) {
			// 銀行的扣款金額跟著利率一起換（DR-048）；沒填就照公式重算（DR-018）
			if (loan.getRepaymentMethod() != RepaymentMethod.MONTHLY_FIXED) {
				throw new LoanValidationException("只有每月固定還款要填每月扣款金額");
			}
			checkAmount(form.monthlyPayment(), "每月扣款金額");
			rates.save(RateSetting.loanPayment(currentUser.id(), loan.getId(), form.monthlyPayment(),
					form.effectiveDate(), LocalDateTime.now(clock)));
		}
		if (loan.getRepaymentMethod() == RepaymentMethod.MONTHLY_FIXED) {
			// 利率調高、扣款沒跟著改時，扣款可能比當期利息少：用說明擋下（審查 I-1）
			checkSchedule(loan);
		}
		posting.postDue(loan, today());
	}

	/** 還本：信貸是提前還本（之後月付金重算、期數不變）；質押是還本。不能超過當天的剩餘本金。 */
	@Transactional
	public CashMovement repay(Long id, PrincipalForm form) {
		Loan loan = findForUpdate(id);
		Long accountId = validatePrincipal(loan, form);
		BigDecimal remaining = facts.read(loan).remaining(form.date());
		if (form.amount().compareTo(remaining) > 0) {
			throw new LoanValidationException("還本不能超過剩餘本金 " + money.plain(remaining) + " 元");
		}
		CashMovement saved = movements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_PRINCIPAL,
				form.date(), accountId, form.amount(), null, LocalDateTime.now(clock)));
		if (form.interest() != null && form.interest().signum() != 0) {
			// 到期一次還本金＋利息：利息和本金同一天一起付（DR-048）；其他方式的利息由自動記帳處理
			if (loan.getRepaymentMethod() != RepaymentMethod.BULLET) {
				throw new LoanValidationException("只有到期一次還本金＋利息要一起填利息");
			}
			checkAmount(form.interest(), "利息");
			movements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_INTEREST, form.date(), accountId,
					form.interest(), null, LocalDateTime.now(clock)));
		}
		// 補記較早的還本時，當天沒超過、但之後已經記的還本加起來可能超過
		if (facts.read(loan).minRemaining().signum() < 0) {
			throw new LoanValidationException(NEGATIVE_PRINCIPAL);
		}
		// 質押還清時，當天要記最後一段利息
		posting.postDue(loan, today());
		requireLoansCovered();
		return saved;
	}

	/** 質押再動撥：記一筆借款撥款到指定帳戶，之後依新的餘額計息。 */
	@Transactional
	public CashMovement redraw(Long id, PrincipalForm form) {
		Loan loan = findForUpdate(id);
		if (loan.getType() != LoanType.PLEDGE) {
			throw new LoanValidationException("只有質押可以再動撥");
		}
		if (loan.getRepaymentMethod() == RepaymentMethod.MONTHLY_FIXED) {
			// 攤還表把所有撥款當成第 1 期起的本金，中途再動撥會整張重算（審查 I-2）
			throw new LoanValidationException("每月固定還款不能再動撥");
		}
		Long accountId = validatePrincipal(loan, form);
		return movements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_DISBURSEMENT, form.date(),
				accountId, form.amount(), null, LocalDateTime.now(clock)));
	}

	/**
	 * 作廢借款（例如起算本金打錯又已經開始記帳）：連同所有撥款、還本、利息一起作廢並寫修改紀錄，
	 * 之後不出現在資金來源頁、不再自動記帳。不硬刪除；只有擔保股票是設定不是事件（DR-046），直接刪掉，
	 * 不然會繼續擋住賣出。
	 */
	@Transactional
	public void voidLoan(Long id, String reason) {
		Loan loan = findForUpdate(id);
		if (tradeService.loanInUse(loan.getId())) {
			throw new LoanValidationException("還有股票交易用到這筆借款當資金來源；請先修改或作廢那些交易");
		}
		cashMovements.voidLoanMovements(loan.getId(), reason);
		collaterals.findByUserIdAndLoanId(currentUser.id(), loan.getId()).forEach(collaterals::delete);
		loan.markVoided();
	}

	/**
	 * 設定質押的擔保股票股數（spec 5.5）；0 ＝ 拿掉。股數不能超過目前持有股數。
	 * 擔保品是設定、不是事件，所以直接改或刪（我的判斷，見問題清單）。
	 */
	@Transactional
	public void setCollateral(Long id, String stockCode, Long shares) {
		Loan loan = findForUpdate(id);
		Stock stock = checkCollateral(loan, stockCode, shares);
		Long userId = currentUser.id();
		Optional<PledgeCollateral> existing = collaterals.findByUserIdAndLoanIdAndStockId(userId, loan.getId(),
				stock.getId());
		if (shares == 0) {
			existing.ifPresent(collaterals::delete);
			return;
		}
		existing.ifPresentOrElse(c -> c.setShares(shares),
				() -> collaterals.save(PledgeCollateral.of(userId, loan.getId(), stock.getId(), shares)));
	}

	/** 只檢查、不存（引導擔保頁的預覽，設計文件 7a-1）：和 {@link #setCollateral} 同一段檢查，丟出的訊息一模一樣。 */
	@Transactional(readOnly = true)
	public void validateCollateral(Long id, String stockCode, Long shares) {
		checkCollateral(findActive(id), stockCode, shares);
	}

	/** 擔保股票的規則：只有質押、股票要存在、股數不能是負的，擔保合計（含其他質押）不能超過目前持股。回傳那檔股票。 */
	private Stock checkCollateral(Loan loan, String stockCode, Long shares) {
		if (loan.getType() != LoanType.PLEDGE) {
			throw new LoanValidationException("只有質押要設定擔保股票");
		}
		if (stockCode == null || stockCode.isBlank()) {
			throw new LoanValidationException("請輸入股票代號");
		}
		Stock stock = stocks.findByCode(stockCode.strip())
			.orElseThrow(() -> new LoanValidationException("找不到這個上市股票代號：" + stockCode.strip()));
		if (shares == null || shares < 0) {
			throw new LoanValidationException("請輸入擔保股數（0 表示拿掉）");
		}
		if (shares == 0) {
			return stock;
		}
		Long userId = currentUser.id();
		Position position = tradeService.portfolio().positions().get(stock.getId());
		long held = position == null ? 0 : position.shares();
		// 同一檔股票可能同時擔保好幾筆質押：合計不能超過持股
		long otherLoans = collaterals.findActiveByUserId(userId).stream()
			.filter(c -> c.getStockId().equals(stock.getId()) && !c.getLoanId().equals(loan.getId()))
			.mapToLong(PledgeCollateral::getShares)
			.sum();
		if (shares + otherLoans > held) {
			throw new LoanValidationException(otherLoans == 0
					? "擔保股數不能超過持有股數（" + money.plain(BigDecimal.valueOf(held)) + " 股）"
					: "擔保股數加上其他質押的 " + money.plain(BigDecimal.valueOf(otherLoans)) + " 股，超過持有股數（"
							+ money.plain(BigDecimal.valueOf(held)) + " 股）");
		}
		return stock;
	}

	/** 這筆質押目前的擔保股票：股票 id → 擔保股數（照股票 id 排）。別人的借款 → 404。 */
	@Transactional(readOnly = true)
	public Map<Long, Long> collateralOf(Long id) {
		Loan loan = find(id);
		Map<Long, Long> shares = new TreeMap<>();
		collaterals.findByUserIdAndLoanId(currentUser.id(), loan.getId())
			.forEach(c -> shares.put(c.getStockId(), c.getShares()));
		return shares;
	}

	/**
	 * 其他沒作廢的質押已經押的股數：股票 id → 合計股數（和 {@link #checkCollateral} 的「合計不能超過持股」同一個算法）。
	 * 別人的借款 → 404。
	 */
	@Transactional(readOnly = true)
	public Map<Long, Long> collateralOfOtherLoans(Long id) {
		Loan loan = find(id);
		Map<Long, Long> shares = new TreeMap<>();
		collaterals.findActiveByUserId(currentUser.id())
			.stream()
			.filter(c -> !c.getLoanId().equals(loan.getId()))
			.forEach(c -> shares.merge(c.getStockId(), c.getShares(), Long::sum));
		return shares;
	}

	/** 設定這筆質押的維持率提醒線與追繳線（%），從今天起生效（spec 5.5、DR-007；沒設定時 150%、130%）。 */
	@Transactional
	public void setThresholds(Long id, BigDecimal warnPercent, BigDecimal callPercent) {
		Loan loan = findForUpdate(id);
		checkThresholds(loan, warnPercent, callPercent);
		LocalDate effective = today().isBefore(loan.getStartDate()) ? loan.getStartDate() : today();
		LocalDateTime now = LocalDateTime.now(clock);
		rates.save(RateSetting.loanThreshold(currentUser.id(), loan.getId(), RateKind.MAINTENANCE_WARN,
				warnPercent.divide(HUNDRED, 10, RoundingMode.HALF_UP), effective, now));
		rates.save(RateSetting.loanThreshold(currentUser.id(), loan.getId(), RateKind.MAINTENANCE_CALL,
				callPercent.divide(HUNDRED, 10, RoundingMode.HALF_UP), effective, now));
	}

	/** 只檢查、不存（引導擔保頁的預覽）：和 {@link #setThresholds} 同一段檢查，訊息一樣。 */
	@Transactional(readOnly = true)
	public void validateThresholds(Long id, BigDecimal warnPercent, BigDecimal callPercent) {
		checkThresholds(findActive(id), warnPercent, callPercent);
	}

	private static void checkThresholds(Loan loan, BigDecimal warnPercent, BigDecimal callPercent) {
		if (loan.getType() != LoanType.PLEDGE) {
			throw new LoanValidationException("只有質押有維持率");
		}
		if (warnPercent == null || callPercent == null) {
			throw new LoanValidationException("請輸入提醒線與追繳線");
		}
		if (callPercent.signum() <= 0 || warnPercent.compareTo(new BigDecimal("1000")) > 0) {
			throw new LoanValidationException("門檻要在 0% 到 1000% 之間");
		}
		if (warnPercent.compareTo(callPercent) <= 0) {
			throw new LoanValidationException("提醒線要高於追繳線");
		}
	}

	/** 一筆質押目前生效的維持率提醒線與追繳線（%）；沒設定時是預設的 150%、130%。 */
	public record Thresholds(BigDecimal warnPercent, BigDecimal callPercent) {
	}

	/** 這筆質押今天生效的門檻（撥款日在未來就用撥款日那天），和資金來源頁顯示的一樣。別人的借款 → 404。 */
	@Transactional(readOnly = true)
	public Thresholds thresholdsOf(Long id) {
		return thresholds(find(id), today());
	}

	private Thresholds thresholds(Loan loan, LocalDate today) {
		LocalDate on = today.isBefore(loan.getStartDate()) ? loan.getStartDate() : today;
		return new Thresholds(percentOrDefault(threshold(loan, RateKind.MAINTENANCE_WARN, on), DEFAULT_WARN),
				percentOrDefault(threshold(loan, RateKind.MAINTENANCE_CALL, on), DEFAULT_CALL));
	}

	/** 只讀不鎖的 {@link #findForUpdate}：別人的 → 404，已作廢 → 和存檔一樣的訊息。 */
	private Loan findActive(Long id) {
		Loan loan = find(id);
		if (loan.isVoided()) {
			throw new LoanValidationException("這筆借款已經作廢");
		}
		return loan;
	}

	// ---------- 查詢 ----------

	@Transactional(readOnly = true)
	public Loan find(Long id) {
		return loans.findByIdAndUserId(id, currentUser.id()).orElseThrow(() -> new NotFoundException("找不到這筆借款"));
	}

	@Transactional(readOnly = true)
	public List<Loan> listMine() {
		return loans.findByUserIdAndVoidedFalseOrderByIdAsc(currentUser.id());
	}

	/** 某一天的剩餘本金（由撥款與還本推算）。 */
	@Transactional(readOnly = true)
	public BigDecimal remainingPrincipal(Loan loan, LocalDate asOf) {
		requireMine(loan);
		return facts.read(loan).remaining(asOf);
	}

	/** 某一天適用的年利率（0.025 = 2.5%）。 */
	@Transactional(readOnly = true)
	public BigDecimal currentRate(Loan loan, LocalDate on) {
		requireMine(loan);
		LoanFacts f = facts.read(loan);
		LocalDate day = on.isBefore(loan.getStartDate()) ? loan.getStartDate() : on;
		return RateChange.rateOn(f.rates(), day);
	}

	/**
	 * 目前登入者所有借款某個月的利息（本月淨資產變動的「利息」，DR-008 應計制、設計文件 6、11-2），四捨五入到元。
	 * 信貸：扣款日在這個月的那幾期；質押：已繳的期間用實際金額按天數分攤，還沒繳的天數用應計數（本月算到今天）。
	 */
	@Transactional(readOnly = true)
	public BigDecimal accruedInterest(YearMonth month) {
		LocalDate today = today();
		BigDecimal total = BigDecimal.ZERO;
		for (Loan loan : listMine()) {
			total = total.add(interestIn(facts.read(loan), month, today));
		}
		return total.setScale(0, RoundingMode.HALF_UP);
	}

	/**
	 * 資金來源頁（設計文件 7）：總負債、加權平均利率、本月利息，以及每筆借款的卡片。
	 */
	@Transactional(readOnly = true)
	public FundingView fundingView() {
		LocalDate today = today();
		YearMonth month = YearMonth.from(today);
		Map<Long, String> accountNames = accounts.findByUserIdOrderByIdAsc(currentUser.id()).stream()
			.collect(Collectors.toMap(CashAccount::getId, CashAccount::getName));
		StockContext stockContext = stockContext();
		List<LoanCard> cards = new ArrayList<>();
		List<LoanRateCalculator.Weighted> weights = new ArrayList<>();
		BigDecimal debt = BigDecimal.ZERO;
		BigDecimal interest = BigDecimal.ZERO;
		for (Loan loan : listMine()) {
			LoanFacts f = facts.read(loan);
			BigDecimal remaining = f.remaining(today);
			BigDecimal rate = RateChange.rateOn(f.rates(), today.isBefore(loan.getStartDate()) ? loan.getStartDate() : today);
			BigDecimal monthInterest = interestIn(f, month, today);
			debt = debt.add(remaining);
			interest = interest.add(monthInterest);
			weights.add(new LoanRateCalculator.Weighted(remaining, rate));
			cards.add(card(loan, f, remaining, rate, monthInterest, accountNames, today, stockContext));
		}
		BigDecimal weighted = LoanRateCalculator.weightedAverage(weights).map(LoanService::percent).orElse(null);
		return new FundingView(debt, weighted, interest.setScale(0, RoundingMode.HALF_UP), cards,
				noticeService.stockAndFundingNotices());
	}

	private LoanCard card(Loan loan, LoanFacts f, BigDecimal remaining, BigDecimal rate, BigDecimal monthInterest,
			Map<Long, String> accountNames, LocalDate today, StockContext sc) {
		boolean paidOff = remaining.signum() <= 0;
		BigDecimal paidInterest = f.active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_INTEREST || m.getType() == CashMovementType.LOAN_FEE)
			.map(CashMovement::getAmount)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		LocalDate nextDue = null;
		BigDecimal nextAmount = null;
		Integer periodsLeft = null;
		if (!paidOff && loan.getRepaymentMethod() == RepaymentMethod.MONTHLY_FIXED) {
			List<Installment> upcoming = InstallmentCalculator.schedule(f.installmentInput()).stream()
				.filter(i -> i.dueDate().isAfter(today))
				.toList();
			periodsLeft = upcoming.size();
			if (!upcoming.isEmpty()) {
				nextDue = upcoming.get(0).dueDate();
				nextAmount = upcoming.get(0).principal().add(upcoming.get(0).interest());
			}
		}
		else if (!paidOff && loan.getRepaymentMethod() == RepaymentMethod.INTEREST_ONLY) {
			nextDue = nextPayDate(loan, today);
			LocalDate from = f.lastInterestPaidBefore(nextDue);
			nextAmount = DailyInterestCalculator.roundUp(
					DailyInterestCalculator.interest(f.startBalance(), f.principalChanges(), f.rates(), from, nextDue));
		}
		// 依還款方式的到期資訊（spec 4.8a-5、4.8a-8）
		BigDecimal monthlyPayment = null;
		BigDecimal accrued = null;
		BigDecimal amountAtDue = null;
		Integer daysToDue = null;
		LocalDate dueDate = loan.getRepaymentMethod() == RepaymentMethod.MONTHLY_FIXED ? null : loan.getDueDate();
		if (loan.getRepaymentMethod() == RepaymentMethod.MONTHLY_FIXED) {
			monthlyPayment = PaymentChange.amountOn(f.payments(), today).map(LoanService::whole).orElse(nextAmount);
		}
		if (loan.getRepaymentMethod() == RepaymentMethod.BULLET && !paidOff) {
			LocalDate from = f.lastInterestPaidBefore(today);
			accrued = DailyInterestCalculator.roundUp(
					DailyInterestCalculator.interest(f.startBalance(), f.principalChanges(), f.rates(), from, today));
			// 已經過期還沒還：利息算到今天（審查 M-3）
			LocalDate until = today.isAfter(dueDate) ? today : dueDate;
			amountAtDue = whole(remaining).add(DailyInterestCalculator.roundUp(DailyInterestCalculator
				.interest(f.startBalance(), f.principalChanges(), f.rates(), from, until)));
		}
		else if (loan.getRepaymentMethod() == RepaymentMethod.INTEREST_ONLY && dueDate != null && !paidOff) {
			amountAtDue = whole(remaining);
		}
		if (dueDate != null && !paidOff) {
			daysToDue = (int) ChronoUnit.DAYS.between(today, dueDate);
		}
		boolean dueSoon = daysToDue != null && daysToDue <= DUE_REMINDER_DAYS;
		LoanUsage usage = sc.portfolio().loans().get(loan.getId());
		BigDecimal undeployed = usage == null ? remaining : usage.undeployed();
		List<LoanHoldingLine> deployedIn = new ArrayList<>();
		BigDecimal unrealized = BigDecimal.ZERO;
		for (Position pos : sc.portfolio().positions().values()) {
			BigDecimal tag = pos.loanTags().getOrDefault(loan.getId(), BigDecimal.ZERO);
			if (tag.signum() <= 0 || pos.shares() == 0) {
				continue;
			}
			deployedIn.add(new LoanHoldingLine(sc.label(pos.stockId()), whole(tag)));
			BigDecimal price = sc.prices().get(pos.stockId());
			BigDecimal loanShares = pos.loanShares().get(loan.getId());
			if (price != null && loanShares != null) {
				// 這筆借款在這檔股票的市值 ＝ 借款買到的股數 × 收盤價（不分到別批較便宜買進的漲幅）
				unrealized = unrealized.add(loanShares.multiply(price).subtract(tag));
			}
		}
		// 借款值不值得：投資賺到（帳面＋已實現＋配息）和已付利息比（spec 第 3 節第 4 頁；賺贏利息就算符合預期）
		boolean invested = usage != null && (usage.deployed().signum() > 0 || usage.realized().signum() != 0
				|| usage.dividends().signum() != 0);
		BigDecimal investGain = usage == null ? BigDecimal.ZERO
				: unrealized.add(usage.realized()).add(usage.dividends());
		// 到期一次付清：還沒付、但已經欠的應計利息也是借錢的成本（spec 4.8a-5）
		BigDecimal cost = accrued == null ? paidInterest : paidInterest.add(accrued);
		BigDecimal netGain = investGain.subtract(cost);
		BigDecimal investReturn = usage == null ? null
				: StockMetricsCalculator.gainPercent(whole(investGain), usage.deployed()).orElse(null);
		String verdict = !invested ? "NOT_INVESTED" : netGain.signum() >= 0 ? "BEAT" : "BEHIND";

		List<CollateralLine> collateral = new ArrayList<>();
		BigDecimal maintenance = null;
		String level = null;
		BigDecimal warn = null;
		BigDecimal call = null;
		if (loan.getType() == LoanType.PLEDGE) {
			Thresholds t = thresholds(loan, today);
			warn = t.warnPercent();
			call = t.callPercent();
			BigDecimal collateralValue = BigDecimal.ZERO;
			boolean priced = false;
			for (PledgeCollateral c : sc.collateralByLoan().getOrDefault(loan.getId(), List.of())) {
				BigDecimal price = sc.prices().get(c.getStockId());
				BigDecimal value = price == null ? null : StockMetricsCalculator.marketValue(c.getShares(), price);
				if (value != null) {
					collateralValue = collateralValue.add(value);
					priced = true;
				}
				collateral.add(new CollateralLine(sc.code(c.getStockId()), sc.label(c.getStockId()), c.getShares(),
						value == null ? null : whole(value)));
			}
			if (priced && !paidOff) {
				maintenance = StockMetricsCalculator.maintenancePercent(collateralValue, remaining).orElse(null);
				level = maintenance == null ? null : StockMetricsCalculator.level(maintenance, warn, call).name();
			}
		}
		return new LoanCard(loan.getId(), loan.getName(), loan.getBank(), loan.getType().label(),
				loan.getOrigin().label(), loan.getType() == LoanType.CREDIT, loan.getType() == LoanType.PLEDGE,
				whole(loan.getOriginalAmount()), whole(remaining), percent(rate),
				monthInterest.setScale(0, RoundingMode.HALF_UP), whole(paidInterest), whole(undeployed), nextDue,
				nextAmount, periodsLeft, accountNames.getOrDefault(loan.getPayAccountId(), "—"), paidOff, deployedIn,
				sc.purchases().getOrDefault(loan.getId(), List.of()),
				whole(netGain), whole(investGain), investReturn, verdict, collateral, maintenance, level, warn, call,
				loan.getRepaymentMethod().label(), monthlyPayment, dueDate, daysToDue, amountAtDue, accrued, dueSoon);
	}

	/** 資金來源頁要用的股票資料：投資組合、股名、最新股價、各質押的擔保股票。 */
	private record StockContext(Portfolio portfolio, Map<Long, Stock> stocks, Map<Long, BigDecimal> prices,
			Map<Long, List<PledgeCollateral>> collateralByLoan, Map<Long, List<LoanPurchaseLine>> purchases) {

		String code(Long stockId) {
			return stocks.get(stockId).getCode();
		}

		String label(Long stockId) {
			Stock s = stocks.get(stockId);
			return s.getCode() + " " + s.getName();
		}

	}

	private StockContext stockContext() {
		Portfolio portfolio = tradeService.portfolio();
		Map<Long, List<PledgeCollateral>> byLoan = collaterals.findByUserId(currentUser.id()).stream()
			.collect(Collectors.groupingBy(PledgeCollateral::getLoanId));
		Set<Long> ids = new HashSet<>(portfolio.positions().keySet());
		byLoan.values().forEach(list -> list.forEach(c -> ids.add(c.getStockId())));
		Map<Long, Stock> stockById = ids.isEmpty() ? Map.of()
				: stocks.findByIdIn(new ArrayList<>(ids)).stream().collect(Collectors.toMap(Stock::getId, s -> s));
		return new StockContext(portfolio, stockById, tradeService.latestPrices(ids), byLoan,
				tradeService.purchasesByLoan());
	}

	/** 這筆質押某一天適用的維持率門檻（比率）；沒設定為 empty。 */
	private Optional<BigDecimal> threshold(Loan loan, RateKind kind, LocalDate on) {
		List<RateChange> versions = rates
			.findByUserIdAndLoanIdOrderByEffectiveDateAscIdAsc(loan.getUserId(), loan.getId()).stream()
			.filter(r -> r.getKind() == kind && !r.getEffectiveDate().isAfter(on))
			.map(r -> new RateChange(r.getEffectiveDate(), r.getValue()))
			.toList();
		return versions.isEmpty() ? Optional.empty() : Optional.of(RateChange.rateOn(versions, on));
	}

	private static BigDecimal percentOrDefault(Optional<BigDecimal> ratio, BigDecimal defaultPercent) {
		return ratio.map(r -> plainPercent(r.multiply(HUNDRED))).orElse(defaultPercent);
	}

	/** 去掉多餘的小數 0，但不變成 1.6E+2 這種科學記號（150、132.5）。 */
	private static BigDecimal plainPercent(BigDecimal percent) {
		BigDecimal stripped = percent.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

	/** 年利率比率 → 表單上的百分比，不四捨五入（利率小數最多 4 位，0.021525 → 2.1525）。 */
	private static BigDecimal exactPercent(BigDecimal ratio) {
		BigDecimal stripped = ratio.multiply(HUNDRED).stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

	/**
	 * 修改借款之後，這筆借款不夠付已經用它買的股票（spec 4.8a-10、DR-051）：還沒確定 → 丟
	 * {@link LoanShortfallException}，整個修改回滾；確定了 → 從最晚的交易開始把這筆借款出的錢往下調，差額改算自有
	 * （不另外扣帳戶的錢）。之後再檢查一次所有借款；別筆借款的問題（或調了也補不回來的）照原本的規則擋下。
	 */
	private void coverShortfall(Loan loan, boolean confirmShortfall) {
		List<AllocationCut> cuts = tradeService.shortfallAdjustments(loan.getId());
		if (!cuts.isEmpty()) {
			BigDecimal shortfall = cuts.stream()
				.map(c -> c.from().subtract(c.to()))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			if (!confirmShortfall) {
				throw new LoanShortfallException(shortfall, cuts, money.plain(shortfall));
			}
			tradeService.reduceLoanAllocations(loan.getId(), cuts, EDIT_REASON);
		}
		requireLoansCovered();
	}

	/** 借款作廢以外的改動（改本金、日期、還本）之後：買股票用掉的錢不能超過借到的。 */
	private void requireLoansCovered() {
		try {
			tradeService.checkLoansCovered();
		}
		catch (TradeValidationException ex) {
			throw new LoanValidationException(ex.getMessage());
		}
	}

	/** 質押下一個繳息日：晚於今天、也晚於起算日的第一個繳息日。 */
	private static LocalDate nextPayDate(Loan loan, LocalDate today) {
		LocalDate after = today.isBefore(loan.getStartDate()) ? loan.getStartDate() : today;
		YearMonth m = YearMonth.from(after);
		while (true) {
			LocalDate due = m.atDay(Math.min(loan.getPayDay(), m.lengthOfMonth()));
			if (due.isAfter(after)) {
				return due;
			}
			m = m.plusMonths(1);
		}
	}

	/** 一筆借款某個月的利息（保留 10 位），含這個月記的開辦費（借款成本，DR-048）。 */
	private static BigDecimal interestIn(LoanFacts f, YearMonth month, LocalDate today) {
		BigDecimal fees = f.active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_FEE && YearMonth.from(m.getMovementDate()).equals(month))
			.map(CashMovement::getAmount)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return accruedIn(f, month, today).add(fees);
	}

	private static BigDecimal accruedIn(LoanFacts f, YearMonth month, LocalDate today) {
		Loan loan = f.loan();
		if (loan.getRepaymentMethod() == RepaymentMethod.MONTHLY_FIXED) {
			List<Installment> schedule = InstallmentCalculator.schedule(f.installmentInput());
			Set<YearMonth> scheduled = schedule.stream().map(Installment::period).collect(Collectors.toSet());
			// 全部提前還清時記的最後一段利息不在攤還表裡，算在記帳那天的月份
			BigDecimal payoff = f.active().stream()
				.filter(m -> m.getType() == CashMovementType.LOAN_INTEREST && m.getPeriod() != null
						&& !scheduled.contains(m.getPeriod()) && YearMonth.from(m.getMovementDate()).equals(month))
				.map(CashMovement::getAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			return LoanAccrualCalculator.creditMonth(schedule, month).add(payoff);
		}
		List<LoanAccrualCalculator.Payment> payments = f.active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_INTEREST)
			.map(m -> new LoanAccrualCalculator.Payment(m.getMovementDate(), m.getAmount()))
			.toList();
		return LoanAccrualCalculator.pledgeMonth(f.startBalance(), f.principalChanges(), f.rates(), loan.getStartDate(),
				payments, month, today);
	}

	/** 比率 → 百分比，顯示到 0.01（HALF_UP，spec 第 5 節）。 */
	private static BigDecimal percent(BigDecimal ratio) {
		return ratio.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
	}

	/** 金額一定是整數元：去掉資料庫的小數位。 */
	private static BigDecimal whole(BigDecimal value) {
		return value.setScale(0, RoundingMode.HALF_UP);
	}

	/**
	 * 修改表單的預設值（金額轉成整數元）：目前的所有欄位，利率是原始利率、扣款金額是原始的銀行扣款、開辦費是目前的金額
	 * （DR-051）。V11 以前建立、還沒有扣款金額（或每月繳息沒有到期日）的借款，還款方式帶 null，照舊的寫法檢查。
	 */
	@Transactional(readOnly = true)
	public LoanForm form(Long id) {
		Loan loan = find(id);
		LoanFacts f = facts.read(loan);
		RepaymentMethod method = loan.getRepaymentMethod();
		boolean legacy = method == RepaymentMethod.MONTHLY_FIXED ? f.payments().isEmpty()
				: method == RepaymentMethod.INTEREST_ONLY && loan.getDueDate() == null;
		BigDecimal payment = method != RepaymentMethod.MONTHLY_FIXED ? null
				: originalPayment(f.payments(), loan.getStartDate()).map(LoanService::whole).orElse(null);
		BigDecimal fee = f.active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_FEE)
			.map(m -> whole(m.getAmount()))
			.findFirst()
			.orElse(null);
		return new LoanForm(loan.getName(), loan.getType(), loan.getBank(), loan.getOrigin(),
				whole(loan.getOriginalAmount()), loan.getStartDate(), whole(loan.getStartPrincipal()),
				exactPercent(originalRate(f.rates(), loan.getStartDate())),
				loan.getDisburseAccountId(), loan.getPayAccountId(), loan.getPayDay(), loan.getRemainingPeriods(),
				loan.getFirstPeriod(), loan.getDueDate(), legacy ? null : method, payment, fee, List.of(), null, null);
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	// ---------- 檢查 ----------

	/**
	 * 回傳轉成比率的年利率（2.5 → 0.025）。
	 * 借款與它的現金異動照真實日期記；早於帳戶初始現金餘額日期的不影響餘額，所以不檢查帳戶起點（DR-047）。
	 */
	private BigDecimal validate(LoanForm form, RepaymentMethod method) {
		checkMethodFields(form, method);
		validateBasics(form);
		if (form.type() == null) {
			throw new LoanValidationException("請選擇類型");
		}
		if (form.origin() == null) {
			throw new LoanValidationException("請選擇新撥款或既有借款");
		}
		checkAmount(form.startPrincipal(), form.origin() == LoanOrigin.NEW ? "撥款金額" : "剩餘本金");
		if (form.origin() == LoanOrigin.EXISTING) {
			checkAmount(form.originalAmount(), "原始借款金額");
			if (form.startPrincipal().compareTo(form.originalAmount()) > 0) {
				throw new LoanValidationException("剩餘本金不能大於原始借款金額");
			}
		}
		else if (form.originalAmount() != null && form.originalAmount().compareTo(form.startPrincipal()) < 0) {
			throw new LoanValidationException("原始借款金額不能小於撥款金額");
		}
		BigDecimal rate = toRate(form.annualRatePercent());
		if (form.startDate() == null) {
			throw new LoanValidationException("請選擇起算日");
		}
		if (form.startDate().isBefore(MIN_DATE)) {
			throw new LoanValidationException("起算日要在 2000-01-01 之後");
		}
		if (form.startDate().isAfter(today())) {
			throw new LoanValidationException("起算日不能晚於今天");
		}
		if (method == RepaymentMethod.MONTHLY_FIXED) {
			if (form.remainingPeriods() == null || form.remainingPeriods() < 1
					|| form.remainingPeriods() > MAX_PERIODS) {
				throw new LoanValidationException("剩餘期數要在 1～600 期");
			}
			if (form.firstPeriod() == null) {
				throw new LoanValidationException("請選擇第一期扣款月份");
			}
			LocalDate firstDue = form.firstPeriod()
				.atDay(Math.min(form.payDay(), form.firstPeriod().lengthOfMonth()));
			if (!firstDue.isAfter(form.startDate())) {
				throw new LoanValidationException("第一期扣款日要在起算日之後");
			}
		}
		// 每月固定還款用不到到期日（期數決定）：留著的舊值不檢查
		if (method != RepaymentMethod.MONTHLY_FIXED && form.dueDate() != null
				&& !form.dueDate().isAfter(form.startDate())) {
			throw new LoanValidationException("到期日要晚於撥款日");
		}
		findMyAccount(form.payAccountId(), "請選擇扣款帳戶");
		if (form.origin() == LoanOrigin.NEW) {
			findMyAccount(form.disburseAccountId(), "請選擇撥入帳戶");
		}
		else if (form.disburseAccountId() != null) {
			throw new LoanValidationException("既有借款不用選撥入帳戶");
		}
		return rate;
	}

	/**
	 * 還款方式和欄位要對得上（spec 4.8a-1、DR-048）：不能只靠畫面藏欄位。form.repaymentMethod() 為 null 的是
	 * V11 以前的寫法（舊的信貸、質押），只檢查資料庫一定要有的欄位。
	 */
	private static void checkMethodFields(LoanForm form, RepaymentMethod method) {
		boolean newStyle = form.repaymentMethod() != null;
		if (method != RepaymentMethod.MONTHLY_FIXED && form.remainingPeriods() != null) {
			throw new LoanValidationException(method.label() + "不用填總期數");
		}
		if (method == RepaymentMethod.BULLET || (newStyle && method == RepaymentMethod.INTEREST_ONLY)) {
			if (form.dueDate() == null) {
				throw new LoanValidationException("請填到期日");
			}
		}
		if (method == RepaymentMethod.MONTHLY_FIXED && newStyle) {
			if (form.monthlyPayment() == null) {
				throw new LoanValidationException("請填銀行每月扣款金額");
			}
			checkAmount(form.monthlyPayment(), "每月扣款金額");
		}
		else if (form.monthlyPayment() != null) {
			throw new LoanValidationException(method.label() + "不用填每月扣款金額");
		}
		if (form.fee() != null && form.fee().signum() != 0) {
			checkAmount(form.fee(), "開辦費");
		}
	}

	/** 新增與修改共用：名稱、銀行、扣款日、扣款帳戶。 */
	private void validateBasics(LoanForm form) {
		String name = CashAccount.normalizeName(form.name());
		if (name == null || name.isEmpty()) {
			throw new LoanValidationException("請輸入名稱");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new LoanValidationException("名稱最多 50 個字");
		}
		String bank = CashAccount.normalizeName(form.bank());
		if (bank == null || bank.isEmpty()) {
			throw new LoanValidationException("請輸入銀行");
		}
		if (bank.length() > MAX_NAME_LENGTH) {
			throw new LoanValidationException("銀行最多 50 個字");
		}
		if (form.payDay() == null || form.payDay() < 1 || form.payDay() > 31) {
			throw new LoanValidationException(form.type() == LoanType.PLEDGE ? "繳息日要在 1～31 號" : "扣款日要在 1～31 號");
		}
		if (form.payAccountId() == null) {
			throw new LoanValidationException("請選擇扣款帳戶");
		}
		if (form.originalAmount() != null) {
			checkAmount(form.originalAmount(), "原始借款金額");
		}
		findMyAccount(form.payAccountId(), "請選擇扣款帳戶");
	}

	private Long validatePrincipal(Loan loan, PrincipalForm form) {
		if (form.date() == null) {
			throw new LoanValidationException("請選擇日期");
		}
		if (form.date().isBefore(loan.getStartDate())) {
			throw new LoanValidationException("日期不能早於起算日");
		}
		if (form.date().isAfter(today())) {
			throw new LoanValidationException("日期不能晚於今天");
		}
		checkAmount(form.amount(), "金額");
		return findMyAccount(form.accountId(), "請選擇帳戶").getId();
	}

	private static void checkAmount(BigDecimal amount, String label) {
		if (amount == null) {
			throw new LoanValidationException("請輸入" + label);
		}
		if (amount.abs().compareTo(MAX_AMOUNT) >= 0) {
			throw new LoanValidationException(label + "太大");
		}
		if (amount.stripTrailingZeros().scale() > 0) {
			throw new LoanValidationException("金額只能是整數元");
		}
		if (amount.signum() <= 0) {
			throw new LoanValidationException(label + "要大於 0");
		}
	}

	/** 百分比 → 比率；0～30%，小數最多 4 位。 */
	private static BigDecimal toRate(BigDecimal percent) {
		if (percent == null) {
			throw new LoanValidationException("請輸入年利率");
		}
		if (percent.signum() < 0 || percent.compareTo(MAX_RATE_PERCENT) > 0) {
			throw new LoanValidationException("年利率要在 0～30% 之間");
		}
		if (percent.stripTrailingZeros().scale() > 4) {
			throw new LoanValidationException("年利率小數最多 4 位");
		}
		return percent.divide(HUNDRED, 6, RoundingMode.UNNECESSARY);
	}

	private CashAccount findMyAccount(Long accountId, String missingMessage) {
		if (accountId == null) {
			throw new LoanValidationException(missingMessage);
		}
		return accounts.findByIdAndUserId(accountId, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這個帳戶"));
	}

	/** 修改、還本、作廢前讀取並鎖住；作廢的借款不能再操作。 */
	private Loan findForUpdate(Long id) {
		Loan loan = loans.findForUpdate(id, currentUser.id()).orElseThrow(() -> new NotFoundException("找不到這筆借款"));
		if (loan.isVoided()) {
			throw new LoanValidationException("這筆借款已經作廢");
		}
		return loan;
	}

	private void requireMine(Loan loan) {
		if (!Objects.equals(loan.getUserId(), currentUser.id())) {
			throw new NotFoundException("找不到這筆借款");
		}
	}

	private static <T> T orElse(T submitted, T current) {
		return submitted == null ? current : submitted;
	}

	/** 表單沒送（之後的 REST API）就當作沒改。 */
	private static boolean changed(Object submitted, Object current) {
		return submitted != null && !Objects.equals(submitted, current);
	}

}
