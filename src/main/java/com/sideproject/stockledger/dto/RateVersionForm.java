package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

/** 借款新的年利率版本：從生效日起適用（畫面填百分比，2.75 代表 2.75%）。 */
public record RateVersionForm(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
		BigDecimal annualRatePercent) {
}
