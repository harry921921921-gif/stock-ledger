package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.sideproject.stockledger.entity.RecurringCycle;

/**
 * 固定收支的期數、入帳日與月平均（設計文件 4.1、4.2、spec 第 3 節第 5 頁）。純 Java、不依賴 Spring。
 * 期別用「該期所在月份」辨認，改了入帳日也不會讓同一個月變成兩期。
 */
public final class RecurringScheduleCalculator {

	/** 中間值保留 10 位（CLAUDE.md 計算規則）。 */
	private static final int SCALE = 10;

	private RecurringScheduleCalculator() {
	}

	/** 從開始月份到 min(lastMonth, 結束月份) 的每一期，舊的在前。 */
	public static List<DuePeriod> periodsThrough(RecurringRule rule, YearMonth lastMonth) {
		Objects.requireNonNull(rule, "rule");
		Objects.requireNonNull(lastMonth, "lastMonth");
		YearMonth last = rule.endMonth() != null && rule.endMonth().isBefore(lastMonth) ? rule.endMonth() : lastMonth;
		List<DuePeriod> periods = new ArrayList<>();
		for (YearMonth m = rule.startMonth(); !m.isAfter(last); m = m.plusMonths(1)) {
			if (isDueMonth(rule, m)) {
				periods.add(new DuePeriod(m, dueDate(rule, m)));
			}
		}
		return periods;
	}

	/** 入帳日已經到了（≤ today）的期數，舊的在前；不會有未來日期。 */
	public static List<DuePeriod> dueOnOrBefore(RecurringRule rule, LocalDate today) {
		Objects.requireNonNull(today, "today");
		return periodsThrough(rule, YearMonth.from(today)).stream().filter(p -> !p.dueDate().isAfter(today)).toList();
	}

	/** 下一個入帳日（晚於 today 的第一期）；項目已經結束時為 empty。 */
	public static Optional<LocalDate> nextDueAfter(RecurringRule rule, LocalDate today) {
		Objects.requireNonNull(rule, "rule");
		Objects.requireNonNull(today, "today");
		YearMonth from = YearMonth.from(today);
		if (rule.startMonth().isAfter(from)) {
			from = rule.startMonth();
		}
		// 最長的週期是一年，往後找 13 個月一定找得到（除非已經結束）
		for (YearMonth m = from; !m.isAfter(from.plusMonths(12)); m = m.plusMonths(1)) {
			if (rule.endMonth() != null && m.isAfter(rule.endMonth())) {
				return Optional.empty();
			}
			if (isDueMonth(rule, m) && dueDate(rule, m).isAfter(today)) {
				return Optional.of(dueDate(rule, m));
			}
		}
		return Optional.empty();
	}

	/** 換算成每月平均：每半年 ÷ 6、每年 ÷ 12，保留 10 位、HALF_UP；顯示時再取整數。 */
	public static BigDecimal monthlyAverage(RecurringCycle cycle, BigDecimal amount) {
		Objects.requireNonNull(cycle, "cycle");
		Objects.requireNonNull(amount, "amount");
		return switch (cycle) {
			case MONTHLY -> amount;
			case SEMIANNUAL -> amount.divide(BigDecimal.valueOf(6), SCALE, RoundingMode.HALF_UP);
			case YEARLY -> amount.divide(BigDecimal.valueOf(12), SCALE, RoundingMode.HALF_UP);
		};
	}

	private static boolean isDueMonth(RecurringRule rule, YearMonth month) {
		return switch (rule.cycle()) {
			case MONTHLY -> true;
			case YEARLY -> month.getMonthValue() == rule.anchorMonth();
			case SEMIANNUAL -> Math.floorMod(month.getMonthValue() - rule.anchorMonth(), 6) == 0;
		};
	}

	/** 該月沒有那一天時（例如 2 月 31 號）用該月最後一天。 */
	private static LocalDate dueDate(RecurringRule rule, YearMonth month) {
		return month.atDay(Math.min(rule.dayOfMonth(), month.lengthOfMonth()));
	}

}
