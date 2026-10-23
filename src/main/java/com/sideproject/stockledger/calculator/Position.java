package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 一檔股票的持股（平均成本法）。
 *
 * @param cost      剩餘持股的總成本（含手續費），保留 10 位
 * @param ownTag    來源標記中自有的金額
 * @param loanTags  來源標記中各借款的金額（借款 id → 金額）
 * @param dividends 累計已領配息（含已經賣掉的那段時間）
 * @param loanShares 各借款持有的股數（借款 id → 股數，可有小數）：每筆買進按借款金額占總成本的比例分，用來算借款的帳面損益
 */
public record Position(Long stockId, long shares, BigDecimal cost, BigDecimal ownTag, Map<Long, BigDecimal> loanTags,
		BigDecimal dividends, Map<Long, BigDecimal> loanShares) {

	/** 平均成本（保留 10 位）；沒有持股時為 0。 */
	public BigDecimal averageCost() {
		return shares == 0 ? BigDecimal.ZERO : cost.divide(BigDecimal.valueOf(shares), 10, RoundingMode.HALF_UP);
	}

}
