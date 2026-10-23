package com.sideproject.stockledger.calculator;

import java.util.List;
import java.util.Map;

/** PortfolioCalculator 的結果：每檔股票的持股、每筆借款的用途、每筆賣出的已實現損益。 */
public record Portfolio(Map<Long, Position> positions, Map<Long, LoanUsage> loans, List<RealizedSale> sales) {
}
