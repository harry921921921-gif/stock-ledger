package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

import com.sideproject.stockledger.entity.CashMovementType;

/**
 * 「記一筆現金異動」的表單：存入、支出、帳戶互轉（spec 4.6-2、DR-035）。
 * 存入只用 toAccountId（轉入帳戶），支出只用 fromAccountId（轉出帳戶），互轉兩個都用；
 * 依類型的必填與多餘欄位由 CashMovementService 檢查（沒有 JavaScript 時畫面兩個欄位都會顯示）。
 * 金額一律填正數，方向由類型決定。沒有 userId 欄位：擁有者一律是登入者。
 */
public record CashMovementForm(
		@NotNull(message = "請選擇類型") CashMovementType type,
		@NotNull(message = "請選擇日期") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
		Long fromAccountId,
		Long toAccountId,
		@NotNull(message = "請輸入金額") BigDecimal amount,
		@Size(max = 100, message = "備註最多 100 個字") String note) {

	/** 備註去掉前後空白（含全形空白）；空白備註存成 null。 */
	public CashMovementForm {
		note = note == null || note.isBlank() ? null : note.strip();
	}

}
