package com.sideproject.stockledger.entity;

/** v1 的現金帳戶類型（spec 4.6-1）。定存、外幣延到之後版本。資料庫存英文代碼，畫面顯示 label。 */
public enum CashAccountType {

	SETTLEMENT("證券交割戶"),
	BANK_DEMAND("銀行活存");

	private final String label;

	CashAccountType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
