package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 固定收支頁需要的所有數字（spec 第 3 節第 5 頁、設計文件 4.4）。都由 RecurringItemService 算好，模板只顯示。
 *
 * @param monthlyIncome        本月有效的固定金額收入，月平均合計（整數元）
 * @param monthlyExpense       本月有效的固定金額支出，月平均合計（整數元）
 * @param incomeCount          上面收入合計的項目數
 * @param expenseCount         上面支出合計的項目數
 * @param manualFilledThisMonth 本月「每月自己填」已經填的金額合計
 * @param manualItemCount      本月有效的「每月自己填」項目數；0 時卡片顯示「沒有每月自己填的項目」
 * @param manualUnfilledNames  本月還沒填的「每月自己填」項目名稱
 * @param unfilled             所有還沒填的期數（舊的在前），每一列可以直接填金額
 * @param income               還沒結束的收入項目
 * @param expense              還沒結束的支出項目，依月平均由大到小，每月自己填排在最後
 * @param ended                已經結束的項目
 */
public record RecurringPageView(BigDecimal monthlyIncome, BigDecimal monthlyExpense, int incomeCount, int expenseCount,
		BigDecimal manualFilledThisMonth, int manualItemCount, List<String> manualUnfilledNames, List<UnfilledPeriod> unfilled,
		List<RecurringRow> income, List<RecurringRow> expense, List<RecurringRow> ended) {
}
