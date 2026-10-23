package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;

/**
 * 一筆借款的錢現在在哪（spec 4.1、第 3 節第 4 頁）。
 *
 * @param undeployed 尚未投入：撥款後還沒分配到任何買進的金額
 * @param deployed   已投入：各持股中這筆借款的標記合計
 * @param realized   這筆借款的已實現損益（按賣出當時借款持有的股數比例）
 * @param dividends  這筆借款領到的配息（按配息當時借款持有的股數比例）
 * @param lowestUndeployed 歷史上尚未投入的最低點；小於 0 表示有一段時間借款出的錢超過已經借到的（例如撥款日以前就拿去買）
 */
public record LoanUsage(BigDecimal undeployed, BigDecimal deployed, BigDecimal realized, BigDecimal dividends,
		BigDecimal lowestUndeployed) {
}
