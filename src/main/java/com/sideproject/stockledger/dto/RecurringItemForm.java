package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.springframework.format.annotation.DateTimeFormat;

import com.sideproject.stockledger.entity.AmountMode;
import com.sideproject.stockledger.entity.RecurringCycle;
import com.sideproject.stockledger.entity.RecurringDirection;

/**
 * 新增或修改固定項目的表單（設計文件 4.1）。所有規則在 RecurringItemService 檢查，訊息一致、之後的 REST API 也適用。
 * amount 只有固定金額用；anchorMonth 只有每半年、每年用；endMonth 可以不填。沒有 userId：擁有者一律是登入者。
 */
public record RecurringItemForm(
		String name,
		RecurringDirection direction,
		AmountMode amountMode,
		BigDecimal amount,
		RecurringCycle cycle,
		Integer anchorMonth,
		Integer dayOfMonth,
		Long accountId,
		@DateTimeFormat(pattern = "yyyy-MM") YearMonth startMonth,
		@DateTimeFormat(pattern = "yyyy-MM") YearMonth endMonth) {
}
