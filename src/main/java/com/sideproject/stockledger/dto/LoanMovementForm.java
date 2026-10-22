package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

/** 修改借款的撥款、還本或利息：只能改日期與金額（例如對上銀行明細）。類型、借款、帳戶不能改。 */
public record LoanMovementForm(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, BigDecimal amount) {
}
