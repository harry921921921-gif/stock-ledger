package com.sideproject.stockledger.dto;

import java.util.List;

/** 全部異動頁依固定項目篩選的結果：這個項目產生的所有異動（含作廢的），新的在前。 */
public record ItemMovements(Long itemId, String itemName, List<MovementRow> rows) {
}
