package com.sideproject.stockledger.entity;

/**
 * 借款怎麼進入系統（設計文件 3）。
 * <ul>
 * <li>NEW：新撥款，起算日記一筆借款撥款到撥入帳戶。</li>
 * <li>EXISTING：開始用系統前就有的借款，起算日只是起點（和初始現金餘額一樣），不記現金異動。</li>
 * </ul>
 */
public enum LoanOrigin {

	NEW("新撥款"),
	EXISTING("既有借款");

	private final String label;

	LoanOrigin(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
