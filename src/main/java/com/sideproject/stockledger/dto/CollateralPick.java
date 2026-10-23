package com.sideproject.stockledger.dto;

/**
 * 新增股票質押時勾選的一檔擔保持股（spec 4.8a-3）。stockId 為 null 或股數為 0／null 的列略過。
 *
 * @param shares 擔保股數：預設全部持股，可以改小，不能超過持股
 */
public record CollateralPick(Long stockId, Long shares) {
}
