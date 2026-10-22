package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

/** 還本（信貸是提前還本）或質押再動撥：日期、金額、從哪個帳戶扣或撥入哪個帳戶。 */
public record PrincipalForm(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, BigDecimal amount,
		Long accountId) {
}
