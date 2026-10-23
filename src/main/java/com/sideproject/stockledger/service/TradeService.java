package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.FeeCalculator;
import com.sideproject.stockledger.calculator.MonthlyStockChange;
import com.sideproject.stockledger.calculator.MonthlyStockChangeCalculator;
import com.sideproject.stockledger.calculator.Portfolio;
import com.sideproject.stockledger.calculator.PortfolioCalculator;
import com.sideproject.stockledger.calculator.Position;
import com.sideproject.stockledger.calculator.RealizedSale;
import com.sideproject.stockledger.calculator.StockMetricsCalculator;
import com.sideproject.stockledger.calculator.TradeEvent;
import com.sideproject.stockledger.dto.AllocationLine;
import com.sideproject.stockledger.dto.DividendForm;
import com.sideproject.stockledger.dto.DividendSuggestion;
import com.sideproject.stockledger.dto.HoldingRow;
import com.sideproject.stockledger.dto.SaleRow;
import com.sideproject.stockledger.dto.StocksView;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.dto.TradeRow;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Dividend;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.PledgeCollateral;
import com.sideproject.stockledger.entity.RateKind;
import com.sideproject.stockledger.entity.SourceAllocation;
import com.sideproject.stockledger.entity.Stock;
import com.sideproject.stockledger.entity.StockPrice;
import com.sideproject.stockledger.entity.Trade;
import com.sideproject.stockledger.entity.TradeRevision;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.repository.CashAccountRepository;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.DividendRepository;
import com.sideproject.stockledger.repository.LoanRepository;
import com.sideproject.stockledger.repository.PledgeCollateralRepository;
import com.sideproject.stockledger.repository.SourceAllocationRepository;
import com.sideproject.stockledger.repository.StockPriceRepository;
import com.sideproject.stockledger.repository.StockRepository;
import com.sideproject.stockledger.repository.TradeRepository;
import com.sideproject.stockledger.repository.TradeRevisionRepository;
import com.sideproject.stockledger.security.CurrentUser;

/**
 * 股票交易（設計文件 2026-10-23-stocks、spec 4.1、4.2、4.4、4.7、5.1～5.6）：期初持股、買進、賣出、配股、配息入帳、
 * 修改與作廢，以及股票頁的數字。買進與賣出自動記一筆交割戶的現金異動。
 * 方法參數故意沒有 userId：一律用 {@link CurrentUser#id()}；別人的交易、帳戶、借款在這裡等於「不存在」（404）。
 */
@Service
public class TradeService {

	private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e12");
	private static final BigDecimal MAX_PRICE = new BigDecimal("1e8");
	private static final long MAX_SHARES = 1_000_000_000_000L;
	private static final int RECENT_SALES = 10;
	/** 除息後多久內還沒記入帳就提醒（我的判斷：配息通常在除息後一個月內發放）。 */
	private static final int SUGGEST_DAYS = 180;

	/** 個人費率沒設定時的預設（spec 第 5 節：最低手續費整股 20 元、零股 1 元；沒有折扣）。 */
	private static final BigDecimal DEFAULT_DISCOUNT = BigDecimal.ONE;
	private static final BigDecimal DEFAULT_MIN_FEE_BOARD = new BigDecimal("20");
	private static final BigDecimal DEFAULT_MIN_FEE_ODD = BigDecimal.ONE;

	static final String OVERSOLD = "賣出股數超過持有股數";

	private final TradeRepository trades;
	private final TradeRevisionRepository revisions;
	private final SourceAllocationRepository allocations;
	private final StockRepository stocks;
	private final StockPriceRepository prices;
	private final DividendRepository dividends;
	private final CashAccountRepository accounts;
	private final CashMovementRepository movements;
	private final CashMovementService cashMovements;
	private final LoanRepository loans;
	private final RateSettingService rates;
	private final MoneyFormatter money;
	private final CurrentUser currentUser;
	private final Clock clock;
	private final PortfolioReader portfolioReader;
	private final PledgeCollateralRepository collaterals;

