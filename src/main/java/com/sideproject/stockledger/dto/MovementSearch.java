package com.sideproject.stockledger.dto;

import java.util.List;

/**
 * 全部異動頁的備註搜尋結果（spec 4.6-9）。
 *
 * @param query 整理過的搜尋字（去掉前後空白），畫面顯示用
 * @param rows  符合的異動，新的在前；有錯誤時是空清單
 * @param error 搜尋字不合規則時的訊息（例如太長）；沒有錯誤時為 null
 */
public record MovementSearch(String query, List<MovementRow> rows, String error) {
}
