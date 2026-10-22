package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 固定收支頁項目清單的一列。
 *
 * @param amount         每期金額（整數元）；每月自己填為 null
 * @param manual         每月自己填（畫面顯示「每月自己填」）
 * @param scheduleLabel  例如「每月 5 號」「每年 3 月 15 號」「每半年 1、7 月 10 號」
 * @param monthlyAverage 月平均（整數元）；每月自己填為 null
 * @param nextDue        下次入帳日；已結束為 null
 * @param endMonth       結束月份；沒有結束為 null
 */
public record RecurringRow(Long id, String name, BigDecimal amount, boolean manual, String scheduleLabel,
		BigDecimal monthlyAverage, LocalDate nextDue, String accountName, YearMonth startMonth, YearMonth endMonth) {
}
