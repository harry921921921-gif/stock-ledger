package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** 手續費與證交稅（spec 5.1、4.2-7、DR-009）。 */
class FeeCalculatorTest {

	/** 手續費率 0.1425%、2.8 折、整股最低 20、零股最低 1、證交稅 0.1%（ETF）。 */
	private static final FeeCalculator.Rates ETF = new FeeCalculator.Rates(new BigDecimal("0.001425"),
			new BigDecimal("0.28"), new BigDecimal("20"), new BigDecimal("1"), new BigDecimal("0.001"));

	@Test
	void tradeAmountTruncatesToDollar() {
		assertThat(FeeCalculator.tradeAmount(1000, new BigDecimal("58.75"))).isEqualByComparingTo("58750");
		// 零股 33 股 × 58.75 ＝ 1,938.75 → 1,938
		assertThat(FeeCalculator.tradeAmount(33, new BigDecimal("58.75"))).isEqualByComparingTo("1938");
	}

	@Test
	void feeWithDiscountRoundsDown() {
		// 58,750 × 0.001425 × 0.28 ＝ 23.44 → 23
		assertThat(FeeCalculator.fee(new BigDecimal("58750"), 1000, ETF)).isEqualByComparingTo("23");
	}

	/** 整股最低 20 元；零股（不是 1,000 的倍數）最低 1 元。 */
	@Test
	void minimumFeeByLotType() {
		// 10,000 × 0.001425 × 0.28 ＝ 3.99 → 3，整股最低 20
		assertThat(FeeCalculator.fee(new BigDecimal("10000"), 1000, ETF)).isEqualByComparingTo("20");
		// 1,938 × 0.001425 × 0.28 ＝ 0.77 → 0，零股最低 1
		assertThat(FeeCalculator.fee(new BigDecimal("1938"), 33, ETF)).isEqualByComparingTo("1");
		assertThat(FeeCalculator.isOddLot(1500)).isTrue();
		assertThat(FeeCalculator.isOddLot(2000)).isFalse();
	}

	@Test
	void securitiesTaxRoundsDown() {
		// 58,750 × 0.1% ＝ 58.75 → 58
		assertThat(FeeCalculator.tax(new BigDecimal("58750"), ETF)).isEqualByComparingTo("58");
	}

	/** 預估入帳 ＝ 成交金額 − 手續費 − 證交稅（spec 5.4，沒有融資）。 */
	@Test
	void estimatedSellProceeds() {
		assertThat(FeeCalculator.sellProceeds(new BigDecimal("58750"), new BigDecimal("23"), new BigDecimal("58")))
			.isEqualByComparingTo("58669");
	}

}
