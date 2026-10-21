package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * 占比（例如資產配置的「占總資產 %」）。spec 第 5 節：比例顯示到 0.01%、HALF_UP；中間值保留 10 位、HALF_UP。
 * 純 Java、不依賴 Spring。
 */
public final class ShareCalculator {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private ShareCalculator() {
	}

	/**
	 * part × 100 ÷ total，到 0.01%。total ≤ 0 時比例沒有意義，回傳 empty。
	 * 先乘 100 再除：先除再乘會讓百分比只剩 8 位小數，總額大時會多進位一次。
	 */
	public static Optional<BigDecimal> percent(BigDecimal part, BigDecimal total) {
		Objects.requireNonNull(part, "part");
		Objects.requireNonNull(total, "total");
		if (total.signum() <= 0) {
			return Optional.empty();
		}
		BigDecimal percent = part.multiply(HUNDRED).divide(total, 10, RoundingMode.HALF_UP);
		return Optional.of(percent.setScale(2, RoundingMode.HALF_UP));
	}

}
