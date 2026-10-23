package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 股票頁需要的所有數字（spec 第 3 節第 3 頁）。
 *
 * @param marketValue    持股市值合計（有股價的部分）
 * @param cost           持股總成本
 * @param unrealized     帳面損益合計（有股價的部分）
 * @param returnPercent  股票報酬率（%）；沒有持股或股價時為 null
 * @param dividends      累計已領配息
 * @param annualDividend 預估年配息合計；沒有任何一檔有估計（缺除息資料或年配息次數）為 null
 * @param priceDate      股價更新至；還沒抓過為 null
 * @param stockListEmpty 股票清單還沒更新（第一次啟動沒網路）
 * @param admin          目前登入者是管理者（可以設定年配息次數）
 */
public record StocksView(BigDecimal marketValue, BigDecimal cost, BigDecimal unrealized, BigDecimal returnPercent,
		BigDecimal dividends, BigDecimal annualDividend, LocalDate priceDate, boolean stockListEmpty,
		List<HoldingRow> holdings, List<SaleRow> recentSales, List<TradeRow> trades,
		List<DividendSuggestion> dividendSuggestions, boolean admin) {
}
