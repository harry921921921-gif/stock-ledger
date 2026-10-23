package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.sideproject.stockledger.entity.TradeType;

/**
 * 交給 PortfolioCalculator 的一筆股票交易（沒作廢的）。
 *
 * @param amount          期初、買進：總成本（含手續費）；賣出：實際入帳金額；配股：0
 * @param loanAllocations 期初、買進：各借款出的錢（借款 id → 金額）；其餘是自有。賣出、配股為空
 */
public record TradeEvent(Long id, TradeType type, LocalDate date, Long stockId, long shares, BigDecimal amount,
		Map<Long, BigDecimal> loanAllocations) {
}
