package com.sideproject.stockledger.entity;

/** 使用者角色（spec 4.10）：管理者可以建立帳號、重設密碼。資料庫存英文代碼，畫面顯示 label。 */
public enum Role {

	ADMIN("管理者"),
	USER("一般使用者");

	private final String label;

	Role(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