	public TradeService(TradeRepository trades, TradeRevisionRepository revisions,
			SourceAllocationRepository allocations, StockRepository stocks, StockPriceRepository prices,
			DividendRepository dividends, CashAccountRepository accounts, CashMovementRepository movements,
			CashMovementService cashMovements, LoanRepository loans,
			RateSettingService rates, MoneyFormatter money, CurrentUser currentUser, Clock clock, PortfolioReader portfolioReader,
			PledgeCollateralRepository collaterals) {
		this.collaterals = collaterals;
		this.portfolioReader = portfolioReader;
		this.trades = trades;
		this.revisions = revisions;
		this.allocations = allocations;
		this.stocks = stocks;
		this.prices = prices;
		this.dividends = dividends;
		this.accounts = accounts;
		this.movements = movements;
		this.cashMovements = cashMovements;
		this.loans = loans;
		this.rates = rates;
		this.money = money;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	/** 交易的計算結果（新增、修改、試算共用）。 */
	public record Priced(Stock stock, LocalDate date, Long accountId, long shares, BigDecimal price, BigDecimal fee,
			BigDecimal tax, BigDecimal amount, Map<Long, BigDecimal> loanAllocations) {
	}

	// ---------- 新增、修改、作廢 ----------

	/** 新增交易。買進與賣出自動記一筆交割戶的現金異動。 */
	@Transactional
	public Trade create(TradeForm form) {
		Long userId = currentUser.id();
		if (form.type() == null) {
			throw new TradeValidationException("請選擇交易類型");
		}
		Priced p = price(form.type(), form, null);
		Trade trade = trades.saveAndFlush(Trade.create(userId, form.type(), p.date(), p.stock().getId(), p.accountId(),
				p.shares(), p.price(), p.fee(), p.tax(), p.amount(), LocalDateTime.now(clock)));
		saveAllocations(trade, p.loanAllocations());
		checkConsistency(trade, p.loanAllocations().keySet());
		if (form.type() == TradeType.BUY || form.type() == TradeType.SELL) {
			movements.saveAndFlush(CashMovement.ofTrade(trade, cashType(trade.getType()), p.stock().getName(),
					LocalDateTime.now(clock)));
		}
		return trade;
	}

	/** 修改交易（類型與股票不能改）：每個改到的欄位寫修改紀錄，自動記的現金異動跟著改。 */
	@Transactional
	public Trade edit(Long id, TradeForm form, String reason) {
		Long userId = currentUser.id();
		Trade trade = findMineForEdit(id);
		if (form.type() != null && form.type() != trade.getType()) {
			throw new TradeValidationException("交易類型不能改；請作廢後重新記一筆");
		}
		Stock stock = stocks.findById(trade.getStockId()).orElseThrow();
		if (form.stockCode() != null && !form.stockCode().equals(stock.getCode())) {
			throw new TradeValidationException("股票不能改；請作廢後重新記一筆");
		}
		// 改了股數或價格，但手續費、證交稅、實際入帳還是舊的數字（表單帶入的）→ 視為沒填，依新的股數與價格重算
		boolean repriced = !Objects.equals(form.shares(), trade.getShares())
				|| !sameAmount(form.price(), trade.getPrice());
		TradeForm withStock = new TradeForm(trade.getType(), form.date(), stock.getCode(), form.accountId(),
				form.shares(), form.price(), staleOrSubmitted(repriced, form.fee(), trade.getFee()),
				staleOrSubmitted(repriced, form.tax(), trade.getTax()),
				trade.getType() == TradeType.SELL ? staleOrSubmitted(repriced, form.amount(), trade.getAmount()) : null,
				form.allocations());
		Priced p = price(trade.getType(), withStock, trade.getId());
		String clean = cleanReason(reason);
		Map<String, String> before = snapshot(trade, allocationText(userId, trade.getId()));
		trade.change(p.date(), p.accountId(), p.shares(), p.price(), p.fee(), p.tax(), p.amount());
		trades.flush();
		allocations.deleteForTrade(userId, trade.getId());
		saveAllocations(trade, p.loanAllocations());
		Map<String, String> after = snapshot(trade, allocationText(userId, trade.getId()));
		LocalDateTime now = LocalDateTime.now(clock);
		before.forEach((field, old) -> {
			if (!Objects.equals(old, after.get(field))) {
				revisions.save(TradeRevision.of(trade.getId(), userId, now, TradeRevision.EDIT, field, old,
						after.get(field), clean));
			}
		});
		checkConsistency(trade, p.loanAllocations().keySet());
		movements.findByUserIdAndTradeIdAndVoidedFalse(userId, trade.getId())
			.ifPresent(m -> cashMovements.followTrade(m.getId(), trade, clean));
		return trade;
	}

	/** 作廢交易：連同自動記的現金異動一起作廢（不硬刪除）。 */
	@Transactional
	public void voidTrade(Long id, String reason) {
		Long userId = currentUser.id();
		Trade trade = findMineForEdit(id);
		String clean = cleanReason(reason);
		revisions.save(TradeRevision.of(trade.getId(), userId, LocalDateTime.now(clock), TradeRevision.VOID, "voided",
				"false", "true", clean));
		trade.markVoided();
		trades.flush();
		movements.findByUserIdAndTradeIdAndVoidedFalse(userId, trade.getId())
			.ifPresent(m -> cashMovements.voidForTrade(m.getId(), clean));
		checkConsistency(trade, Set.of());
	}

	/** 配息入帳：記一筆「配息」現金異動（實際入帳金額與二代健保扣繳額）。 */
	@Transactional
	public CashMovement recordDividend(DividendForm form) {
		Long userId = currentUser.id();
		checkDate(form.date());
		Stock stock = findStock(form.stockCode());
		Long accountId = findMyAccount(form.accountId()).getId();
		checkNotBeforeOpening(accountId, form.date());
		checkWhole(form.amount(), "實際入帳金額", true);
		BigDecimal nhi = form.nhi() == null ? BigDecimal.ZERO : form.nhi();
		checkWhole(nhi, "二代健保", false);
		return movements.saveAndFlush(CashMovement.ofDividend(userId, form.date(), accountId, stock.getId(),
				form.amount(), nhi, stock.getName(), LocalDateTime.now(clock)));
	}

	/** 試算：依目前輸入算出手續費、證交稅、總成本或預估入帳（表單上的「試算」按鈕）。 */
	@Transactional(readOnly = true)
	public Priced quote(TradeForm form) {
		if (form.type() == null) {
			throw new TradeValidationException("請選擇交易類型");
		}
		return price(form.type(), form, null);
	}

	// ---------- 查詢 ----------

	@Transactional(readOnly = true)
	public Trade find(Long id) {
		return trades.findByIdAndUserId(id, currentUser.id()).orElseThrow(() -> new NotFoundException("找不到這筆交易"));
	}

	/** 修改表單的預設值。 */
	@Transactional(readOnly = true)
	public TradeForm form(Long id) {
		Trade t = find(id);
		Stock stock = stocks.findById(t.getStockId()).orElseThrow();
		List<AllocationLine> lines = allocations.findByUserIdAndTradeId(currentUser.id(), t.getId()).stream()
			.map(a -> new AllocationLine(a.getLoanId(), whole(a.getAmount())))
			.toList();
		BigDecimal price = t.getType() == TradeType.OPENING
				? t.getAmount().divide(BigDecimal.valueOf(t.getShares()), 4, RoundingMode.HALF_UP)
				: t.getPrice();
		return new TradeForm(t.getType(), t.getTradeDate(), stock.getCode(), t.getAccountId(), t.getShares(),
				price == null ? null : trim(price), wholeOrNull(t.getFee()), wholeOrNull(t.getTax()),
				t.getType() == TradeType.SELL ? whole(t.getAmount()) : null, lines);
	}

	/** 目前登入者的投資組合（資金來源頁、總覽也用）。 */
	@Transactional(readOnly = true)
	public Portfolio portfolio() {
		return portfolioOf(currentUser.id());
	}

	/** 股票頁（spec 第 3 節第 3 頁）。 */
	@Transactional(readOnly = true)
	public StocksView view() {
		Long userId = currentUser.id();
		LocalDate today = today();
		List<Trade> all = trades.findByUserIdOrderByTradeDateDescIdDesc(userId);
		Portfolio portfolio = portfolioOf(userId);
		Set<Long> stockIds = new HashSet<>(portfolio.positions().keySet());
		all.forEach(t -> stockIds.add(t.getStockId()));
		Map<Long, Stock> stockById = stocks.findByIdIn(new ArrayList<>(stockIds)).stream()
			.collect(Collectors.toMap(Stock::getId, s -> s));
		Map<Long, BigDecimal> latest = latestPrices(stockIds, today);
		Map<Long, List<Dividend>> notices = stockIds.isEmpty() ? Map.of()
				: dividends.findByStockIdInOrderByExDateDesc(stockIds).stream()
					.collect(Collectors.groupingBy(Dividend::getStockId, LinkedHashMap::new, Collectors.toList()));
		Map<Long, String> loanNames = loans.findByUserIdAndVoidedFalseOrderByIdAsc(userId).stream()
			.collect(Collectors.toMap(Loan::getId, Loan::getName));

		List<HoldingRow> holdings = new ArrayList<>();
		BigDecimal marketValue = BigDecimal.ZERO;
		BigDecimal pricedCost = BigDecimal.ZERO;
		BigDecimal cost = BigDecimal.ZERO;
		BigDecimal dividendTotal = BigDecimal.ZERO;
		BigDecimal annualTotal = null;
		for (Position pos : portfolio.positions().values()) {
			dividendTotal = dividendTotal.add(pos.dividends());
			if (pos.shares() == 0) {
				continue;
			}
			Stock stock = stockById.get(pos.stockId());
			BigDecimal price = latest.get(pos.stockId());
			BigDecimal mv = price == null ? null : StockMetricsCalculator.marketValue(pos.shares(), price);
			BigDecimal annual = annualDividend(stock, notices.getOrDefault(pos.stockId(), List.of()), pos.shares());
			cost = cost.add(pos.cost());
			if (mv != null) {
				marketValue = marketValue.add(mv);
				pricedCost = pricedCost.add(pos.cost());
			}
			if (annual != null) {
				annualTotal = annualTotal == null ? annual : annualTotal.add(annual);
			}
			holdings.add(new HoldingRow(pos.stockId(), stock.getCode(), stock.getName(), pos.shares(),
					pos.averageCost().setScale(2, RoundingMode.HALF_UP), whole(pos.cost()), price,
					mv == null ? null : whole(mv), mv == null ? null : whole(mv.subtract(pos.cost())),
					mv == null ? null : StockMetricsCalculator.returnPercent(mv, pos.cost()).orElse(null),
					mv == null ? null
							: StockMetricsCalculator.totalReturnPercent(mv, pos.dividends(), pos.cost()).orElse(null),
					whole(pos.dividends()), annual, stock.getDividendsPerYear(), sourcesLabel(pos, loanNames)));
		}
		holdings.sort(Comparator.comparing((HoldingRow h) -> h.marketValue() == null ? h.cost() : h.marketValue())
			.reversed());
		BigDecimal unrealized = marketValue.subtract(pricedCost);
		return new StocksView(whole(marketValue), whole(cost), whole(unrealized),
				StockMetricsCalculator.returnPercent(marketValue, pricedCost).orElse(null), whole(dividendTotal),
				annualTotal, prices.findLatestTradeDate().orElse(null), stocks.count() == 0, holdings,
				recentSales(all, portfolio, stockById), tradeRows(userId, all, stockById),
				suggestions(userId, portfolio, today), currentUser.isAdmin());
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	// ---------- 計算 ----------

	/** 檢查輸入並算出手續費、證交稅、總成本或入帳金額。selfId 是修改中的那筆（新增時為 null）。 */
	private Priced price(TradeType type, TradeForm form, Long selfId) {
		checkDate(form.date());
		Stock stock = findStock(form.stockCode());
		if (form.shares() == null || form.shares() <= 0 || form.shares() > MAX_SHARES) {
			throw new TradeValidationException("請輸入股數（正整數）");
		}
		long shares = form.shares();
		boolean cash = type == TradeType.BUY || type == TradeType.SELL;
		Long accountId = null;
		if (cash) {
			accountId = findMyAccount(form.accountId()).getId();
			checkNotBeforeOpening(accountId, form.date());
		}
		else if (form.accountId() != null && type == TradeType.STOCK_DIVIDEND) {
			throw new TradeValidationException("配股不用選帳戶");
		}
		Map<Long, BigDecimal> loanAllocations = allocationsOf(type, form.allocations());
		BigDecimal price = null;
		BigDecimal fee = null;
		BigDecimal tax = null;
		BigDecimal amount;
		switch (type) {
			case OPENING -> {
				price = checkPrice(form.price(), "請輸入平均成本");
				amount = price.multiply(BigDecimal.valueOf(shares)).setScale(0, RoundingMode.HALF_UP);
			}
			case BUY -> {
				price = checkPrice(form.price(), "請輸入成交價");
				BigDecimal tradeAmount = FeeCalculator.tradeAmount(shares, price);
				FeeCalculator.Rates r = feeRates(stock, form.date());
				fee = form.fee() == null ? FeeCalculator.fee(tradeAmount, shares, r) : checkWhole(form.fee(), "手續費", false);
				amount = tradeAmount.add(fee);
			}
			case SELL -> {
				price = checkPrice(form.price(), "請輸入成交價");
				BigDecimal tradeAmount = FeeCalculator.tradeAmount(shares, price);
				FeeCalculator.Rates r = feeRates(stock, form.date());
				fee = form.fee() == null ? FeeCalculator.fee(tradeAmount, shares, r) : checkWhole(form.fee(), "手續費", false);
				tax = form.tax() == null ? FeeCalculator.tax(tradeAmount, r) : checkWhole(form.tax(), "證交稅", false);
				amount = form.amount() == null ? FeeCalculator.sellProceeds(tradeAmount, fee, tax)
						: checkWhole(form.amount(), "實際入帳金額", true);
				if (amount.signum() <= 0) {
					throw new TradeValidationException("實際入帳金額要大於 0");
				}
			}
			case STOCK_DIVIDEND -> amount = BigDecimal.ZERO;
			default -> throw new TradeValidationException("請選擇交易類型");
		}
		BigDecimal loanTotal = loanAllocations.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		if (loanTotal.compareTo(amount) > 0) {
			throw new TradeValidationException("資金來源加總不能超過總成本 " + money.plain(amount) + " 元");
		}
		return new Priced(stock, form.date(), accountId, shares, price, fee, tax, amount, loanAllocations);
	}

	/** 交易日適用的手續費率、折扣、最低手續費、證交稅率（spec 4.5；個人費率沒設定時用預設）。 */
	private FeeCalculator.Rates feeRates(Stock stock, LocalDate date) {
		return new FeeCalculator.Rates(
				rates.findEffective(RateKind.BROKERAGE_FEE_RATE, date).orElse(new BigDecimal("0.001425")),
				rates.findEffective(RateKind.BROKERAGE_DISCOUNT, date).orElse(DEFAULT_DISCOUNT),
				rates.findEffective(RateKind.MIN_FEE_BOARD_LOT, date).orElse(DEFAULT_MIN_FEE_BOARD),
				rates.findEffective(RateKind.MIN_FEE_ODD_LOT, date).orElse(DEFAULT_MIN_FEE_ODD),
				rates.findEffective(stock.getKind().taxRateKind(), date).orElse(BigDecimal.ZERO));
	}

	/**
	 * 寫入後檢查整體一致：期初持股每檔一筆而且最早、賣出不超過持有、借款出的錢不超過尚未投入。
	 * 在同一個交易裡丟出例外時，整筆退回。
	 */
	private void checkConsistency(Trade trade, Set<Long> loanIds) {
		Long userId = currentUser.id();
		List<Trade> active = trades.findByUserIdAndVoidedFalse(userId);
		List<Trade> sameStock = active.stream().filter(t -> t.getStockId().equals(trade.getStockId())).toList();
		List<Trade> openings = sameStock.stream().filter(t -> t.getType() == TradeType.OPENING).toList();
		if (openings.size() > 1) {
			throw new TradeValidationException("這檔股票已經有期初持股");
		}
		if (!openings.isEmpty()) {
			LocalDate openingDate = openings.get(0).getTradeDate();
			boolean earlier = sameStock.stream()
				.anyMatch(t -> t.getType() != TradeType.OPENING && !t.getTradeDate().isAfter(openingDate));
			if (earlier) {
				throw new TradeValidationException(trade.getType() == TradeType.OPENING ? "期初持股要早於這檔股票的所有交易"
						: "日期要晚於這檔股票的期初持股日期（" + openingDate + "）");
			}
		}
		Portfolio portfolio;
		try {
			portfolio = portfolioOf(userId);
		}
		catch (IllegalArgumentException ex) {
			throw new TradeValidationException(trade.getType() == TradeType.SELL ? OVERSOLD : "這樣之後的賣出會超過持有股數");
		}
		checkCollateral(userId, portfolio, trade.getStockId());
		for (Long loanId : loanIds) {
			BigDecimal undeployed = portfolio.loans().get(loanId).lowestUndeployed();
			if (undeployed.signum() < 0) {
				Loan loan = loans.findByIdAndUserId(loanId, userId).orElseThrow();
				// 最常見的原因是日期：借款起算日以前，系統裡還沒有這筆借款的錢
				if (trade.getTradeDate().isBefore(loan.getStartDate())) {
					throw new TradeValidationException("交易日 " + trade.getTradeDate() + " 早於「" + loan.getName() + "」的起算日 "
							+ loan.getStartDate() + "；起算日以前系統裡還沒有這筆借款的錢");
				}
				throw new TradeValidationException("「" + loan.getName() + "」出的錢超過它的尚未投入（差 "
						+ money.plain(undeployed.negate()) + " 元）");
			}
		}
	}

	/** 質押的擔保股數合計不能超過持股（賣出或改股數後；spec 5.5）。 */
	private void checkCollateral(Long userId, Portfolio portfolio, Long stockId) {
		long pledged = collaterals.findByUserId(userId).stream()
			.filter(c -> c.getStockId().equals(stockId))
			.mapToLong(PledgeCollateral::getShares)
			.sum();
		Position pos = portfolio.positions().get(stockId);
		long held = pos == null ? 0 : pos.shares();
		if (pledged > held) {
			throw new TradeValidationException("這檔有 " + money.plain(BigDecimal.valueOf(pledged))
					+ " 股設定為質押擔保，這樣持股只剩 " + money.plain(BigDecimal.valueOf(held)) + " 股；請先到資金來源頁調整擔保股數");
		}
	}

	/** 讀出目前登入者的所有交易、配息、借款流量，算出投資組合。 */
	private Portfolio portfolioOf(Long userId) {
		return portfolioReader.read(userId);
	}

	/** 一檔持股的價值（總資產與資產配置排行用）。 */
	public record HoldingValue(Long stockId, String label, BigDecimal value, boolean priced) {
	}

	/** 目前持股的價值：有股價用市值，還沒有股價用成本（整數元）。 */
	@Transactional(readOnly = true)
	public List<HoldingValue> holdingValues() {
		Portfolio portfolio = portfolio();
		List<Position> held = portfolio.positions().values().stream().filter(p -> p.shares() > 0).toList();
		if (held.isEmpty()) {
			return List.of();
		}
		List<Long> ids = held.stream().map(Position::stockId).toList();
		Map<Long, Stock> stockById = stocks.findByIdIn(ids).stream().collect(Collectors.toMap(Stock::getId, s -> s));
		Map<Long, BigDecimal> latest = latestPrices(ids);
		return held.stream().map(pos -> {
			Stock s = stockById.get(pos.stockId());
			BigDecimal price = latest.get(pos.stockId());
			BigDecimal value = price == null ? pos.cost() : StockMetricsCalculator.marketValue(pos.shares(), price);
			return new HoldingValue(pos.stockId(), s.getCode() + " " + s.getName(), whole(value), price != null);
		}).toList();
	}

	/**
	 * 某個月的股價漲跌與賣出損益（spec 4.3）。某一天還沒有收盤價時用那天以前最新的；系統開始抓股價以前的日子，
	 * 用之後最早的股價（等於當作那段期間沒漲跌，不會把開始用系統前累積的漲幅算成這個月的變動）。
	 */
	@Transactional(readOnly = true)
	public MonthlyStockChange monthlyChange(YearMonth month) {
		LocalDate today = today();
		LocalDate end = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
		List<TradeEvent> events = trades.findByUserIdAndVoidedFalse(currentUser.id()).stream()
			.map(t -> new TradeEvent(t.getId(), t.getType(), t.getTradeDate(), t.getStockId(), t.getShares(),
					t.getAmount(), Map.of()))
			.toList();
		Set<Long> ids = events.stream().map(TradeEvent::stockId).collect(Collectors.toSet());
		Map<LocalDate, Map<Long, BigDecimal>> cache = new HashMap<>();
		return MonthlyStockChangeCalculator.compute(events, month, end, (stockId, day) -> Optional
			.ofNullable(cache.computeIfAbsent(day, d -> pricesOn(ids, d)).get(stockId)));
	}

	private Map<Long, BigDecimal> pricesOn(Set<Long> ids, LocalDate day) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, BigDecimal> result = new HashMap<>(latestPrices(ids, day));
		Set<Long> missing = ids.stream().filter(id -> !result.containsKey(id)).collect(Collectors.toSet());
		if (!missing.isEmpty()) {
			prices.findEarliestAfter(missing, day).forEach(p -> result.putIfAbsent(p.getStockId(), p.getClosePrice()));
		}
		return result;
	}

	/** 各股票今天以前最新的收盤價（沒有股價的不在 Map 裡）。共用資料，不分使用者。 */
	@Transactional(readOnly = true)
	public Map<Long, BigDecimal> latestPrices(Collection<Long> stockIds) {
		return latestPrices(stockIds, today());
	}

	/** 沒作廢的交易裡有沒有用到這筆借款當資金來源（借款作廢前檢查）。 */
	@Transactional(readOnly = true)
	public boolean loanInUse(Long loanId) {
		Long userId = currentUser.id();
		Set<Long> active = trades.findByUserIdAndVoidedFalse(userId).stream().map(Trade::getId)
			.collect(Collectors.toSet());
		return allocations.findByUserId(userId).stream()
			.anyMatch(a -> a.getLoanId().equals(loanId) && active.contains(a.getTradeId()));
	}

	/** 借款改了本金或日期之後：每筆借款出去買股票的錢都不能超過它借到的（尚未投入不能是負數）。 */
	@Transactional(readOnly = true)
	public void checkLoansCovered() {
		portfolioReader.uncoveredLoan(currentUser.id()).ifPresent(message -> {
			throw new TradeValidationException(message);
		});
	}

	private Map<Long, BigDecimal> latestPrices(Collection<Long> stockIds, LocalDate asOf) {
		if (stockIds.isEmpty()) {
			return Map.of();
		}
		return prices.findLatestOnOrBefore(stockIds, asOf).stream()
			.collect(Collectors.toMap(StockPrice::getStockId, StockPrice::getClosePrice, (a, b) -> a));
	}

	/** 預估年配息：最近一次有金額的現金股利 × 年配息次數 × 股數；缺資料為 null。 */
	private static BigDecimal annualDividend(Stock stock, List<Dividend> notices, long shares) {
		if (stock.getDividendsPerYear() == null) {
			return null;
		}
		return notices.stream()
			.filter(d -> d.getCashPerShare() != null)
			.findFirst()
			.map(d -> StockMetricsCalculator.annualDividend(d.getCashPerShare(), stock.getDividendsPerYear(), shares))
			.orElse(null);
	}

	/** 「自有 40%、玉山質押 60%」：依各來源金額占持股成本的比例。 */
	private static String sourcesLabel(Position pos, Map<Long, String> loanNames) {
		BigDecimal total = pos.loanTags().values().stream().reduce(pos.ownTag(), BigDecimal::add);
		if (total.signum() <= 0) {
			return "—";
		}
		List<String> parts = new ArrayList<>();
		if (pos.ownTag().signum() > 0) {
			parts.add("自有 " + percent(pos.ownTag(), total) + "%");
		}
		pos.loanTags().forEach((loan, amount) -> parts.add(loanNames.getOrDefault(loan, "借款") + " "
				+ percent(amount, total) + "%"));
		return String.join("、", parts);
	}

	private static String percent(BigDecimal part, BigDecimal total) {
		return part.multiply(BigDecimal.valueOf(100)).divide(total, 0, RoundingMode.HALF_UP).toPlainString();
	}

	private List<SaleRow> recentSales(List<Trade> all, Portfolio portfolio, Map<Long, Stock> stockById) {
		Map<Long, RealizedSale> realized = portfolio.sales().stream()
			.collect(Collectors.toMap(RealizedSale::tradeId, s -> s, (a, b) -> a));
		return all.stream()
			.filter(t -> t.getType() == TradeType.SELL && !t.isVoided())
			.limit(RECENT_SALES)
			.map(t -> {
				Stock stock = stockById.get(t.getStockId());
				BigDecimal tradeAmount = FeeCalculator.tradeAmount(t.getShares(), t.getPrice());
				FeeCalculator.Rates r = feeRates(stock, t.getTradeDate());
				BigDecimal estimated = FeeCalculator.sellProceeds(tradeAmount,
						FeeCalculator.fee(tradeAmount, t.getShares(), r), FeeCalculator.tax(tradeAmount, r));
				RealizedSale sale = realized.get(t.getId());
				return new SaleRow(t.getId(), t.getTradeDate(), stock.getCode(), stock.getName(), t.getShares(),
						estimated, whole(t.getAmount()), whole(t.getAmount()).subtract(estimated),
						sale == null ? null : whole(sale.realized()));
			})
			.toList();
	}

	private List<TradeRow> tradeRows(Long userId, List<Trade> all, Map<Long, Stock> stockById) {
		Set<Long> revised = new HashSet<>(revisions.findRevisedTradeIds(userId));
		Map<Long, String> accountNames = accounts.findByUserIdOrderByIdAsc(userId).stream()
			.collect(Collectors.toMap(CashAccount::getId, CashAccount::getName));
		return all.stream().map(t -> {
			Stock stock = stockById.get(t.getStockId());
			return new TradeRow(t.getId(), t.getTradeDate(), t.getType().label(), stock.getCode(), stock.getName(),
					t.getShares(), t.getPrice(), wholeOrNull(t.getFee()), wholeOrNull(t.getTax()), whole(t.getAmount()),
					t.getAccountId() == null ? "—" : accountNames.getOrDefault(t.getAccountId(), "—"),
					revised.contains(t.getId()) && !t.isVoided(), t.isVoided());
		}).toList();
	}

	/**
	 * 已經除息（最近 180 天）、除息前一天有持股、而且除息日以後還沒記這檔股票的配息 → 建議入帳金額與二代健保。
	 */
	private List<DividendSuggestion> suggestions(Long userId, Portfolio portfolio, LocalDate today) {
		List<TradeEvent> events = trades.findByUserIdAndVoidedFalse(userId).stream()
			.map(t -> new TradeEvent(t.getId(), t.getType(), t.getTradeDate(), t.getStockId(), t.getShares(),
					t.getAmount(), Map.of()))
			.toList();
		Set<Long> stockIds = events.stream().map(TradeEvent::stockId).collect(Collectors.toSet());
		if (stockIds.isEmpty()) {
			return List.of();
		}
		List<CashMovement> received = movements.findByUserIdAndTypeAndVoidedFalse(userId, CashMovementType.DIVIDEND);
		Map<Long, Stock> stockById = stocks.findByIdIn(new ArrayList<>(stockIds)).stream()
			.collect(Collectors.toMap(Stock::getId, s -> s));
		List<DividendSuggestion> result = new ArrayList<>();
		for (Dividend d : dividends.findByStockIdInOrderByExDateDesc(stockIds)) {
			if (d.getCashPerShare() == null || d.getCashPerShare().signum() <= 0 || d.getExDate().isAfter(today)
					|| d.getExDate().isBefore(today.minusDays(SUGGEST_DAYS))) {
				continue;
			}
			long shares = PortfolioCalculator.sharesOn(events, d.getStockId(), d.getExDate().minusDays(1));
			boolean recorded = received.stream()
				.anyMatch(m -> d.getStockId().equals(m.getStockId()) && !m.getMovementDate().isBefore(d.getExDate()));
			if (shares <= 0 || recorded) {
				continue;
			}
			BigDecimal gross = StockMetricsCalculator.cashDividend(shares, d.getCashPerShare());
			// 二代健保依除息日當時的費率與門檻（股票股利的部分見問題清單）
			BigDecimal rate = rates.findEffective(RateKind.NHI_SUPPLEMENT_RATE, d.getExDate()).orElse(BigDecimal.ZERO);
			BigDecimal threshold = rates.findEffective(RateKind.NHI_SUPPLEMENT_THRESHOLD, d.getExDate())
				.orElse(MAX_AMOUNT);
			BigDecimal nhi = StockMetricsCalculator.nhi(gross, 0, rate, threshold);
			Stock stock = stockById.get(d.getStockId());
			result.add(new DividendSuggestion(stock.getCode(), stock.getName(), d.getExDate(), shares,
					trim(d.getCashPerShare()), gross, nhi, gross.subtract(nhi)));
		}
		return result;
	}

	// ---------- 檢查 ----------

	private Map<Long, BigDecimal> allocationsOf(TradeType type, List<AllocationLine> lines) {
		Map<Long, BigDecimal> result = new LinkedHashMap<>();
		for (AllocationLine line : lines) {
			if (line == null || line.loanId() == null || line.amount() == null || line.amount().signum() == 0) {
				continue;
			}
			if (type != TradeType.OPENING && type != TradeType.BUY) {
				throw new TradeValidationException("只有期初持股與買進要選資金來源");
			}
			checkWhole(line.amount(), "資金來源金額", true);
			Loan loan = loans.findByIdAndUserId(line.loanId(), currentUser.id())
				.orElseThrow(() -> new NotFoundException("找不到這筆借款"));
			if (loan.isVoided()) {
				throw new TradeValidationException("「" + loan.getName() + "」已經作廢");
			}
			result.merge(loan.getId(), line.amount(), BigDecimal::add);
		}
		return result;
	}

	private void saveAllocations(Trade trade, Map<Long, BigDecimal> loanAllocations) {
		loanAllocations.forEach((loanId, amount) -> allocations.save(SourceAllocation.of(trade.getId(),
				trade.getUserId(), loanId, amount)));
	}

	private Stock findStock(String code) {
		if (code == null || code.isEmpty()) {
			throw new TradeValidationException("請輸入股票代號");
		}
		return stocks.findByCode(code)
			.orElseThrow(() -> new TradeValidationException("找不到這個上市股票代號：" + code));
	}

	private CashAccount findMyAccount(Long accountId) {
		if (accountId == null) {
			throw new TradeValidationException("請選擇帳戶");
		}
		return accounts.findByIdAndUserId(accountId, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這個帳戶"));
	}

	private Trade findMineForEdit(Long id) {
		Trade t = trades.findForUpdate(id, currentUser.id()).orElseThrow(() -> new NotFoundException("找不到這筆交易"));
		if (t.isVoided()) {
			throw new TradeValidationException("這筆交易已經作廢");
		}
		return t;
	}

	private void checkDate(LocalDate date) {
		if (date == null) {
			throw new TradeValidationException("請選擇日期");
		}
		if (date.isAfter(today())) {
			throw new TradeValidationException("日期不能晚於今天");
		}
		if (date.isBefore(MIN_DATE)) {
			throw new TradeValidationException("日期要在 2000-01-01 之後");
		}
	}

	private void checkNotBeforeOpening(Long accountId, LocalDate date) {
		Optional<LocalDate> opening = movements
			.findByUserIdAndAccountIdAndTypeAndVoidedFalse(currentUser.id(), accountId, CashMovementType.OPENING)
			.map(CashMovement::getMovementDate);
		if (opening.isPresent() && date.isBefore(opening.get())) {
			throw new TradeValidationException("日期不能早於這個帳戶的初始現金餘額日期（" + opening.get() + "）");
		}
	}

	private static BigDecimal checkPrice(BigDecimal price, String missing) {
		if (price == null) {
			throw new TradeValidationException(missing);
		}
		if (price.signum() <= 0 || price.compareTo(MAX_PRICE) >= 0) {
			throw new TradeValidationException("價格要大於 0");
		}
		if (price.stripTrailingZeros().scale() > 4) {
			throw new TradeValidationException("價格小數最多 4 位");
		}
		return price;
	}

	private static BigDecimal checkWhole(BigDecimal amount, String label, boolean positive) {
		if (amount == null) {
			throw new TradeValidationException("請輸入" + label);
		}
		if (amount.abs().compareTo(MAX_AMOUNT) >= 0) {
			throw new TradeValidationException(label + "太大");
		}
		if (amount.stripTrailingZeros().scale() > 0) {
			throw new TradeValidationException(label + "只能是整數元");
		}
		if (positive ? amount.signum() <= 0 : amount.signum() < 0) {
			throw new TradeValidationException(label + (positive ? "要大於 0" : "不能是負數"));
		}
		return amount;
	}

	private static String cleanReason(String reason) {
		if (reason == null || reason.isBlank()) {
			return null;
		}
		String stripped = reason.strip();
		if (stripped.length() > 200) {
			throw new TradeValidationException("原因最多 200 個字");
		}
		return stripped;
	}

	private static CashMovementType cashType(TradeType type) {
		return type == TradeType.BUY ? CashMovementType.STOCK_BUY : CashMovementType.STOCK_SELL;
	}

	private Map<String, String> snapshot(Trade t, String allocationText) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("tradeDate", String.valueOf(t.getTradeDate()));
		values.put("accountId", t.getAccountId() == null ? null : t.getAccountId().toString());
		values.put("shares", Long.toString(t.getShares()));
		values.put("price", plain(t.getPrice()));
		values.put("fee", plain(t.getFee()));
		values.put("tax", plain(t.getTax()));
		values.put("amount", plain(t.getAmount()));
		values.put("allocation", allocationText);
		return values;
	}

	private String allocationText(Long userId, Long tradeId) {
		String text = allocations.findByUserIdAndTradeId(userId, tradeId).stream()
			.sorted(Comparator.comparing(SourceAllocation::getLoanId))
			.map(a -> a.getLoanId() + ":" + plain(a.getAmount()))
			.collect(Collectors.joining(","));
		return text.isEmpty() ? null : text;
	}

	private static boolean sameAmount(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : b != null && a.compareTo(b) == 0;
	}

	/** 股數或價格改了、而這個欄位還是舊值時回傳 null（重算）；否則用使用者送來的值。 */
	private static BigDecimal staleOrSubmitted(boolean repriced, BigDecimal submitted, BigDecimal stored) {
		return repriced && sameAmount(submitted, stored) ? null : submitted;
	}

	private static String plain(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	/** 去掉多餘的小數 0，但不變成 6E+1 這種科學記號。 */
	private static BigDecimal trim(BigDecimal value) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

	private static BigDecimal whole(BigDecimal value) {
		return value.setScale(0, RoundingMode.HALF_UP);
	}

	private static BigDecimal wholeOrNull(BigDecimal value) {
		return value == null ? null : whole(value);
	}

}
