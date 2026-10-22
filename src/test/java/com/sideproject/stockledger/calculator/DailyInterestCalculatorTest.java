package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** 質押按日計息與加權平均利率（spec 5.3、設計文件 5、11-4）。 */
class DailyInterestCalculatorTest {

	private static final BigDecimal BAL = new BigDecimal("400000");
	private static final List<RateChange> RATE_3 = List.of(new RateChange(LocalDate.of(2026, 1, 1), new BigDecimal("0.03")));

	@Test
	void pledgeThirtyDays() {
		BigDecimal raw = DailyInterestCalculator.interest(BAL, List.of(), RATE_3, LocalDate.of(2026, 10, 1),
				LocalDate.of(2026, 10, 31));

		assertThat(raw).isEqualByComparingTo("986.3013698630");
		assertThat(DailyInterestCalculator.roundUp(raw)).isEqualByComparingTo("987");
	}

	/** 算頭不算尾：還本當天起用新的餘額。 */
	@Test
	void pledgeBalanceChangeMidPeriod() {
		BigDecimal raw = DailyInterestCalculator.interest(BAL,
				List.of(new PrincipalChange(LocalDate.of(2026, 10, 16), new BigDecimal("-100000"))), RATE_3,
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

		// 15 天 40 萬 + 15 天 30 萬
		BigDecimal expected = DailyInterestCalculator.interest(BAL, List.of(), RATE_3, LocalDate.of(2026, 10, 1),
				LocalDate.of(2026, 10, 16))
			.add(DailyInterestCalculator.interest(new BigDecimal("300000"), List.of(), RATE_3,
					LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 31)));
		assertThat(raw).isEqualByComparingTo(expected);
	}

	/** 期間之前的還本也要算進起始餘額。 */
	@Test
	void earlierChangesReduceBalance() {
		BigDecimal raw = DailyInterestCalculator.interest(BAL,
				List.of(new PrincipalChange(LocalDate.of(2026, 9, 10), new BigDecimal("-100000"))), RATE_3,
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

		assertThat(raw).isEqualByComparingTo(DailyInterestCalculator.interest(new BigDecimal("300000"), List.of(),
				RATE_3, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
	}

	@Test
	void pledgeRateChangeMidPeriod() {
		List<RateChange> rates = List.of(new RateChange(LocalDate.of(2026, 1, 1), new BigDecimal("0.03")),
				new RateChange(LocalDate.of(2026, 10, 16), new BigDecimal("0.032")));

		BigDecimal raw = DailyInterestCalculator.interest(BAL, List.of(), rates, LocalDate.of(2026, 10, 1),
				LocalDate.of(2026, 10, 31));

		// 400000 × (0.03 × 15 + 0.032 × 15) ÷ 365
		assertThat(raw).isEqualByComparingTo("1019.1780821918");
	}

	/** 同一天有好幾個版本時，較晚新增（清單較後面）的優先（同 DR-026）。 */
	@Test
	void sameDayLaterVersionWins() {
		List<RateChange> rates = List.of(new RateChange(LocalDate.of(2026, 10, 1), new BigDecimal("0.03")),
				new RateChange(LocalDate.of(2026, 10, 1), new BigDecimal("0.032")));

		assertThat(RateChange.rateOn(rates, LocalDate.of(2026, 10, 1))).isEqualByComparingTo("0.032");
	}

	@Test
	void emptyPeriodIsZero() {
		assertThat(DailyInterestCalculator.interest(BAL, List.of(), RATE_3, LocalDate.of(2026, 10, 1),
				LocalDate.of(2026, 10, 1))).isEqualByComparingTo("0");
	}

	@Test
	void weightedAverage() {
		Optional<BigDecimal> avg = LoanRateCalculator.weightedAverage(List.of(
				new LoanRateCalculator.Weighted(new BigDecimal("400000"), new BigDecimal("0.025")),
				new LoanRateCalculator.Weighted(new BigDecimal("400000"), new BigDecimal("0.03"))));

		assertThat(avg).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.0275"));
		assertThat(LoanRateCalculator.weightedAverage(List.of(
				new LoanRateCalculator.Weighted(BigDecimal.ZERO, new BigDecimal("0.03"))))).isEmpty();
	}

}
