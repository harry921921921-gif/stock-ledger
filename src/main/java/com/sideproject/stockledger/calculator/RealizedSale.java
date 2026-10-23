package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 一筆賣出的已實現損益：實際入帳 − 賣出成本（平均成本 × 賣出股數）。 */
public record RealizedSale(Long tradeId, Long stockId, LocalDate date, long shares, BigDecimal costOfSold,
		BigDecimal proceeds, BigDecimal realized) {
}
