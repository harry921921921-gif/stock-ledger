package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 股票的資金來源按張數分配（設計文件 2026-10-25-onboarding 3.4）。 */
class LoanShareAllocationCalculatorTest {

	private static final BigDecimal COST = new BigDecimal("736047");

	/** 設計文件的例子：10 張 × 73.5，總成本 736,047；玉山 4 張、土銀 3 張，剩下 220,814 是自己的錢。 */
	@Test
	void specExample() {
		List<BigDecimal> amounts = LoanShareAllocationCalculator.amountsFromLots(COST, 10000, List.of(4L, 3L));

		assertThat(amounts).containsExactly(new BigDecimal("294419"), new BigDecimal("220814"));
		assertThat(COST.subtract(amounts.get(0)).subtract(amounts.get(1))).isEqualByComparingTo("220814");
	}

	/** 全部張數都是借款：加起來等於總成本。 */
	@Test
	void allLoanSumsToTotal() {
		List<BigDecimal> amounts = LoanShareAllocationCalculator.amountsFromLots(COST, 10000, List.of(6L, 4L));

		assertThat(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(COST);
	}

	/** 全部是借款、各自四捨五入後少 1 元：最後一筆借款吸收差額（333 + 333 + 334）。 */
	@Test
	void lastLoanAbsorbsDifferenceWhenNoOwnMoney() {
		assertThat(LoanShareAllocationCalculator.amountsFromLots(new BigDecimal("1000"), 3000, List.of(1L, 1L, 1L)))
			.containsExactly(new BigDecimal("333"), new BigDecimal("333"), new BigDecimal("334"));
	}

	/** 還有自己的錢：差額算自己的，借款不吸收（333 + 333，自己 334）。 */
	@Test
	void ownMoneyKeepsDifference() {
		assertThat(LoanShareAllocationCalculator.amountsFromLots(new BigDecimal("1000"), 3000, List.of(1L, 1L)))
			.containsExactly(new BigDecimal("333"), new BigDecimal("333"));
	}

	/** 剛好 .5 進位（HALF_UP）：1 張 ÷ 2 張 × 1,001 = 500.5 → 501。 */
	@Test
	void roundsHalfUp() {
		assertThat(LoanShareAllocationCalculator.amountsFromLots(new BigDecimal("1001"), 2000, List.of(1L)))
			.containsExactly(new BigDecimal("501"));
	}

	@Test
	void noLoansIsEmpty() {
		assertThat(LoanShareAllocationCalculator.amountsFromLots(COST, 10000, List.of())).isEmpty();
	}

	@Test
	void zeroSharesRejected() {
		assertThatThrownBy(() -> LoanShareAllocationCalculator.amountsFromLots(COST, 0, List.of(1L)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/** 換回張數：剛好是張數換算出來的金額就換得回來。 */
	@Test
	void lotsFromAmountsRoundTrip() {
		assertThat(LoanShareAllocationCalculator.lotsFromAmounts(COST, 10000,
				List.of(new BigDecimal("294419"), new BigDecimal("220814.0000"))))
			.hasValue(List.of(4L, 3L));
	}

	/** 全部借款、最後一筆吸收差額的金額也換得回來。 */
	@Test
	void lotsFromAmountsWithAbsorbedDifference() {
		assertThat(LoanShareAllocationCalculator.lotsFromAmounts(new BigDecimal("1000"), 3000,
				List.of(new BigDecimal("333"), new BigDecimal("333"), new BigDecimal("334"))))
			.hasValue(List.of(1L, 1L, 1L));
	}

	/** 300,000 換成 4 張是 294,419，不等於原金額：換不回來（畫面用填金額）。 */
	@Test
	void lotsFromAmountsNotExact() {
		assertThat(LoanShareAllocationCalculator.lotsFromAmounts(COST, 10000, List.of(new BigDecimal("300000"))))
			.isEmpty();
	}

	/** 金額大到張數放不進 long（例如 1e25 元）：換不回來，回傳 empty，不丟 ArithmeticException。 */
	@Test
	void lotsFromHugeAmountIsEmpty() {
		assertThat(LoanShareAllocationCalculator.lotsFromAmounts(COST, 10000, List.of(new BigDecimal("1E+25"))))
			.isEmpty();
	}

	/** 每筆都放得進 long、但張數加起來超過：也是 empty。 */
	@Test
	void lotsFromTwoHugeAmountsIsEmpty() {
		BigDecimal huge = new BigDecimal("6.6E+20");
		assertThat(LoanShareAllocationCalculator.lotsFromAmounts(COST, 10000, List.of(huge, huge))).isEmpty();
	}

	@Test
	void lotsFromAmountsZeroCostIsEmpty() {
		assertThat(LoanShareAllocationCalculator.lotsFromAmounts(BigDecimal.ZERO, 10000, List.of(BigDecimal.ONE)))
			.isEmpty();
	}

}
