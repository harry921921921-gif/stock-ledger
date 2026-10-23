package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sideproject.stockledger.entity.TradeType;

/** 平均成本、部分賣出、配股、資金來源標記、還本轉換（spec 4.1、4.7、DR-015、DR-016、DR-017）。 */
class PortfolioCalculatorTest {

	private static final Long S1 = 1L;
	private static final Long S2 = 2L;
	private static final Long LOAN_A = 10L;
	private static final LocalDate D = LocalDate.of(2026, 10, 1);

	private long nextId = 1;

	/** 期初 1,000 股成本 50,000（自有 2 萬、借款 A 3 萬）＋買進 1,000 股 60,000（自有）→ 平均成本 55。 */
	@Test
	void averageCostAndTags() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.OPENING, D, S1, 1000, "50000", Map.of(LOAN_A, new BigDecimal("30000"))),
				trade(TradeType.BUY, D.plusDays(1), S1, 1000, "60000", Map.of())), List.of(), List.of());

		Position pos = p.positions().get(S1);
		assertThat(pos.shares()).isEqualTo(2000);
		assertThat(pos.cost()).isEqualByComparingTo("110000");
		assertThat(pos.averageCost()).isEqualByComparingTo("55");
		assertThat(pos.ownTag()).isEqualByComparingTo("80000");
		assertThat(pos.loanTags()).containsEntry(LOAN_A, new BigDecimal("30000"));
	}

	/** 賣出 500 股、實際入帳 30,000：賣出成本 27,500、已實現 2,500；來源按比例減少（spec 4.7-1、4.7-2）。 */
	@Test
	void partialSellReducesCostAndTagsProportionally() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.OPENING, D, S1, 1000, "50000", Map.of(LOAN_A, new BigDecimal("30000"))),
				trade(TradeType.BUY, D.plusDays(1), S1, 1000, "60000", Map.of()),
				trade(TradeType.SELL, D.plusDays(2), S1, 500, "30000", Map.of())), List.of(), List.of());

		Position pos = p.positions().get(S1);
		assertThat(pos.shares()).isEqualTo(1500);
		assertThat(pos.cost()).isEqualByComparingTo("82500");
		assertThat(pos.averageCost()).isEqualByComparingTo("55");
		assertThat(pos.ownTag()).isEqualByComparingTo("60000");
		assertThat(pos.loanTags().get(LOAN_A)).isEqualByComparingTo("22500");
		RealizedSale sale = p.sales().get(0);
		assertThat(sale.costOfSold()).isEqualByComparingTo("27500");
		assertThat(sale.realized()).isEqualByComparingTo("2500");
		// 借款 A 持有 600 股（期初 1000 股中借款出 3/5）、占 2000 股的 30%：入帳 30,000 × 30% − 成本 30,000 × 25% ＝ 1,500
		// （DR-045，2026-10-23 使用者同意改成按股數；原本按成本比例是 681.82）
		assertThat(p.loans().get(LOAN_A).realized()).isEqualByComparingTo("1500");
	}

	/** 全部賣光：持股與標記歸零。 */
	@Test
	void sellAllClearsPosition() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.BUY, D, S1, 1000, "50000", Map.of(LOAN_A, new BigDecimal("50000"))),
				trade(TradeType.SELL, D.plusDays(1), S1, 1000, "52000", Map.of())), List.of(),
				List.of(new LoanFlow(LOAN_A, D, new BigDecimal("100000"))));

		assertThat(p.positions().get(S1).shares()).isZero();
		assertThat(p.positions().get(S1).cost()).isEqualByComparingTo("0");
		assertThat(p.loans().get(LOAN_A).deployed()).isEqualByComparingTo("0");
		// 賣股入帳全部算自有，不回到借款的尚未投入（DR-015）
		assertThat(p.loans().get(LOAN_A).undeployed()).isEqualByComparingTo("50000");
	}

	/** 配股：股數增加、成本不變、平均成本下降（spec 4.2-5）。 */
	@Test
	void stockDividendLowersAverageCost() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.BUY, D, S1, 1000, "50000", Map.of()),
				trade(TradeType.STOCK_DIVIDEND, D.plusDays(1), S1, 100, "0", Map.of())), List.of(), List.of());

		assertThat(p.positions().get(S1).shares()).isEqualTo(1100);
		assertThat(p.positions().get(S1).averageCost()).isEqualByComparingTo("45.4545454545");
	}

	/** 撥款進尚未投入；買進用掉；還本先扣尚未投入，不足的部分按比例把各持股的標記轉成自有（spec 4.1-6、DR-017）。 */
	@Test
	void repaymentUsesUndeployedThenConvertsTags() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.BUY, D, S1, 1000, "30000", Map.of(LOAN_A, new BigDecimal("30000"))),
				trade(TradeType.BUY, D, S2, 1000, "10000", Map.of(LOAN_A, new BigDecimal("10000")))), List.of(),
				List.of(new LoanFlow(LOAN_A, D, new BigDecimal("100000")),
						new LoanFlow(LOAN_A, D.plusDays(5), new BigDecimal("-80000"))));

		// 尚未投入 60,000 先扣完，剩 20,000 按 30:10 轉成自有
		assertThat(p.loans().get(LOAN_A).undeployed()).isEqualByComparingTo("0");
		assertThat(p.positions().get(S1).loanTags().get(LOAN_A)).isEqualByComparingTo("15000");
		assertThat(p.positions().get(S1).ownTag()).isEqualByComparingTo("15000");
		assertThat(p.positions().get(S2).loanTags().get(LOAN_A)).isEqualByComparingTo("5000");
		assertThat(p.loans().get(LOAN_A).deployed()).isEqualByComparingTo("20000");
	}

	/** 配息依當時的來源比例分到各借款（spec 4.1-5）。 */
	@Test
	void dividendsSplitByTags() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.BUY, D, S1, 1000, "40000", Map.of(LOAN_A, new BigDecimal("30000")))),
				List.of(new DividendIncome(D.plusDays(10), S1, new BigDecimal("1720"))),
				List.of(new LoanFlow(LOAN_A, D, new BigDecimal("400000"))));

		assertThat(p.positions().get(S1).dividends()).isEqualByComparingTo("1720");
		assertThat(p.loans().get(LOAN_A).dividends()).isEqualByComparingTo("1290");
		assertThat(p.loans().get(LOAN_A).undeployed()).isEqualByComparingTo("370000");
	}

	/**
	 * 借款持有的股數：每筆買進按「借款金額 ÷ 總成本」分股數；賣出按比例減少；還本轉自有時股數跟著轉。
	 * 期初 1000 股 50,000（借 30,000 → 600 股）、再買 1000 股 60,000（借 0）；賣 500 股 → 借款剩 450 股。
	 */
	@Test
	void loanSharesFollowEachPurchase() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.OPENING, D, S1, 1000, "50000", Map.of(LOAN_A, new BigDecimal("30000"))),
				trade(TradeType.BUY, D.plusDays(1), S1, 1000, "60000", Map.of()),
				trade(TradeType.SELL, D.plusDays(2), S1, 500, "30000", Map.of())), List.of(), List.of());

		assertThat(p.positions().get(S1).loanShares().get(LOAN_A)).isEqualByComparingTo("450");
	}

	/** 還本把一半的標記轉成自有 → 借款股數也少一半。 */
	@Test
	void repaymentMovesLoanShares() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.BUY, D, S1, 1000, "30000", Map.of(LOAN_A, new BigDecimal("30000")))), List.of(),
				List.of(new LoanFlow(LOAN_A, D, new BigDecimal("30000")),
						new LoanFlow(LOAN_A, D.plusDays(5), new BigDecimal("-15000"))));

		assertThat(p.positions().get(S1).loanShares().get(LOAN_A)).isEqualByComparingTo("500");
	}

	/** 配股：借款持有的股數按比例一起增加（1000 股配 100 股 → 借款的 600 股變 660 股）。 */
	@Test
	void stockDividendScalesLoanShares() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.OPENING, D, S1, 1000, "50000", Map.of(LOAN_A, new BigDecimal("30000"))),
				trade(TradeType.STOCK_DIVIDEND, D.plusDays(1), S1, 100, "0", Map.of())), List.of(), List.of());

		assertThat(p.positions().get(S1).loanShares().get(LOAN_A)).isEqualByComparingTo("660");
	}

	/** 借款在撥款以前就被拿去買股票：最後加總是 0 也不行，要看每一天（lowestUndeployed）。 */
	@Test
	void lowestUndeployedCatchesSpendingBeforeDisbursement() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.BUY, D, S1, 1000, "40000", Map.of(LOAN_A, new BigDecimal("40000")))), List.of(),
				List.of(new LoanFlow(LOAN_A, D.plusDays(30), new BigDecimal("40000"))));

		assertThat(p.loans().get(LOAN_A).undeployed()).isEqualByComparingTo("0");
		assertThat(p.loans().get(LOAN_A).lowestUndeployed()).isEqualByComparingTo("-40000");
	}

	/** 配息按借款股數分：借款買的 1000 股 @40、自有的 1000 股 @20；配息 2,000 → 借款分 1,000（按成本比例會是 1,333）。 */
	@Test
	void dividendsSplitByLoanShares() {
		Portfolio p = PortfolioCalculator.compute(List.of(
				trade(TradeType.OPENING, D, S1, 1000, "20000", Map.of()),
				trade(TradeType.BUY, D.plusDays(1), S1, 1000, "40000", Map.of(LOAN_A, new BigDecimal("40000")))),
				List.of(new DividendIncome(D.plusDays(10), S1, new BigDecimal("2000"))),
				List.of(new LoanFlow(LOAN_A, D, new BigDecimal("40000"))));

		assertThat(p.loans().get(LOAN_A).dividends()).isEqualByComparingTo("1000");
	}

	@Test
	void sellMoreThanHeldThrows() {
		assertThatThrownBy(() -> PortfolioCalculator.compute(List.of(
				trade(TradeType.BUY, D, S1, 1000, "50000", Map.of()),
				trade(TradeType.SELL, D.plusDays(1), S1, 1001, "50000", Map.of())), List.of(), List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/** 某一天的持股（賣出股數檢查用）。 */
	@Test
	void sharesOnDate() {
		List<TradeEvent> trades = List.of(trade(TradeType.BUY, D, S1, 1000, "50000", Map.of()),
				trade(TradeType.SELL, D.plusDays(5), S1, 400, "20000", Map.of()));

		assertThat(PortfolioCalculator.sharesOn(trades, S1, D.plusDays(4))).isEqualTo(1000);
		assertThat(PortfolioCalculator.sharesOn(trades, S1, D.plusDays(5))).isEqualTo(600);
	}

	private TradeEvent trade(TradeType type, LocalDate date, Long stock, long shares, String amount,
			Map<Long, BigDecimal> loans) {
		return new TradeEvent(nextId++, type, date, stock, shares, new BigDecimal(amount), loans);
	}

}
