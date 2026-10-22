package com.sideproject.stockledger.entity;

/** 借款類型（spec 6 Loan）。這一版有信貸與質押；融資在股票階段加入。 */
public enum LoanType {

	CREDIT("信貸"),
	PLEDGE("質押");

	private final String label;

	LoanType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
