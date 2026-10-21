package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** 占比顯示到 0.01%、HALF_UP（spec 第 5 節「報酬率、比例、維持率」）。 */
class ShareCalculatorTest {

	@Test
	void percentRoundsHalfUp() {
		// 2,247,700 ÷ 5,284,000 = 42.5378…%
		assertThat(ShareCalculator.percent(new BigDecimal("2247700"), new BigDecimal("5284000")))
			.hasValue(new BigDecimal("42.54"));
	}

	@Test
	void exactHalfRoundsUp() {
		// 1 ÷ 8 = 12.5% → 12.50；1 ÷ 16 = 6.25%；1 ÷ 32 = 3.125% → 3.13
		assertThat(ShareCalculator.percent(BigDecimal.ONE, new BigDecimal("32"))).hasValue(new BigDecimal("3.13"));
	}

	/** 先 ×100 再除，避免兩次進位：50 ÷ 1,000,001 = 0.004999995%，應該是 0.00 而不是 0.01（審核發現）。 */
	@Test
	void noDoubleRounding() {
		assertThat(ShareCalculator.percent(new BigDecimal("50"), new BigDecimal("1000001")))
			.hasValue(new BigDecimal("0.00"));
	}

	@Test
	void wholeIsHundred() {
		assertThat(ShareCalculator.percent(new BigDecimal("500"), new BigDecimal("500")))
			.hasValue(new BigDecimal("100.00"));
	}

	@Test
	void zeroTotalIsEmpty() {
		assertThat(ShareCalculator.percent(BigDecimal.ZERO, BigDecimal.ZERO)).isEmpty();
	}

	/** 淨值為負（例如帳戶透支）時總額可能 ≤ 0，比例沒有意義。 */
	@Test
	void negativeTotalIsEmpty() {
		assertThat(ShareCalculator.percent(BigDecimal.ONE, new BigDecimal("-100"))).isEmpty();
	}

}
