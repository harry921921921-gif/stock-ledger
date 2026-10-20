package com.sideproject.stockledger.dto;

import java.time.LocalDate;
import java.util.List;

import com.sideproject.stockledger.entity.RateKind;

/**
 * 費率設定頁的一列。數字由 Service 先格式化好（RATIO 顯示成「0.1425%」，AMOUNT 顯示成「20 元」），
 * 模板只負責顯示，不做計算。history 是所有版本（新的在前）；history 裡的列本身的 history 為空。
 *
 * @param sourceLabel 法定／預設／個人；沒有任何版本時為「—」
 * @param statusLabel 歷史版本的狀態：「目前適用」「尚未生效」或空字串
 * @param note        備註（法定費率寫法規出處）；沒有時為 null
 */
public record RateRow(RateKind kind, String displayValue, LocalDate effectiveDate, String sourceLabel,
		String statusLabel, String note, List<RateRow> history) {
}
