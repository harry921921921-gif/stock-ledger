package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 最近賣出（spec 4.4）：預估入帳與實際入帳的差異、已實現損益。 */
public record SaleRow(Long tradeId, LocalDate date, String code, String name, long shares, BigDecimal estimated,
		BigDecimal actual, BigDecimal difference, BigDecimal realized) {
}
