package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;

/**
 * 一個月內現金部分的淨資產變動（spec 4.3、DR-031、DR-035）。
 *
 * @param deposits    本月存入合計（正數）
 * @param withdrawals 本月支出合計（正數）
 * @param interest    本月借款利息（應計制，正數；DR-008）。由事件算不出來，呼叫端用 {@link #withInterest} 加進來
 * @param adjustments 本月對帳的餘額調整合計（可正可負）
 * @param total       deposits − withdrawals − interest + adjustments
 */
public record MonthlyCashChange(BigDecimal deposits, BigDecimal withdrawals, BigDecimal interest,
		BigDecimal adjustments, BigDecimal total) {

	/** 加上借款利息（LoanAccrualCalculator 算好的），重算合計。 */
	public MonthlyCashChange withInterest(BigDecimal loanInterest) {
		return new MonthlyCashChange(deposits, withdrawals, loanInterest, adjustments,
				deposits.subtract(withdrawals).subtract(loanInterest).add(adjustments));
	}

}
