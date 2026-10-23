package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.DailyInterestCalculator;
import com.sideproject.stockledger.calculator.Installment;
import com.sideproject.stockledger.calculator.InstallmentCalculator;
import com.sideproject.stockledger.calculator.LoanAccrualCalculator;
import com.sideproject.stockledger.calculator.LoanRateCalculator;
import com.sideproject.stockledger.calculator.LoanUsage;
import com.sideproject.stockledger.calculator.Portfolio;
import com.sideproject.stockledger.calculator.Position;
import com.sideproject.stockledger.calculator.StockMetricsCalculator;
import com.sideproject.stockledger.calculator.RateChange;
import com.sideproject.stockledger.dto.CollateralLine;
import com.sideproject.stockledger.dto.FundingView;
import com.sideproject.stockledger.dto.LoanCard;
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
import com.sideproject.stockledger.entity.Stock;
import com.sideproject.stockledger.repository.CashAccountRepository;
import com.sideproject.stockledger.repository.CashMovementRepository;
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
	private static final int MAX_NAME_LENGTH = 50;
	private static final int MAX_PERIODS = 600;

	static final String LOCKED = "起算日、起算本金、剩餘期數、第一期已經開始記帳，不能再改";

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
	private final Clock clock;
	private final TradeService tradeService;
	private final StockRepository stocks;
	private final PledgeCollateralRepository collaterals;

	public LoanService(LoanRepository loans, RateSettingRepository rates, CashAccountRepository accounts,
			CashMovementRepository movements, LoanFactsReader facts, LoanPostingService posting,
			CashMovementService cashMovements, MoneyFormatter money, CurrentUser currentUser, Clock clock,
			TradeService tradeService, StockRepository stocks, PledgeCollateralRepository collaterals) {
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
	public Loan create(LoanForm form) {
		Long userId = currentUser.id();
		BigDecimal rate = validate(form);
		BigDecimal original = form.origin() == LoanOrigin.NEW && form.originalAmount() == null ? form.startPrincipal()
				: form.originalAmount();
		LocalDateTime now = LocalDateTime.now(clock);
		Loan loan = loans.save(Loan.create(userId, form.name(), form.type(), form.bank(), original, form.origin(),
				form.startDate(), form.startPrincipal(), form.disburseAccountId(), form.payAccountId(), form.payDay(),
				form.remainingPeriods(), form.firstPeriod(), form.dueDate(), now));
		rates.save(RateSetting.loanRate(userId, loan.getId(), rate, form.startDate(), now));
		if (form.origin() == LoanOrigin.NEW) {
			movements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_DISBURSEMENT, form.startDate(),
					form.disburseAccountId(), form.startPrincipal(), null, now));
		}
		posting.postDue(loan, today());
		return loan;
	}

	/**
	 * 修改名稱、銀行、原始金額、扣款日、帳戶、到期日；扣款日或帳戶改了從當月起生效（同 DR-037）。
	 * 起點欄位（起算日、起算本金、剩餘期數、第一期）只有「既有借款、還沒有任何異動」時可以改（設計文件 11-5）；類型與方式不能改。
	 */
	@Transactional
	public Loan edit(Long id, LoanForm form) {
		Loan loan = findForUpdate(id);
		if (changed(form.type(), loan.getType()) || changed(form.origin(), loan.getOrigin())) {
			throw new LoanValidationException("類型、新撥款或既有借款建立後不能改");
		}
		boolean startChanged = changed(form.startDate(), loan.getStartDate())
				|| changedAmount(form.startPrincipal(), loan.getStartPrincipal())
				|| changed(form.remainingPeriods(), loan.getRemainingPeriods())
				|| changed(form.firstPeriod(), loan.getFirstPeriod());
		LoanFacts f = facts.read(loan);
		if (startChanged && (loan.getOrigin() != LoanOrigin.EXISTING || !f.movements().isEmpty())) {
			throw new LoanValidationException(LOCKED);
		}
		LoanForm merged = new LoanForm(form.name(), loan.getType(), form.bank(), loan.getOrigin(),
				form.originalAmount() == null ? loan.getOriginalAmount() : form.originalAmount(),
				orElse(form.startDate(), loan.getStartDate()), orElse(form.startPrincipal(), loan.getStartPrincipal()),
				f.rates().get(0).annualRate().multiply(HUNDRED), loan.getDisburseAccountId(), form.payAccountId(),
				form.payDay(),
				orElse(form.remainingPeriods(), loan.getRemainingPeriods()),
				orElse(form.firstPeriod(), loan.getFirstPeriod()), form.dueDate());
		validate(merged);
		if (startChanged) {
			loan.changeStart(merged.startDate(), merged.startPrincipal(), merged.remainingPeriods(),
					merged.firstPeriod());
			// 起算日提前時，第一個利率版本也要從新的起算日生效
			RateChange first = f.rates().get(0);
			if (merged.startDate().isBefore(first.effectiveDate())) {
				rates.save(RateSetting.loanRate(currentUser.id(), loan.getId(), first.annualRate(), merged.startDate(),
						LocalDateTime.now(clock)));
			}
		}
		loan.change(merged.name(), merged.bank(), merged.originalAmount(), merged.payAccountId(), merged.payDay(),
				merged.dueDate(), YearMonth.from(today()));
		posting.postDue(loan, today());
		requireLoansCovered();
		return loan;
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
		Long accountId = validatePrincipal(loan, form);
		return movements.saveAndFlush(CashMovement.ofLoan(loan, CashMovementType.LOAN_DISBURSEMENT, form.date(),
				accountId, form.amount(), null, LocalDateTime.now(clock)));
	}

	/**
	 * 作廢借款（例如起算本金打錯又已經開始記帳）：連同所有撥款、還本、利息一起作廢並寫修改紀錄，
	 * 之後不出現在資金來源頁、不再自動記帳。不硬刪除。
	 */
	@Transactional
	public void voidLoan(Long id, String reason) {
		Loan loan = findForUpdate(id);
		if (tradeService.loanInUse(loan.getId())) {
			throw new LoanValidationException("還有股票交易用到這筆借款當資金來源；請先修改或作廢那些交易");
		}
		cashMovements.voidLoanMovements(loan.getId(), reason);
		loan.markVoided();
	}

	/**
	 * 設定質押的擔保股票股數（spec 5.5）；0 ＝ 拿掉。股數不能超過目前持有股數。
	 * 擔保品是設定、不是事件，所以直接改或刪（我的判斷，見問題清單）。
	 */
	@Transactional
	public void setCollateral(Long id, String stockCode, Long shares) {
		Loan loan = findForUpdate(id);
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
		Long userId = currentUser.id();
		Optional<PledgeCollateral> existing = collaterals.findByUserIdAndLoanIdAndStockId(userId, loan.getId(),
				stock.getId());
		if (shares == 0) {
			existing.ifPresent(collaterals::delete);
			return;
		}
		Position position = tradeService.portfolio().positions().get(stock.getId());
		long held = position == null ? 0 : position.shares();
		// 同一檔股票可能同時擔保好幾筆質押：合計不能超過持股
		long otherLoans = collaterals.findByUserId(userId).stream()
			.filter(c -> c.getStockId().equals(stock.getId()) && !c.getLoanId().equals(loan.getId()))
			.mapToLong(PledgeCollateral::getShares)
			.sum();
		if (shares + otherLoans > held) {
			throw new LoanValidationException(otherLoans == 0
					? "擔保股數不能超過持有股數（" + money.plain(BigDecimal.valueOf(held)) + " 股）"
					: "擔保股數加上其他質押的 " + money.plain(BigDecimal.valueOf(otherLoans)) + " 股，超過持有股數（"
							+ money.plain(BigDecimal.valueOf(held)) + " 股）");
		}
		existing.ifPresentOrElse(c -> c.setShares(shares),
				() -> collaterals.save(PledgeCollateral.of(userId, loan.getId(), stock.getId(), shares)));
	}

	/** 設定這筆質押的維持率提醒線與追繳線（%），從今天起生效（spec 5.5、DR-007；沒設定時 150%、130%）。 */
	@Transactional
	public void setThresholds(Long id, BigDecimal warnPercent, BigDecimal callPercent) {
		Loan loan = findForUpdate(id);
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
		LocalDate effective = today().isBefore(loan.getStartDate()) ? loan.getStartDate() : today();
		LocalDateTime now = LocalDateTime.now(clock);
		rates.save(RateSetting.loanThreshold(currentUser.id(), loan.getId(), RateKind.MAINTENANCE_WARN,
				warnPercent.divide(HUNDRED, 10, RoundingMode.HALF_UP), effective, now));
		rates.save(RateSetting.loanThreshold(currentUser.id(), loan.getId(), RateKind.MAINTENANCE_CALL,
				callPercent.divide(HUNDRED, 10, RoundingMode.HALF_UP), effective, now));
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
		return new FundingView(debt, weighted, interest.setScale(0, RoundingMode.HALF_UP), cards);
	}

	private LoanCard card(Loan loan, LoanFacts f, BigDecimal remaining, BigDecimal rate, BigDecimal monthInterest,
			Map<Long, String> accountNames, LocalDate today, StockContext sc) {
		boolean paidOff = remaining.signum() <= 0;
		BigDecimal paidInterest = f.active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_INTEREST)
			.map(CashMovement::getAmount)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		LocalDate nextDue = null;
		BigDecimal nextAmount = null;
		Integer periodsLeft = null;
		if (!paidOff && loan.getType() == LoanType.CREDIT) {
			List<Installment> upcoming = InstallmentCalculator.schedule(f.installmentInput()).stream()
				.filter(i -> i.dueDate().isAfter(today))
				.toList();
			periodsLeft = upcoming.size();
			if (!upcoming.isEmpty()) {
				nextDue = upcoming.get(0).dueDate();
				nextAmount = upcoming.get(0).principal().add(upcoming.get(0).interest());
			}
		}
		else if (!paidOff) {
			nextDue = nextPayDate(loan, today);
			LocalDate from = f.lastInterestPaidBefore(nextDue);
			nextAmount = DailyInterestCalculator.roundUp(
					DailyInterestCalculator.interest(f.startBalance(), f.principalChanges(), f.rates(), from, nextDue));
		}
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
		BigDecimal netGain = investGain.subtract(paidInterest);
		BigDecimal investReturn = usage == null ? null
				: StockMetricsCalculator.gainPercent(whole(investGain), usage.deployed()).orElse(null);
		String verdict = !invested ? "NOT_INVESTED" : netGain.signum() >= 0 ? "BEAT" : "BEHIND";

		List<CollateralLine> collateral = new ArrayList<>();
		BigDecimal maintenance = null;
		String level = null;
		BigDecimal warn = null;
		BigDecimal call = null;
		if (loan.getType() == LoanType.PLEDGE) {
			LocalDate on = today.isBefore(loan.getStartDate()) ? loan.getStartDate() : today;
			warn = percentOrDefault(threshold(loan, RateKind.MAINTENANCE_WARN, on), DEFAULT_WARN);
			call = percentOrDefault(threshold(loan, RateKind.MAINTENANCE_CALL, on), DEFAULT_CALL);
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
				whole(netGain), whole(investGain), investReturn, verdict, collateral, maintenance, level, warn, call);
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

	/** 一筆借款某個月的利息（保留 10 位）。 */
	private static BigDecimal interestIn(LoanFacts f, YearMonth month, LocalDate today) {
		Loan loan = f.loan();
		if (loan.getType() == LoanType.CREDIT) {
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

	/** 修改表單的預設值（金額轉成整數元；利率不在這裡改，用利率版本）。 */
	@Transactional(readOnly = true)
	public LoanForm form(Long id) {
		Loan loan = find(id);
		return new LoanForm(loan.getName(), loan.getType(), loan.getBank(), loan.getOrigin(),
				whole(loan.getOriginalAmount()), loan.getStartDate(), whole(loan.getStartPrincipal()), null,
				loan.getDisburseAccountId(), loan.getPayAccountId(), loan.getPayDay(), loan.getRemainingPeriods(),
				loan.getFirstPeriod(), loan.getDueDate());
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	// ---------- 檢查 ----------

	/**
	 * 回傳轉成比率的年利率（2.5 → 0.025）。
	 * 借款與它的現金異動照真實日期記；早於帳戶初始現金餘額日期的不影響餘額，所以不檢查帳戶起點（DR-047）。
	 */
	private BigDecimal validate(LoanForm form) {
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
		if (form.type() == LoanType.CREDIT) {
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
		if (form.type() == LoanType.PLEDGE && form.dueDate() != null && form.dueDate().isBefore(form.startDate())) {
			throw new LoanValidationException("到期日不能早於起算日");
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

	private static boolean changedAmount(BigDecimal submitted, BigDecimal current) {
		return submitted != null && submitted.compareTo(current) != 0;
	}

}
