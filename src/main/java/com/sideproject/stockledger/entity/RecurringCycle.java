package com.sideproject.stockledger.entity;

/** 固定收支的週期（設計文件 4.1、spec 6 RecurringItem）。資料庫存英文代碼，畫面顯示 label。 */
public enum RecurringCycle {

	MONTHLY("每月"),
	SEMIANNUAL("每半年"),
	YEARLY("每年");

	private final String label;

	RecurringCycle(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
