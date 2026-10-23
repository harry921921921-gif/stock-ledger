package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

import com.sideproject.stockledger.entity.TradeType;

/**
 * 本月淨資產變動的股票部分（spec 4.3-1、4.3-2）。純 Java、不依賴 Spring。
 * <ul>
 * <li>帳面損益 U(t) ＝ Σ 那天結束時各股票的 (股數 × 股價 − 剩餘成本)；那天沒有股價的股票算 0（沒資料就當沒漲跌）。</li>
 * <li>股價漲跌 ＝ U(月底) − U(上月底) − Σ 本月期初持股建立當天的帳面損益（期初持股是起點，不算變動）。</li>
 * <li>賣出損益 ＝ 本月賣出的已實現損益（實際入帳 − 平均成本 × 股數）。</li>
 * </ul>
 * 推導：股票對淨資產的影響 ＝ Δ市值 ＋ 賣出入帳 − 買進付款 − 期初持股市值 ＝ ΔU ＋ 已實現 − 期初建立時的 U。
 */
public final class MonthlyStockChangeCalculator {

	private MonthlyStockChangeCalculator() {
	}

	/**
	 * @param trades 沒作廢的交易
	 * @param month  要算的月份
	 * @param end    算到哪一天（本月算到今天，過去的月份是月底）
	 * @param price  某檔股票在某一天的股價（呼叫端決定沒有收盤價時的替代規則）
	 */
	public static MonthlyStockChange compute(List<TradeEvent> trades, YearMonth month, LocalDate end,
			BiFunction<Long, LocalDate, Optional<BigDecimal>> price) {
		Objects.requireNonNull(trades, "trades");
		LocalDate start = month.atDay(1).minusDays(1);
		BigDecimal openingGap = BigDecimal.ZERO;
		for (TradeEvent t : trades) {
			if (t.type() == TradeType.OPENING && t.date().isAfter(start) && !t.date().isAfter(end)) {
				Optional<BigDecimal> p = price.apply(t.stockId(), t.date());
				if (p.isPresent()) {
					openingGap = openingGap.add(p.get().multiply(BigDecimal.valueOf(t.shares())).subtract(t.amount()));
				}
			}
		}
		Portfolio atEnd = PortfolioCalculator.compute(upTo(trades, end), List.of(), List.of());
		BigDecimal realized = atEnd.sales().stream()
			.filter(s -> s.date().isAfter(start) && !s.date().isAfter(end))
			.map(RealizedSale::realized)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal change = unrealized(atEnd, end, price)
			.subtract(unrealized(PortfolioCalculator.compute(upTo(trades, start), List.of(), List.of()), start, price))
			.subtract(openingGap);
		return new MonthlyStockChange(change, realized);
	}

	private static List<TradeEvent> upTo(List<TradeEvent> trades, LocalDate day) {
		return trades.stream().filter(t -> !t.date().isAfter(day)).toList();
	}

	private static BigDecimal unrealized(Portfolio portfolio, LocalDate day,
			BiFunction<Long, LocalDate, Optional<BigDecimal>> price) {
		BigDecimal total = BigDecimal.ZERO;
		for (Position pos : portfolio.positions().values()) {
			if (pos.shares() == 0) {
				continue;
			}
			Optional<BigDecimal> p = price.apply(pos.stockId(), day);
			if (p.isPresent()) {
				total = total.add(p.get().multiply(BigDecimal.valueOf(pos.shares())).subtract(pos.cost()));
			}
		}
		return total;
	}

}
