package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;

/**
 * 一個月內現金部分的淨資產變動（spec 4.3、DR-031、DR-035、DR-039）。股價漲跌與賣出損益在總覽計算。
 *
 * @param deposits    本月存入合計（正數）
 * @param withdrawals 本月支出合計（正數）
 * @param dividends   本月配息實際入帳合計（正數，已扣二代健保）
 * @param interest    本月借款利息（應計制，正數；DR-008）。由事件算不出來，呼叫端用 {@link #withInterest} 加進來
 * @param adjustments 本月對帳的餘額調整合計（可正可負）
 * @param total       deposits − withdrawals ＋ dividends − interest ＋ adjustments
 */
public record MonthlyCashChange(BigDecimal deposits, BigDecimal withdrawals, BigDecimal dividends,
		BigDecimal interest, BigDecimal adjustments, BigDecimal total) {

	/** 加上借款利息（LoanAccrualCalculator 算好的），重算合計。 */
	public MonthlyCashChange withInterest(BigDecimal loanInterest) {
		return new MonthlyCashChange(deposits, withdrawals, dividends, loanInterest, adjustments,
				deposits.subtract(withdrawals).add(dividends).subtract(loanInterest).add(adjustments));
	}

}
