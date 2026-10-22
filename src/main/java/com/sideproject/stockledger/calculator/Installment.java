package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 信貸的一期。
 *
 * @param number         第幾期（從 1 開始，以起算時的剩餘期數為準）
 * @param period         期別：扣款日所在月份
 * @param principal      這一期的本金
 * @param interest       這一期的利息
 * @param remainingAfter 扣完這一期後的剩餘本金
 */
public record Installment(int number, YearMonth period, LocalDate dueDate, BigDecimal principal, BigDecimal interest,
		BigDecimal remainingAfter) {
}
