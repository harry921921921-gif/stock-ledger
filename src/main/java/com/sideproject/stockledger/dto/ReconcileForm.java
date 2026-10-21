package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

import org.springframework.format.annotation.DateTimeFormat;

/** 輸入某個帳戶在某天的實際餘額（spec 4.6-4）。存的是實際餘額，差額由系統推算。可以是負數（透支）。 */
public record ReconcileForm(
		@NotNull(message = "請選擇日期") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
		@NotNull(message = "請輸入實際餘額") BigDecimal actualBalance) {
}
