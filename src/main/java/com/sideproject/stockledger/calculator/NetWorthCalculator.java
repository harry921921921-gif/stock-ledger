package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** 淨資產與槓桿比例（spec 第 2 節名詞定義）。純 Java、不依賴 Spring。 */
public final class NetWorthCalculator {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private NetWorthCalculator() {
	}

	/** 淨資產 ＝ 總資產 − 總負債。 */
	public static BigDecimal netWorth(BigDecimal totalAssets, BigDecimal totalDebt) {
		return totalAssets.subtract(totalDebt);
	}

	/** 槓桿比例 ＝ 總負債 ÷ 總資產（%，HALF_UP 到 0.01）；總資產 ≤ 0 時為 empty。 */
	public static Optional<BigDecimal> leveragePercent(BigDecimal totalDebt, BigDecimal totalAssets) {
		if (totalAssets.signum() <= 0) {
			return Optional.empty();
		}
		return Optional.of(totalDebt.multiply(HUNDRED).divide(totalAssets, 2, RoundingMode.HALF_UP));
	}

}
