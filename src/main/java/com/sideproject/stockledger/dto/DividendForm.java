package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

/** 配息入帳：實際入帳日、帳戶、股票、實際入帳金額（已扣二代健保）、二代健保扣繳額。 */
public record DividendForm(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Long accountId,
		String stockCode, BigDecimal amount, BigDecimal nhi) {

	public DividendForm {
		stockCode = stockCode == null ? null : stockCode.strip().toUpperCase();
	}

}
