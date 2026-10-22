package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 質押按日計息（spec 5.3、設計文件 5、11-4）：每天的利息 ＝ 當天借款餘額 × 當天年利率 ÷ 365。
 * 算頭不算尾：還本、動撥、利率變動都從當天起生效。純 Java、不依賴 Spring。
 */
public final class DailyInterestCalculator {

	private static final int SCALE = 10;
	private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

	private DailyInterestCalculator() {
	}

	/**
	 * from（含）到 toExclusive（不含）的利息，保留 10 位、還沒進位。
	 *
	 * @param startBalance 起算時的借款餘額
	 * @param changes      所有本金變動（含期間之前的），每天的餘額 ＝ startBalance ＋ 當天以前（含）的變動
	 */
	public static BigDecimal interest(BigDecimal startBalance, List<PrincipalChange> changes, List<RateChange> rates,
			LocalDate from, LocalDate toExclusive) {
		return balanceRateSum(startBalance, changes, rates, from, toExclusive).divide(DAYS_PER_YEAR, SCALE,
				RoundingMode.HALF_UP);
	}

	/**
	 * 每天「餘額 × 年利率」的加總（還沒 ÷ 365、不進位）。按天數比例分攤實付利息時用，比例才不會有進位誤差。
	 */
	public static BigDecimal balanceRateSum(BigDecimal startBalance, List<PrincipalChange> changes,
			List<RateChange> rates, LocalDate from, LocalDate toExclusive) {
		Objects.requireNonNull(startBalance, "startBalance");
		Objects.requireNonNull(changes, "changes");
		Objects.requireNonNull(rates, "rates");
		BigDecimal total = BigDecimal.ZERO;
		for (LocalDate day = from; day.isBefore(toExclusive); day = day.plusDays(1)) {
			BigDecimal balance = balanceOn(startBalance, changes, day);
			if (balance.signum() <= 0) {
				continue;
			}
			total = total.add(balance.multiply(RateChange.rateOn(rates, day)));
		}
		return total;
	}

	/** 利息無條件進位到元（spec 第 5 節、DR-009）。 */
	public static BigDecimal roundUp(BigDecimal interest) {
		return interest.setScale(0, RoundingMode.UP);
	}

	/** 某一天的借款餘額（當天的變動已經生效）。 */
	public static BigDecimal balanceOn(BigDecimal startBalance, List<PrincipalChange> changes, LocalDate day) {
		return changes.stream()
			.filter(c -> !c.date().isAfter(day))
			.map(PrincipalChange::delta)
			.reduce(startBalance, BigDecimal::add);
	}

}
