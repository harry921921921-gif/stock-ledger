package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「還款／到期處理」的確認畫面（DR-054）：每一行文字與金額都由 LoanService 算好，畫面只顯示。
 *
 * @param rows      確認區塊的每一列（例如「賣出 00646 元大S&P500」→「1,000 股 × 76，手續費 108 元…」）
 * @param remaining 處理後的剩餘本金（整數元）
 * @param paidOff   處理後是否已還清（剩下的擔保股票解除質押）
 * @param warnings  不擋存檔的提醒（例如還清了但這次沒記利息）；沒有就是空清單
 * @param resolved  算好的表單：手續費、證交稅、實際入帳、賣股還款都換成算出來的數字；確認儲存只存這一份
 */
public record SettlePreview(List<ConfirmRow> rows, BigDecimal remaining, boolean paidOff, List<String> warnings,
		SettleForm resolved) {
}
