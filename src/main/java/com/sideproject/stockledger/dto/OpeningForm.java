package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

import org.springframework.format.annotation.DateTimeFormat;

/** 設定某個帳戶的初始現金餘額：開始用系統時帳戶裡本來就有的錢（spec 4.6-3、DR-032）。 */
public record OpeningForm(
		@NotNull(message = "請選擇日期") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
		@NotNull(message = "請輸入金額") BigDecimal amount) {
}
