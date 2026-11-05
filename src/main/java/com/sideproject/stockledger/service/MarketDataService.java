package com.sideproject.stockledger.service;

import java.time.Clock;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.sideproject.stockledger.entity.Dividend;
import com.sideproject.stockledger.entity.PriceUpdateLog;
import com.sideproject.stockledger.entity.Stock;
import com.sideproject.stockledger.entity.StockPrice;
import com.sideproject.stockledger.repository.DividendRepository;
import com.sideproject.stockledger.repository.PriceUpdateLogRepository;
import com.sideproject.stockledger.repository.StockPriceRepository;
import com.sideproject.stockledger.repository.StockRepository;

/**
 * 證交所 OpenAPI 的股價與除權息（spec 7.1、DR-004、DR-011、DR-024）：啟動時抓一次，週一到週五 18:30、21:30 再抓。
 * 股價與除權息只從這裡來，不由 AI 抓價或猜數字（CLAUDE.md）。共用資料，沒有 user_id。
 * 抓不到（網路、格式）只寫紀錄、不丟例外，下次排程再試。
 */
@Service
public class MarketDataService {

	private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

	static final String OK = "成功";

	private final TwseClient twse;
	private final StockRepository stocks;
	private final StockPriceRepository prices;
	private final DividendRepository dividends;
	private final PriceUpdateLogRepository logs;
	private final TransactionTemplate transaction;
	private final TransactionTemplate newTransaction;
	private final Clock clock;
	private final boolean enabled;

	public MarketDataService(TwseClient twse, StockRepository stocks, StockPriceRepository prices,
			DividendRepository dividends, PriceUpdateLogRepository logs, TransactionTemplate transaction, Clock clock,
			@Value("${app.market.enabled:true}") boolean enabled) {
		this.twse = twse;
		this.stocks = stocks;
		this.prices = prices;
		this.dividends = dividends;
		this.logs = logs;
		this.transaction = transaction;
		this.newTransaction = new TransactionTemplate(transaction.getTransactionManager());
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
		this.enabled = enabled;
	}

	/** 啟動時抓（家裡電腦不會一直開機，spec 7.1-2）；測試關掉（app.market.enabled=false）。 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (enabled) {
			refreshAll();
		}
	}

	/** 盤後資料晚上才完整：18:30 抓一次，21:30 再補一次（DR-011 我的判斷時間）。 */
	@Scheduled(cron = "0 30 18,21 * * MON-FRI", zone = "Asia/Taipei")
	public void scheduled() {
		if (enabled) {
			refreshAll();
		}
	}

	public void refreshAll() {
		refreshPrices();
		refreshDividends();
	}

	/** 股票清單與收盤價：新股票新增、改名更新；同一檔同一天已經有收盤價就不寫。回傳新寫入的收盤價筆數。 */
	public int refreshPrices() {
		LocalDateTime now = LocalDateTime.now(clock);
		List<TwseParser.DailyQuote> quotes;
		try {
			quotes = TwseParser.parseDailyQuotes(twse.dailyQuotesJson());
		}
		catch (RuntimeException ex) {
			return fail(PriceUpdateLog.PRICES, now, ex);
		}
		// 寫入資料庫也可能失敗（例如資料格式超出欄位），一樣記一筆失敗紀錄，不讓啟動或排程中斷
		try {
			Integer written = transaction.execute(status -> {
				Map<String, Stock> byCode = upsertStocks(quotes, now);
				Map<LocalDate, Set<Long>> existing = new HashMap<>();
				int count = 0;
				for (TwseParser.DailyQuote q : quotes) {
					if (q.close() == null || q.close().signum() <= 0) {
						continue;
					}
					Long stockId = byCode.get(q.code()).getId();
					Set<Long> have = existing.computeIfAbsent(q.date(),
							d -> new HashSet<>(prices.findStockIdsByTradeDate(d)));
					if (have.add(stockId)) {
						prices.save(StockPrice.of(stockId, q.date(), q.close()));
						count++;
					}
				}
				LocalDate dataDate = quotes.stream().map(TwseParser.DailyQuote::date).max(LocalDate::compareTo).orElse(null);
				logs.save(PriceUpdateLog.of(now, PriceUpdateLog.PRICES, dataDate, count, OK));
				return count;
			});
			return written == null ? 0 : written;
		}
		catch (RuntimeException ex) {
			return fail(PriceUpdateLog.PRICES, now, ex);
		}
	}

	/** 除權除息預告：依（股票、除權息日）新增或補上金額。回傳處理的筆數。 */
	public int refreshDividends() {
		LocalDateTime now = LocalDateTime.now(clock);
		List<TwseParser.DividendNotice> notices;
		try {
			notices = TwseParser.parseDividendNotices(twse.dividendNoticesJson());
		}
		catch (RuntimeException ex) {
			return fail(PriceUpdateLog.DIVIDENDS, now, ex);
		}
		// 寫入資料庫也可能失敗（例如資料格式超出欄位），一樣記一筆失敗紀錄，不讓啟動或排程中斷
		try {
			Integer written = transaction.execute(status -> {
				Map<String, Stock> byCode = new HashMap<>();
				stocks.findAll().forEach(s -> byCode.put(s.getCode(), s));
				for (TwseParser.DividendNotice n : notices) {
					Stock stock = byCode.computeIfAbsent(n.code(), code -> stocks.save(Stock.create(code, n.name(), now)));
					dividends.findByStockIdAndExDate(stock.getId(), n.exDate())
						.ifPresentOrElse(d -> d.update(n.cashPerShare(), n.stockRatio(), now),
								() -> dividends.save(Dividend.of(stock.getId(), n.exDate(), n.cashPerShare(),
										n.stockRatio(), now)));
				}
				logs.save(PriceUpdateLog.of(now, PriceUpdateLog.DIVIDENDS, null, notices.size(), OK));
				return notices.size();
			});
			return written == null ? 0 : written;
		}
		catch (RuntimeException ex) {
			return fail(PriceUpdateLog.DIVIDENDS, now, ex);
		}
	}

