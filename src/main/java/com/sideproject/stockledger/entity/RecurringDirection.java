package com.sideproject.stockledger.entity;

/** 固定項目是收入還是支出；決定自動產生的現金異動是存入還是支出。 */
public enum RecurringDirection {

	INCOME("收入", CashMovementType.DEPOSIT),
	EXPENSE("支出", CashMovementType.WITHDRAWAL);

	private final String label;
	private final CashMovementType movementType;

	RecurringDirection(String label, CashMovementType movementType) {
		this.label = label;
		this.movementType = movementType;
	}

	public String label() {
		return label;
	}

	public CashMovementType movementType() {
		return movementType;
	}

}
