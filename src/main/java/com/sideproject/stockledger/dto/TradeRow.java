package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 交易紀錄的一列（含作廢的）。 */
public record TradeRow(Long id, LocalDate date, String typeLabel, String code, String name, long shares,
		BigDecimal price, BigDecimal fee, BigDecimal tax, BigDecimal amount, String accountName, boolean revised,
		boolean voided) {
}
