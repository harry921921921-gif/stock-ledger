package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 本月淨資產變動的完整拆解（spec 4.3-1）：股價漲跌、賣出損益、配息、利息、存入、支出、餘額調整。金額都是整數元。
 *
 * @param priceChange 股價漲跌（帳面，可正可負）
 * @param realized    賣出損益（可正可負）
 * @param total       priceChange ＋ realized ＋ 現金部分的合計
 */
public record MonthlyNetWorthChange(BigDecimal priceChange, BigDecimal realized, BigDecimal deposits,
		BigDecimal withdrawals, BigDecimal dividends, BigDecimal interest, BigDecimal adjustments, BigDecimal total) {

	/** 股票部分四捨五入到元，再和現金部分（已含應計利息）合計。 */
	public static MonthlyNetWorthChange of(MonthlyCashChange cash, MonthlyStockChange stock) {
		BigDecimal price = stock.priceChange().setScale(0, RoundingMode.HALF_UP);
		BigDecimal realized = stock.realized().setScale(0, RoundingMode.HALF_UP);
		return new MonthlyNetWorthChange(price, realized, cash.deposits(), cash.withdrawals(), cash.dividends(),
				cash.interest(), cash.adjustments(), price.add(realized).add(cash.total()));
	}

}
