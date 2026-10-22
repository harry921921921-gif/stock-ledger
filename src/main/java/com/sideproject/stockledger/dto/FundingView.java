package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 資金來源頁（spec 第 3 節第 4 頁、設計文件 7）。
 *
 * @param totalDebt           總負債：所有借款今天的剩餘本金
 * @param weightedRatePercent 加權平均利率（%，到 0.01）；沒有未還清的借款時為 null
 * @param monthInterest       本月利息（應計，整數元）
 */
public record FundingView(BigDecimal totalDebt, BigDecimal weightedRatePercent, BigDecimal monthInterest,
		List<LoanCard> loans) {
}
