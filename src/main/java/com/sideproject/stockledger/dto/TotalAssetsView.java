package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.util.List;

import com.sideproject.stockledger.calculator.MonthlyNetWorthChange;

/**
 * 總資產頁需要的所有數字（spec 第 3 節第 2 頁）。stockValue 是持股市值（還沒有股價的用成本）。
 * pledgedValue 是其中質押中的股票市值（質押股數 × 最新收盤價，沒有股價的不算）；只是另外標示，已含在 stockValue 與總資產裡。
 *
 * @param cashSharePercent 現金占總資產 %；總資產 ≤ 0 時為 null
 * @param allocation       資產配置排行，由大到小
 * @param largest          最大單一部位；沒有任何部位（或都 ≤ 0）時為 null
 * @param recent           最近 10 筆沒作廢的異動，新的在前
 * @param reminders        入帳日已經過了、還沒填的「每月自己填」期數（例如生活費），舊的在前
 */
public record TotalAssetsView(BigDecimal totalAssets, BigDecimal stockValue, BigDecimal pledgedValue, BigDecimal cash, int accountCount,
		BigDecimal cashSharePercent, MonthlyNetWorthChange monthChange, List<AllocationRow> allocation,
		AllocationRow largest, List<AccountCard> accounts, List<MovementRow> recent, List<UnfilledPeriod> reminders) {
}
