package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;

/**
 * 一個月內現金部分的淨資產變動（spec 4.3、DR-031、DR-034）。
 *
 * @param deposits       本月存入合計（正數）
 * @param withdrawals    本月領出合計（正數，不含生活費）
 * @param livingExpenses 本月生活費合計（正數）
 * @param adjustments    本月對帳的餘額調整合計（可正可負）
 * @param total          deposits − withdrawals − livingExpenses + adjustments
 */
public record MonthlyCashChange(BigDecimal deposits, BigDecimal withdrawals, BigDecimal livingExpenses,
		BigDecimal adjustments, BigDecimal total) {
}
