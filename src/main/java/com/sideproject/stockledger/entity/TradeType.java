package com.sideproject.stockledger.entity;

/** 股票交易事件的類型（spec 4.2、6 Trade、設計文件 2026-10-23-stocks 4）。融資買進在之後的階段加入。 */
public enum TradeType {

	OPENING("期初持股"),
	BUY("買進"),
	SELL("賣出"),
	STOCK_DIVIDEND("配股");

	private final String label;

	TradeType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
