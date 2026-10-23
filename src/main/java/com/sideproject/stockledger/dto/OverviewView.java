package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.sideproject.stockledger.calculator.MonthlyNetWorthChange;

/**
 * 總覽頁（spec 第 3 節第 1 頁）。數字都由 OverviewService 算好，模板只顯示。
 *
 * @param month              淨資產變動顯示的月份
 * @param previousMonth      上一個月（切換用）
 * @param nextMonth          下一個月；已經是本月時為 null
 * @param stockReturnPercent 股票報酬率（%）；沒有持股或股價時為 null。v1 沒有融資，自備款報酬率等於它（spec 5.4）
 * @param loanNetGain        所有借款的借款淨賺合計；沒有借款時為 null
 * @param weightedRatePercent 加權平均利率（%）；沒有未還清的借款時為 null
 * @param leveragePercent    槓桿比例 ＝ 總負債 ÷ 總資產（%）；總資產 ≤ 0 時為 null
 * @param flows              本月資金移動（只改變資產形式的事件）
 * @param holdings           持股報酬表（同股票頁）
 */
public record OverviewView(BigDecimal netWorth, BigDecimal totalAssets, BigDecimal stockValue, BigDecimal cash,
		BigDecimal totalDebt, BigDecimal weightedRatePercent, BigDecimal leveragePercent, YearMonth month,
		YearMonth previousMonth, YearMonth nextMonth, MonthlyNetWorthChange monthChange,
		BigDecimal stockReturnPercent, BigDecimal loanNetGain, List<MaintenanceRow> maintenance,
		List<UnfilledPeriod> reminders, List<FlowRow> flows, List<HoldingRow> holdings, LocalDate priceDate) {

	/** 一筆質押的維持率。level：OK、WARN、CALL。 */
	public record MaintenanceRow(String loanName, BigDecimal percent, String level, BigDecimal warnPercent,
			BigDecimal callPercent) {
	}

	/** 本月資金移動的一類（例如「買進股票」）與金額合計（正數）。 */
	public record FlowRow(String label, BigDecimal amount) {
	}

}
