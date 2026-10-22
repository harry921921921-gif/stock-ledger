package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 信貸本息平均攤還（spec 4.8、5.3、DR-018、設計文件 4、11-1、11-4）。數字照設計文件：40 萬、2.5%、60 期。 */
class InstallmentCalculatorTest {

	private static final BigDecimal P = new BigDecimal("400000");
	private static final BigDecimal RATE = new BigDecimal("0.025");
	private static final YearMonth FIRST = YearMonth.of(2026, 1);

	@Test
	void paymentMatchesFormula() {
		assertThat(InstallmentCalculator.monthlyPayment(P, RATE, 60)).isEqualByComparingTo("7099");
		// 利率 0：本金平均分攤
		assertThat(InstallmentCalculator.monthlyPayment(new BigDecimal("1200"), BigDecimal.ZERO, 12))
			.isEqualByComparingTo("100");
	}

	/** 先乘後除：3.5% ÷ 12 先四捨五入會變成 0.0029166667，24 萬的利息會多算 1 元（審查 Important 1）。 */
	@Test
	void interestMultipliesBeforeDividing() {
		assertThat(InstallmentCalculator.monthlyInterest(new BigDecimal("240000"), new BigDecimal("0.035")))
			.isEqualByComparingTo("700");
		assertThat(InstallmentCalculator.monthlyInterest(new BigDecimal("1200000"), new BigDecimal("0.0275")))
			.isEqualByComparingTo("2750");
		assertThat(InstallmentCalculator.monthlyInterest(new BigDecimal("400000"), RATE)).isEqualByComparingTo("834");
	}

	@Test
	void firstAndLastInstallment() {
		List<Installment> s = InstallmentCalculator.schedule(input(List.of(), List.of(), Map.of()));

		assertThat(s).hasSize(60);
		assertThat(s.get(0)).satisfies(i -> {
			assertThat(i.number()).isEqualTo(1);
			assertThat(i.period()).isEqualTo(FIRST);
			assertThat(i.dueDate()).isEqualTo(LocalDate.of(2026, 1, 5));
			assertThat(i.interest()).isEqualByComparingTo("834");
			assertThat(i.principal()).isEqualByComparingTo("6265");
			assertThat(i.remainingAfter()).isEqualByComparingTo("393735");
		});
		assertThat(s.get(59)).satisfies(i -> {
			assertThat(i.principal()).isEqualByComparingTo("7110");
			assertThat(i.interest()).isEqualByComparingTo("15");
			assertThat(i.remainingAfter()).isEqualByComparingTo("0");
		});
	}

	/** 利率變動：生效日之後的第一期起，用剩餘本金、剩餘期數、新利率重算月付金（DR-018）。 */
	@Test
	void rateChangeRecalculates() {
		List<Installment> base = InstallmentCalculator.schedule(input(List.of(), List.of(), Map.of()));
		BigDecimal after12 = base.get(11).remainingAfter();
		List<RateChange> rates = List.of(new RateChange(LocalDate.of(2026, 1, 1), RATE),
				new RateChange(LocalDate.of(2026, 12, 20), new BigDecimal("0.03")));

		List<Installment> s = InstallmentCalculator.schedule(input(rates, List.of(), Map.of()));

		BigDecimal payment = InstallmentCalculator.monthlyPayment(after12, new BigDecimal("0.03"), 48);
		Installment p13 = s.get(12);
		assertThat(p13.interest()).isEqualByComparingTo(after12.multiply(new BigDecimal("0.0025")).setScale(0,
				java.math.RoundingMode.UP));
		assertThat(p13.principal().add(p13.interest())).isEqualByComparingTo(payment);
		assertThat(s.get(59).remainingAfter()).isEqualByComparingTo("0");
	}

	/** 提前還本：期數不變、月付金變小；下一期利息用還本後的本金（設計文件 4、11-4）。 */
	@Test
	void prepaymentKeepsPeriodsLowersPayment() {
		List<Installment> base = InstallmentCalculator.schedule(input(List.of(), List.of(), Map.of()));
		BigDecimal after12 = base.get(11).remainingAfter();

		List<Installment> s = InstallmentCalculator.schedule(input(List.of(),
				List.of(new PrincipalChange(LocalDate.of(2026, 12, 20), new BigDecimal("-100000"))), Map.of()));

		BigDecimal reduced = after12.subtract(new BigDecimal("100000"));
		BigDecimal payment = InstallmentCalculator.monthlyPayment(reduced, RATE, 48);
		Installment p13 = s.get(12);
		assertThat(p13.interest()).isEqualByComparingTo(InstallmentCalculator.monthlyInterest(reduced, RATE));
		assertThat(p13.principal().add(p13.interest())).isEqualByComparingTo(payment);
		assertThat(s).hasSize(60);
		assertThat(s.get(59).remainingAfter()).isEqualByComparingTo("0");
	}

	/** 已經記下的期數用實際金額（例如改成銀行的數字）；月付金不重算，差額留到最後一期（設計文件 11-1）。 */
	@Test
	void postedAmountsCarryDifferenceToLastPeriod() {
		List<Installment> base = InstallmentCalculator.schedule(input(List.of(), List.of(), Map.of()));

		List<Installment> s = InstallmentCalculator.schedule(input(List.of(), List.of(),
				Map.of(FIRST, new InstallmentCalculator.Posted(new BigDecimal("6260"), new BigDecimal("839")))));

		assertThat(s.get(0).principal()).isEqualByComparingTo("6260");
		assertThat(s.get(0).interest()).isEqualByComparingTo("839");
		assertThat(s.get(1).principal().add(s.get(1).interest()))
			.isEqualByComparingTo(base.get(1).principal().add(base.get(1).interest()));
		assertThat(s.get(59).remainingAfter()).isEqualByComparingTo("0");
		assertThat(s.get(59).principal()).isGreaterThan(base.get(59).principal());
	}

	/** 作廢的那一期也算已經過去：本金沒還，留到最後一期（設計文件 11-1）。 */
	@Test
	void voidedPeriodStillCountsAsPassed() {
		List<Installment> s = InstallmentCalculator.schedule(input(List.of(), List.of(),
				Map.of(FIRST, new InstallmentCalculator.Posted(BigDecimal.ZERO, BigDecimal.ZERO))));

		assertThat(s).hasSize(60);
		assertThat(s.get(0).remainingAfter()).isEqualByComparingTo("400000");
		assertThat(s.get(59).remainingAfter()).isEqualByComparingTo("0");
	}

	/** 全部提前還清：之後沒有期數。 */
	@Test
	void fullPrepaymentEndsSchedule() {
		List<Installment> s = InstallmentCalculator.schedule(input(List.of(),
				List.of(new PrincipalChange(LocalDate.of(2026, 1, 3), new BigDecimal("-400000"))), Map.of()));

		assertThat(s).isEmpty();
	}

	@Test
	void dayClampedToMonthEnd() {
		InstallmentCalculator.Input in = new InstallmentCalculator.Input(P, 60, FIRST, 31,
				List.of(new RateChange(LocalDate.of(2026, 1, 1), RATE)), List.of(), Map.of());

		assertThat(InstallmentCalculator.schedule(in).get(1).dueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
	}

	private static InstallmentCalculator.Input input(List<RateChange> rates, List<PrincipalChange> prepayments,
			Map<YearMonth, InstallmentCalculator.Posted> posted) {
		List<RateChange> r = rates.isEmpty() ? List.of(new RateChange(LocalDate.of(2026, 1, 1), RATE)) : rates;
		return new InstallmentCalculator.Input(P, 60, FIRST, 5, r, prepayments, posted);
	}

}
