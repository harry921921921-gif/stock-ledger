package com.sideproject.stockledger.entity;

/**
 * 固定項目的金額方式（設計文件 4.1）。
 * <ul>
 * <li>FIXED：固定金額，時間到自動記帳。</li>
 * <li>MANUAL：每月自己填，系統不自動記帳，等使用者填金額才記（例如生活費）；只能配每月週期。</li>
 * </ul>
 */
public enum AmountMode {

	FIXED("固定金額"),
	MANUAL("每月自己填");

	private final String label;

	AmountMode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
