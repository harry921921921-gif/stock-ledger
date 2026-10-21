package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 總資產頁的現金帳戶卡片。數字都由 Service 算好。
 *
 * @param lastReconciledOn 上次輸入實際餘額的日期；從沒對帳過為 null
 * @param lastAdjustment   上次對帳的餘額調整（實際 − 系統）；從沒對帳過為 null
 * @param reconcileOverdue 超過 30 天沒對帳（從上次對帳日 → 初始現金餘額日 → 帳戶建立日起算）
 * @param negative         餘額為負，提醒是否漏記存入
 * @param hasOpening       已經設定初始現金餘額（沒有時顯示「設定初始現金餘額」）
 */
public record AccountCard(Long id, String name, String typeLabel, BigDecimal balance, LocalDate lastReconciledOn,
		BigDecimal lastAdjustment, boolean reconcileOverdue, boolean negative, boolean hasOpening) {
}
