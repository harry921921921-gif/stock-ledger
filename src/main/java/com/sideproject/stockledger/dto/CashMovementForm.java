package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

import com.sideproject.stockledger.entity.CashMovementType;

/**
 * 「記一筆現金異動」的表單：存入、領出、帳戶互轉（spec 4.6-2）。
 * 金額一律填正數，方向由類型決定；toAccountId 只有互轉才用。沒有 userId 欄位：擁有者一律是登入者。
 * 金額整數、日期範圍等規則在 CashMovementService 檢查。
 */
public record CashMovementForm(
		@NotNull(message = "請選擇類型") CashMovementType type,
		@NotNull(message = "請選擇日期") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
		@NotNull(message = "請選擇帳戶") Long accountId,
		Long toAccountId,
		@NotNull(message = "請輸入金額") BigDecimal amount,
		@Size(max = 100, message = "說明最多 100 個字") String note) {

	/** 說明去掉前後空白（含全形空白）；空白說明存成 null。 */
	public CashMovementForm {
		note = note == null || note.isBlank() ? null : note.strip();
	}

}
