package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** 淨資產 ＝ 總資產 − 總負債；槓桿比例 ＝ 總負債 ÷ 總資產（spec 第 2 節名詞定義）。 */
class NetWorthCalculatorTest {

	@Test
	void netWorth() {
		assertThat(NetWorthCalculator.netWorth(new BigDecimal("1500000"), new BigDecimal("800000")))
			.isEqualByComparingTo("700000");
	}

	/** 800,000 ÷ 1,500,000 ＝ 53.333…% → 53.33%（HALF_UP 到 0.01%）。 */
	@Test
	void leveragePercent() {
		assertThat(NetWorthCalculator.leveragePercent(new BigDecimal("800000"), new BigDecimal("1500000")))
			.hasValue(new BigDecimal("53.33"));
	}

	@Test
	void leverageWithoutAssetsIsEmpty() {
		assertThat(NetWorthCalculator.leveragePercent(new BigDecimal("800000"), BigDecimal.ZERO)).isEmpty();
	}

}
