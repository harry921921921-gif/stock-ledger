package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 借款本金的變動：起算本金與撥款為正（進到「尚未投入」），還本為負（先扣尚未投入，不足的把標記轉為自有）。
 */
public record LoanFlow(Long loanId, LocalDate date, BigDecimal delta) {
}
