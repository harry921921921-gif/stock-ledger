package com.sideproject.stockledger.dto;

/**
 * 儲存前確認區塊的一列（設計文件 2026-10-25-onboarding 3.2）：欄位名稱和要顯示的內容。
 * 內容由 Service 轉好（例如還款方式中文、帳戶名稱）；沒填是空字串，畫面顯示「（沒填）」。
 */
public record ConfirmRow(String label, String value) {

	public ConfirmRow {
		value = value == null ? "" : value;
	}

	public boolean isEmpty() {
		return value.isBlank();
	}

}