	/**
	 * 某檔股票某天的收盤價：先查本機；沒有就抓那個月、把當月每天都存起來（已有的不覆蓋）。
	 * 那天沒資料（假日）或抓不到都回空，抓不到會寫失敗紀錄。
	 * 不加 @Transactional：網路請求不放在資料庫交易裡；寫入與紀錄各用獨立的新交易（REQUIRES_NEW），
	 * 失敗不會讓呼叫端的交易變成只能 rollback。
	 */
	public Optional<BigDecimal> closeOn(String stockCode, LocalDate date) {
		return closeOn(stockCode, date, new HashSet<>());
	}

	/**
	 * 同上，但呼叫端一次要查很多天時傳同一個 fetchedMonths（「代號|年月」）：這個月已經抓過了，
	 * 本機又沒有那天的價格（假日或沒資料），就不再重抓，直接回空。
	 */
	public Optional<BigDecimal> closeOn(String stockCode, LocalDate date, Set<String> fetchedMonths) {
		Optional<Stock> stock = stocks.findByCode(stockCode);
		if (stock.isEmpty()) {
			return Optional.empty();
		}
		Long stockId = stock.get().getId();
		Optional<StockPrice> stored = prices.findByStockIdAndTradeDate(stockId, date);
		if (stored.isPresent()) {
			return Optional.of(stored.get().getClosePrice());
		}
		if (!fetchedMonths.add(stockCode + "|" + YearMonth.from(date))) {
			return Optional.empty();
		}
		LocalDateTime now = LocalDateTime.now(clock);
		List<TwseParser.DailyQuote> quotes;
		try {
			quotes = TwseParser.parseMonthlyQuotes(stockCode, twse.monthlyQuotesJson(stockCode, YearMonth.from(date)));
		}
		catch (RuntimeException ex) {
			historyLog(now, date, 0, ex);
			return Optional.empty();
		}
		BigDecimal found = quotes.stream()
			.filter(q -> q.date().equals(date) && q.close().signum() > 0)
			.map(TwseParser.DailyQuote::close)
			.findFirst()
			.orElse(null);
		try {
			Integer count = newTransaction.execute(status -> {
				int saved = 0;
				for (TwseParser.DailyQuote q : quotes) {
					if (q.close().signum() > 0 && prices.findByStockIdAndTradeDate(stockId, q.date()).isEmpty()) {
						prices.save(StockPrice.of(stockId, q.date(), q.close()));
						saved++;
					}
				}
				return saved;
			});
			newTransaction.executeWithoutResult(status -> logs.save(PriceUpdateLog.of(now, PriceUpdateLog.HISTORY, date,
					count == null ? 0 : count, OK)));
		}
		catch (RuntimeException ex) {
			// 存不進去（例如和排程同時寫入）：抓到的價格仍然可用，只記失敗紀錄
			historyLog(now, date, 0, ex);
		}
		return Optional.ofNullable(found);
	}

	private void historyLog(LocalDateTime now, LocalDate date, int count, RuntimeException ex) {
		log.warn("證交所歷史收盤價抓取失敗：{}", date, ex);
		String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
		try {
			newTransaction.executeWithoutResult(
					status -> logs.save(PriceUpdateLog.of(now, PriceUpdateLog.HISTORY, date, count, "失敗：" + reason)));
		}
		catch (RuntimeException logEx) {
			log.warn("歷史收盤價失敗紀錄也寫不進去", logEx);
		}
	}

	/** 最新一筆收盤價的日期（畫面顯示「股價更新至」）。 */
	@Transactional(readOnly = true)
	public Optional<LocalDate> latestPriceDate() {
		return prices.findLatestTradeDate();
	}

	private Map<String, Stock> upsertStocks(List<TwseParser.DailyQuote> quotes, LocalDateTime now) {
		Map<String, Stock> byCode = new HashMap<>();
		stocks.findAll().forEach(s -> byCode.put(s.getCode(), s));
		for (TwseParser.DailyQuote q : quotes) {
			Stock existing = byCode.get(q.code());
			if (existing == null) {
				byCode.put(q.code(), stocks.save(Stock.create(q.code(), q.name(), now)));
			}
			else {
				existing.rename(q.name(), now);
			}
		}
		return byCode;
	}

	private int fail(String kind, LocalDateTime now, RuntimeException ex) {
		log.warn("證交所 {} 抓取失敗，下次排程再試", kind, ex);
		transaction.executeWithoutResult(
				status -> logs.save(PriceUpdateLog.of(now, kind, null, 0, "失敗：" + ex.getMessage())));
		return 0;
	}

}
