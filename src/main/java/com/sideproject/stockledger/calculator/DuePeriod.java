package com.sideproject.stockledger.calculator;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 固定項目的一期。
 *
 * @param period  期別：該期所在月份（資料庫存成那個月的 1 號）
 * @param dueDate 入帳日
 */
public record DuePeriod(YearMonth period, LocalDate dueDate) {
}
