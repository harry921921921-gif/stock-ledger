package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 現金異動列表的一列。
 *
 * @param accountLabel 帳戶名稱；互轉是「轉出 → 轉入」
 * @param description  類型＋說明，例如「存入 · 薪資入帳」「餘額調整（實際餘額 1,200）」
 * @param amount       金額（正數），方向看 direction；作廢的對帳沒有調整金額，為 null
 * @param revised      有修改紀錄（顯示「曾修改」）
 * @param fromTrade    股票交易自動記的（買進、賣出）：只能到股票頁改或作廢交易
 * @param beforeOpening      早於帳戶初始現金餘額日期的借款或股票紀錄：已含在初始餘額，不影響餘額（DR-047）
 */
public record MovementRow(Long id, LocalDate date, String accountLabel, String description, BigDecimal amount,
		Direction direction, boolean revised, boolean voided, boolean fromTrade,
		boolean beforeOpening) {
}
