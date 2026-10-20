package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

import com.sideproject.stockledger.entity.RateKind;

/**
 * 新增個人費率版本的表單。value 是畫面上的數字：比率類填百分比（2.8 折填 28），金額類填元。
 * 換算與範圍檢查在 RateSettingService。沒有 userId 欄位：擁有者一律是登入者。
 */
public record RateSettingForm(
		@NotNull(message = "請選擇費率種類") RateKind kind,
		@NotNull(message = "請輸入數字") @Digits(integer = 13, fraction = 4, message = "數字太大或小數位數太多（最多 4 位小數）") BigDecimal value,
		@NotNull(message = "請選擇生效日期") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
		@Size(max = 200, message = "備註最多 200 個字") String note) {

	/** 備註去掉前後空白（含全形空白）；空白備註存成 null。 */
	public RateSettingForm {
		note = note == null || note.isBlank() ? null : note.strip();
	}

}
