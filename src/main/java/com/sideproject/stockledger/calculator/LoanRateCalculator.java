package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 加權平均利率 ＝ Σ(未償本金 × 年利率) ÷ Σ 未償本金（spec 5.3）。純 Java、不依賴 Spring。 */
public final class LoanRateCalculator {

	private static final int SCALE = 10;

	private LoanRateCalculator() {
	}

	public record Weighted(BigDecimal principal, BigDecimal annualRate) {
	}

	/** 總本金為 0（沒有借款或都還清了）時為 empty。 */
	public static Optional<BigDecimal> weightedAverage(List<Weighted> loans) {
		Objects.requireNonNull(loans, "loans");
		BigDecimal total = loans.stream().map(Weighted::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
		if (total.signum() <= 0) {
			return Optional.empty();
		}
		BigDecimal weighted = loans.stream()
			.map(l -> l.principal().multiply(l.annualRate()))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return Optional.of(weighted.divide(total, SCALE, RoundingMode.HALF_UP));
	}

}
