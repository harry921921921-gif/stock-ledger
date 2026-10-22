package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import com.sideproject.stockledger.entity.RecurringCycle;

/** 固定收支的期數、入帳日、月平均（設計文件 4.1、4.2）。純 Java，不需要 Spring。 */
class RecurringScheduleCalculatorTest {

	@Test
	void monthlyPeriods() {
		RecurringRule rule = monthly(5, "2026-08", null);

		assertThat(RecurringScheduleCalculator.dueOnOrBefore(rule, LocalDate.parse("2026-10-04")))
			.extracting(DuePeriod::dueDate)
			.containsExactly(LocalDate.parse("2026-08-05"), LocalDate.parse("2026-09-05"));
		assertThat(RecurringScheduleCalculator.dueOnOrBefore(rule, LocalDate.parse("2026-10-05")))
			.extracting(DuePeriod::period)
			.containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
	}

	/** 該月沒有那一天時用該月最後一天。 */
	@Test
	void dayClampedToMonthEnd() {
		assertThat(due(monthly(31, "2026-02", null), "2026-02")).isEqualTo("2026-02-28");
		assertThat(due(monthly(31, "2028-02", null), "2028-02")).isEqualTo("2028-02-29");
		assertThat(due(monthly(31, "2026-04", null), "2026-04")).isEqualTo("2026-04-30");
		assertThat(due(monthly(30, "2026-02", null), "2026-02")).isEqualTo("2026-02-28");
		assertThat(due(monthly(31, "2026-01", null), "2026-01")).isEqualTo("2026-01-31");
	}

	@Test
	void semiannualFromAnchor() {
		RecurringRule fromJan = new RecurringRule(RecurringCycle.SEMIANNUAL, 1, 10, YearMonth.of(2026, 1), null);
		assertThat(RecurringScheduleCalculator.periodsThrough(fromJan, YearMonth.of(2027, 3)))
			.extracting(DuePeriod::dueDate)
			.containsExactly(LocalDate.parse("2026-01-10"), LocalDate.parse("2026-07-10"),
					LocalDate.parse("2027-01-10"));

		RecurringRule fromMar = new RecurringRule(RecurringCycle.SEMIANNUAL, 1, 10, YearMonth.of(2026, 3), null);
		assertThat(RecurringScheduleCalculator.periodsThrough(fromMar, YearMonth.of(2026, 12)))
			.extracting(DuePeriod::period)
			.containsExactly(YearMonth.of(2026, 7));

		// anchor 在下半年也一樣：9 月 → 3 月、9 月
		RecurringRule fromSep = new RecurringRule(RecurringCycle.SEMIANNUAL, 9, 1, YearMonth.of(2026, 1), null);
		assertThat(RecurringScheduleCalculator.periodsThrough(fromSep, YearMonth.of(2026, 12)))
			.extracting(DuePeriod::period)
			.containsExactly(YearMonth.of(2026, 3), YearMonth.of(2026, 9));
	}

	@Test
	void yearly() {
		RecurringRule rule = new RecurringRule(RecurringCycle.YEARLY, 3, 15, YearMonth.of(2026, 1), null);

		assertThat(RecurringScheduleCalculator.periodsThrough(rule, YearMonth.of(2027, 12)))
			.extracting(DuePeriod::dueDate)
			.containsExactly(LocalDate.parse("2026-03-15"), LocalDate.parse("2027-03-15"));
	}

	@Test
	void endMonthStops() {
		RecurringRule rule = monthly(5, "2026-08", "2026-09");

		assertThat(RecurringScheduleCalculator.dueOnOrBefore(rule, LocalDate.parse("2026-12-31")))
			.extracting(DuePeriod::period)
			.containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9));
		assertThat(RecurringScheduleCalculator.nextDueAfter(rule, LocalDate.parse("2026-12-31"))).isEmpty();
		assertThat(RecurringScheduleCalculator.nextDueAfter(rule, LocalDate.parse("2026-09-01")))
			.contains(LocalDate.parse("2026-09-05"));
	}

	@Test
	void startInFutureHasNoDue() {
		RecurringRule rule = monthly(5, "2026-11", null);

		assertThat(RecurringScheduleCalculator.dueOnOrBefore(rule, LocalDate.parse("2026-10-22"))).isEmpty();
		assertThat(RecurringScheduleCalculator.nextDueAfter(rule, LocalDate.parse("2026-10-22")))
			.contains(LocalDate.parse("2026-11-05"));
	}

	/** 入帳日當天已經產生，所以「下次」是之後的那一期。 */
	@Test
	void nextDueIsStrictlyAfterToday() {
		RecurringRule rule = monthly(5, "2026-08", null);

		assertThat(RecurringScheduleCalculator.nextDueAfter(rule, LocalDate.parse("2026-10-05")))
			.contains(LocalDate.parse("2026-11-05"));
		RecurringRule yearly = new RecurringRule(RecurringCycle.YEARLY, 3, 15, YearMonth.of(2026, 1), null);
		assertThat(RecurringScheduleCalculator.nextDueAfter(yearly, LocalDate.parse("2026-10-22")))
			.contains(LocalDate.parse("2027-03-15"));
	}

	@Test
	void monthlyAverage() {
		assertThat(RecurringScheduleCalculator.monthlyAverage(RecurringCycle.YEARLY, new BigDecimal("10932")))
			.isEqualByComparingTo("911");
		assertThat(RecurringScheduleCalculator.monthlyAverage(RecurringCycle.YEARLY, new BigDecimal("1000")))
			.isEqualByComparingTo("83.3333333333");
		assertThat(RecurringScheduleCalculator.monthlyAverage(RecurringCycle.SEMIANNUAL, new BigDecimal("9600")))
			.isEqualByComparingTo("1600");
		assertThat(RecurringScheduleCalculator.monthlyAverage(RecurringCycle.MONTHLY, new BigDecimal("8000")))
			.isEqualByComparingTo("8000");
	}

	@Test
	void ruleValidation() {
		YearMonth start = YearMonth.of(2026, 1);
		assertThatThrownBy(() -> new RecurringRule(RecurringCycle.MONTHLY, null, 0, start, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecurringRule(RecurringCycle.MONTHLY, null, 32, start, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecurringRule(RecurringCycle.MONTHLY, 3, 5, start, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecurringRule(RecurringCycle.YEARLY, null, 5, start, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecurringRule(RecurringCycle.YEARLY, 13, 5, start, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RecurringRule(RecurringCycle.MONTHLY, null, 5, start, start.minusMonths(1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static RecurringRule monthly(int day, String start, String end) {
		return new RecurringRule(RecurringCycle.MONTHLY, null, day, YearMonth.parse(start),
				end == null ? null : YearMonth.parse(end));
	}

	private static String due(RecurringRule rule, String month) {
		return RecurringScheduleCalculator.periodsThrough(rule, YearMonth.parse(month)).getLast().dueDate().toString();
	}

}
