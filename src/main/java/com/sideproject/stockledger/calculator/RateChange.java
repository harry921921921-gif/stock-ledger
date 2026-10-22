package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 借款年利率的一個版本：從 effectiveDate 起適用（0.025 = 2.5%）。
 */
public record RateChange(LocalDate effectiveDate, BigDecimal annualRate) {

	public RateChange {
		Objects.requireNonNull(effectiveDate, "effectiveDate");
		Objects.requireNonNull(annualRate, "annualRate");
	}

	/**
	 * 某一天適用的利率：生效日 ≤ 那天的最晚一個版本；同一天有好幾個版本時，清單裡較後面（較晚新增）的優先（同 DR-026）。
	 * 沒有任何版本生效時丟出例外（借款建立時一定有第一個版本）。
	 */
	public static BigDecimal rateOn(List<RateChange> rates, LocalDate day) {
		RateChange chosen = null;
		for (RateChange r : rates) {
			if (!r.effectiveDate().isAfter(day)
					&& (chosen == null || !r.effectiveDate().isBefore(chosen.effectiveDate()))) {
				chosen = r;
			}
		}
		if (chosen == null) {
			throw new IllegalArgumentException(day + " 沒有適用的利率");
		}
		return chosen.annualRate();
	}

}
