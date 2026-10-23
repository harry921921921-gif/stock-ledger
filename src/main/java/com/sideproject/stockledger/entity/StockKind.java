package com.sideproject.stockledger.entity;

/** 上市商品種類，決定證交稅率（spec 5.1、DR-026）。由代號判斷：00 開頭是 ETF，再以 B 結尾是債券 ETF。 */
public enum StockKind {

	STOCK("股票"),
	ETF("ETF"),
	BOND_ETF("債券 ETF");

	private final String label;

	StockKind(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** 依證交所代號判斷種類（我的判斷：證交所 API 沒有直接給種類）。 */
	public static StockKind fromCode(String code) {
		if (code.startsWith("00")) {
			return code.endsWith("B") ? BOND_ETF : ETF;
		}
		return STOCK;
	}

	/** 這種商品賣出時適用的證交稅率種類。 */
	public RateKind taxRateKind() {
		return switch (this) {
			case STOCK -> RateKind.SECURITIES_TAX_STOCK;
			case ETF -> RateKind.SECURITIES_TAX_ETF;
			case BOND_ETF -> RateKind.SECURITIES_TAX_BOND_ETF;
		};
	}

}
