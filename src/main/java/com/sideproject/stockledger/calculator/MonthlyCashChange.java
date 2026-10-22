package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;

/**
 * 一個月內現金部分的淨資產變動（spec 4.3、DR-031、DR-035）。
 *
 * @param deposits       本月存入合計（正數）
 * @param withdrawals    本月支出合計（正數）
 * @param adjustments    本月對帳的餘額調整合計（可正可負）
 * @param total          deposits − withdrawals + adjustments
 */
public record MonthlyCashChange(BigDecimal deposits, BigDecimal withdrawals, BigDecimal adjustments, BigDecimal total) {
}
