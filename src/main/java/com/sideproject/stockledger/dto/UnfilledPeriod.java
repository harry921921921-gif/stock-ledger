package com.sideproject.stockledger.dto;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 「每月自己填」的項目還沒填的一期（設計文件 4.3）。
 *
 * @param overdue 入帳日已經到了（≤ 今天）；總資產頁只提醒這種
 */
public record UnfilledPeriod(Long itemId, String itemName, YearMonth period, LocalDate dueDate, boolean overdue) {
}
