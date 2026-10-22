package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 質押每月的應計利息（DR-008、設計文件 6、11-2）：繳息期間跨月時按天數拆到兩個月；
 * 已經記了繳息的期間用實際金額（按天數比例分到各月）；還沒繳的天數用應計數。
 */
class LoanAccrualCalculatorTest {

	private static final BigDecimal BAL = new BigDecimal("400000");
	private static final List<RateChange> RATE_3 = List.of(new RateChange(LocalDate.of(2026, 1, 1), new BigDecimal("0.03")));
	private static final LocalDate START = LocalDate.of(2026, 9, 25);

	/** 還沒有任何繳息：10 月整個月都是應計（9/25 起算，10 月 31 天）。 */
	@Test
	void unpaidMonthIsAccrued() {
		BigDecimal october = LoanAccrualCalculator.pledgeMonth(BAL, List.of(), RATE_3, START, List.of(),
				YearMonth.of(2026, 10), LocalDate.of(2026, 12, 31));

		assertThat(october).isEqualByComparingTo(DailyInterestCalculator.interest(BAL, List.of(), RATE_3,
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)));
	}

	/** 本月還沒過完：只算到今天（含）。 */
	@Test
	void currentMonthAccruesUntilToday() {
		BigDecimal october = LoanAccrualCalculator.pledgeMonth(BAL, List.of(), RATE_3, START, List.of(),
				YearMonth.of(2026, 10), LocalDate.of(2026, 10, 22));

		assertThat(october).isEqualByComparingTo(DailyInterestCalculator.interest(BAL, List.of(), RATE_3,
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 23)));
	}

	/** 9/25～10/25 這一期實際繳了 1,000：按天數拆，9 月 6 天、10 月 24 天。 */
	@Test
	void paidPeriodUsesActualAmountSplitByDays() {
		List<LoanAccrualCalculator.Payment> paid = List.of(
				new LoanAccrualCalculator.Payment(LocalDate.of(2026, 10, 25), new BigDecimal("1000")));

		BigDecimal september = LoanAccrualCalculator.pledgeMonth(BAL, List.of(), RATE_3, START, paid,
				YearMonth.of(2026, 9), LocalDate.of(2026, 12, 31));
		BigDecimal october = LoanAccrualCalculator.pledgeMonth(BAL, List.of(), RATE_3, START, paid,
				YearMonth.of(2026, 10), LocalDate.of(2026, 10, 31));

		assertThat(september).isEqualByComparingTo("200");
		// 10 月：實際 1,000 的 24/30 ＝ 800，加上 10/25～10/31 共 7 天的應計
		BigDecimal tail = DailyInterestCalculator.interest(BAL, List.of(), RATE_3, LocalDate.of(2026, 10, 25),
				LocalDate.of(2026, 11, 1));
		assertThat(october).isEqualByComparingTo(new BigDecimal("800").add(tail));
		assertThat(september.add(october.subtract(tail))).isEqualByComparingTo("1000");
	}

	@Test
	void beforeStartIsZero() {
		assertThat(LoanAccrualCalculator.pledgeMonth(BAL, List.of(), RATE_3, START, List.of(), YearMonth.of(2026, 8),
				LocalDate.of(2026, 12, 31))).isEqualByComparingTo("0");
	}

	/** 信貸：扣款日在這個月的那幾期的利息（已記的用實際金額，攤還表已經處理）。 */
	@Test
	void creditMonthSumsInstallmentsDueInMonth() {
		List<Installment> schedule = List.of(
				new Installment(1, YearMonth.of(2026, 9), LocalDate.of(2026, 9, 5), BigDecimal.TEN,
						new BigDecimal("834"), BigDecimal.ZERO),
				new Installment(2, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 5), BigDecimal.TEN,
						new BigDecimal("821"), BigDecimal.ZERO));

		assertThat(LoanAccrualCalculator.creditMonth(schedule, YearMonth.of(2026, 10))).isEqualByComparingTo("821");
		assertThat(LoanAccrualCalculator.creditMonth(schedule, YearMonth.of(2026, 11))).isEqualByComparingTo("0");
	}

}
