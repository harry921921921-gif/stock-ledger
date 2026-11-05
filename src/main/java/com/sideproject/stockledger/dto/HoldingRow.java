package com.sideproject.stockledger.dto;

import java.math.BigDecimal;

/**
 * 股票頁持股表的一列（spec 第 3 節第 3 頁）。數字都由 TradeService 算好。
 *
 * @param averageCost        平均成本（含手續費，到 0.01）
 * @param price              最新收盤價；還沒有股價為 null
 * @param marketValue        市值（整數元）；沒有股價時為 null
 * @param unrealized         帳面損益；沒有股價時為 null
 * @param returnPercent      股票報酬率（%）；沒有股價時為 null
 * @param totalReturnPercent 含息總報酬率（%）；沒有股價時為 null
 * @param dividends          累計已領配息
 * @param annualDividend     預估年配息；沒有除息資料或年配息次數時為 null
 * @param dividendsPerYear   年配息次數（共用資料，管理者設定）；還沒設定為 null
 * @param sources            資金來源，例如「自有 40%、玉山質押 60%」
 * @param pledgedShares      質押中的擔保股數（還在質押中的借款合計；沒有為 0）
 */
public record HoldingRow(Long stockId, String code, String name, long shares, BigDecimal averageCost,
		BigDecimal cost, BigDecimal price, BigDecimal marketValue, BigDecimal unrealized, BigDecimal returnPercent,
		BigDecimal totalReturnPercent, BigDecimal dividends, BigDecimal annualDividend, Integer dividendsPerYear,
		String sources, long pledgedShares) {
}
