package com.sideproject.stockledger.calculator;

import java.time.YearMonth;
import java.util.Objects;

import com.sideproject.stockledger.entity.RecurringCycle;

/**
 * 一個固定項目「什麼時候入帳」的規則。
 *
 * @param anchorMonth 每年：那個月；每半年：那個月和 6 個月後；每月為 null
 * @param dayOfMonth  幾號（1～31；該月沒有那一天時用該月最後一天）
 * @param startMonth  從哪個月開始
 * @param endMonth    到哪個月結束（含）；沒有結束為 null
 */
public record RecurringRule(RecurringCycle cycle, Integer anchorMonth, int dayOfMonth, YearMonth startMonth,
		YearMonth endMonth) {

	public RecurringRule {
		Objects.requireNonNull(cycle, "cycle");
		Objects.requireNonNull(startMonth, "startMonth");
		if (dayOfMonth < 1 || dayOfMonth > 31) {
			throw new IllegalArgumentException("入帳日要在 1～31");
		}
		if (cycle == RecurringCycle.MONTHLY) {
			if (anchorMonth != null) {
				throw new IllegalArgumentException("每月的項目不用選月份");
			}
		}
		else if (anchorMonth == null || anchorMonth < 1 || anchorMonth > 12) {
			throw new IllegalArgumentException("每半年、每年的項目要選 1～12 月");
		}
		if (endMonth != null && endMonth.isBefore(startMonth)) {
			throw new IllegalArgumentException("結束月份不能早於開始月份");
		}
	}

}
