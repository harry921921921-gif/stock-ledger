package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** 報酬率、二代健保、預估配息、維持率（spec 5.2、5.4、5.5、5.6）。 */
class StockMetricsCalculatorTest {

	@Test
	void stockReturnAndTotalReturn() {
		// 市值 58,750、成本 50,000 → 17.50%；加上配息 1,720 → 20.94%
		assertThat(StockMetricsCalculator.returnPercent(new BigDecimal("58750"), new BigDecimal("50000")))
			.hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("17.50"));
		assertThat(StockMetricsCalculator.totalReturnPercent(new BigDecimal("58750"), new BigDecimal("1720"),
				new BigDecimal("50000"))).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("20.94"));
		assertThat(StockMetricsCalculator.returnPercent(BigDecimal.TEN, BigDecimal.ZERO)).isEmpty();
	}

	@Test
	void marketValue() {
		assertThat(StockMetricsCalculator.marketValue(1500, new BigDecimal("58.75"))).isEqualByComparingTo("88125");
	}

	/** 二代健保：單次股利達 20,000 元才扣 2.11%，四捨五入到元（spec 5.2）。 */
	@Test
	void nhiSupplement() {
		BigDecimal rate = new BigDecimal("0.0211");
		BigDecimal threshold = new BigDecimal("20000");
		assertThat(StockMetricsCalculator.nhi(new BigDecimal("19999"), 0, rate, threshold)).isEqualByComparingTo("0");
		// 25,800 × 2.11% ＝ 544.38 → 544
		assertThat(StockMetricsCalculator.nhi(new BigDecimal("25800"), 0, rate, threshold)).isEqualByComparingTo("544");
		// 配股 2,000 股以面額 10 元算 20,000，達門檻
		assertThat(StockMetricsCalculator.nhi(BigDecimal.ZERO, 2000, rate, threshold)).isEqualByComparingTo("422");
	}

	/** 應領現金股利 ＝ 持有股數 × 每股現金股利，無條件捨去到元。 */
	@Test
	void cashDividendForShares() {
		assertThat(StockMetricsCalculator.cashDividend(15000, new BigDecimal("1.72"))).isEqualByComparingTo("25800");
		assertThat(StockMetricsCalculator.cashDividend(33, new BigDecimal("1.725"))).isEqualByComparingTo("56");
	}

	/** 預估年配息 ＝ 最近一期每股現金股利 × 年配息次數 × 持有股數（spec 5.6）。 */
	@Test
	void estimatedAnnualDividend() {
		assertThat(StockMetricsCalculator.annualDividend(new BigDecimal("1.72"), 4, 15000))
			.isEqualByComparingTo("103200");
	}

	/** 質押維持率 ＝ 擔保股票市值 ÷ 借款餘額（spec 5.5）；和提醒線、追繳線比較。 */
	@Test
	void maintenanceRatio() {
		assertThat(StockMetricsCalculator.maintenancePercent(new BigDecimal("881250"), new BigDecimal("400000")))
			.hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("220.31"));
		assertThat(StockMetricsCalculator.maintenancePercent(BigDecimal.TEN, BigDecimal.ZERO)).isEmpty();
		assertThat(StockMetricsCalculator.level(new BigDecimal("220.31"), new BigDecimal("150"), new BigDecimal("130")))
			.isEqualTo(StockMetricsCalculator.Level.OK);
		assertThat(StockMetricsCalculator.level(new BigDecimal("149.99"), new BigDecimal("150"), new BigDecimal("130")))
			.isEqualTo(StockMetricsCalculator.Level.WARN);
		assertThat(StockMetricsCalculator.level(new BigDecimal("129.99"), new BigDecimal("150"), new BigDecimal("130")))
			.isEqualTo(StockMetricsCalculator.Level.CALL);
	}

}
