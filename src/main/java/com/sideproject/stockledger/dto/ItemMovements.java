package com.sideproject.stockledger.dto;

import java.util.List;

/**
 * 全部異動頁依固定項目或借款篩選的結果：產生的所有異動（含作廢的），新的在前。
 *
 * @param backUrl   「回去」連結（固定收支頁或資金來源頁）
 * @param backLabel 連結文字
 */
public record ItemMovements(Long itemId, String itemName, List<MovementRow> rows, String backUrl, String backLabel) {
}
