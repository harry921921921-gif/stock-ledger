package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sideproject.stockledger.entity.TradeType;

/**
 * 本月淨資產變動的股票部分（spec 4.3-1、4.3-2）：股價漲跌 ＝ 月底帳面損益 − 上月底帳面損益 − 本月期初持股建立時的帳面損益；
 * 賣出損益 ＝ 本月賣出的已實現損益。
 */
class MonthlyStockChangeCalculatorTest {

	private static final Long S1 = 1L;
	private static final YearMonth OCT = YearMonth.of(2026, 10);
	private static final LocalDate SEP30 = LocalDate.of(2026, 9, 30);
	private static final LocalDate OCT31 = LocalDate.of(2026, 10, 31);

	private long nextId = 1;
	private final Map<LocalDate, BigDecimal> prices = new HashMap<>();

	/** 9 月就持有 1000 股（成本 50,000）；9/30 收 55、10/31 收 58 → 股價漲跌 +3,000。 */
	@Test
	void priceChangeOfHeldStock() {
		prices.put(SEP30, new BigDecimal("55"));
		prices.put(OCT31, new BigDecimal("58"));

		MonthlyStockChange c = compute(List.of(trade(TradeType.BUY, LocalDate.of(2026, 9, 1), 1000, "50000")), OCT31);

		assertThat(c.priceChange()).isEqualByComparingTo("3000");
		assertThat(c.realized()).isEqualByComparingTo("0");
	}

	/** 10 月買進 1000 股 56,000（含手續費）、月底收 58 → +2,000；買進本身不算變動。 */
	@Test
	void buyInMonthCountsOnlyMovementAfterBuying() {
		prices.put(OCT31, new BigDecimal("58"));

		MonthlyStockChange c = compute(List.of(trade(TradeType.BUY, LocalDate.of(2026, 10, 5), 1000, "56000")), OCT31);

		assertThat(c.priceChange()).isEqualByComparingTo("2000");
	}

	/** 期初持股是起點：建立當天的帳面損益（市價 − 平均成本）不算這個月的變動。 */
	@Test
	void openingIsStartingPoint() {
		prices.put(LocalDate.of(2026, 10, 10), new BigDecimal("55"));
		prices.put(OCT31, new BigDecimal("58"));

		MonthlyStockChange c = compute(List.of(trade(TradeType.OPENING, LocalDate.of(2026, 10, 10), 1000, "35000")),
				OCT31);

		// 月底帳面 23,000 − 建立時帳面 20,000
		assertThat(c.priceChange()).isEqualByComparingTo("3000");
	}

	/** 賣出：已實現算「賣出損益」；股價漲跌只算還持有的部分與賣出前的帳面變動。 */
	@Test
	void sellSplitsRealizedAndPriceChange() {
		prices.put(SEP30, new BigDecimal("55"));
		prices.put(LocalDate.of(2026, 10, 15), new BigDecimal("57"));
		prices.put(OCT31, new BigDecimal("58"));

		MonthlyStockChange c = compute(List.of(trade(TradeType.BUY, LocalDate.of(2026, 9, 1), 1000, "50000"),
				trade(TradeType.SELL, LocalDate.of(2026, 10, 15), 500, "28500")), OCT31);

		// 已實現 28,500 − 25,000 ＝ 3,500；帳面：月底 500 × 58 − 25,000 ＝ 4,000，上月底 5,000 → −1,000
		// 合計 +2,500 ＝ 500 股 × (58 − 55) ＋ 500 股 × (57 − 55)
		assertThat(c.realized()).isEqualByComparingTo("3500");
		assertThat(c.priceChange()).isEqualByComparingTo("-1000");
	}

	/** 沒有股價時當作沒漲跌（帳面損益 0），不會把沒有資料的部分算成變動。 */
	@Test
	void missingPriceCountsAsNoChange() {
		MonthlyStockChange c = compute(List.of(trade(TradeType.BUY, LocalDate.of(2026, 9, 1), 1000, "50000")), OCT31);

		assertThat(c.priceChange()).isEqualByComparingTo("0");
	}

	private MonthlyStockChange compute(List<TradeEvent> trades, LocalDate end) {
		return MonthlyStockChangeCalculator.compute(trades, OCT, end,
				(stock, day) -> Optional.ofNullable(prices.get(day)));
	}

	private TradeEvent trade(TradeType type, LocalDate date, long shares, String amount) {
		return new TradeEvent(nextId++, type, date, S1, shares, new BigDecimal(amount), Map.of());
	}

}
