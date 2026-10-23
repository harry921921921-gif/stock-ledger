package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * 還本（信貸是提前還本）或質押再動撥：日期、金額、從哪個帳戶扣或撥入哪個帳戶。
 *
 * @param interest 同一天一起付的利息（選填；到期一次還本金＋利息時，系統預設帶入應計利息，DR-048）
 */
public record PrincipalForm(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, BigDecimal amount,
		Long accountId, BigDecimal interest) {

	public PrincipalForm(LocalDate date, BigDecimal amount, Long accountId) {
		this(date, amount, accountId, null);
	}

}
