package com.sideproject.stockledger.entity;

/**
 * 現金事件的類型（spec 4.6、DR-032）。資料庫存英文代碼，畫面顯示 label。
 * <ul>
 * <li>OPENING：初始現金餘額，開始使用系統時帳戶裡本來就有的錢；是起點，不算淨資產變動。</li>
 * <li>DEPOSIT／WITHDRAWAL：存入／支出，錢從系統外進來／離開系統（WITHDRAWAL 在 v1.6 前叫「領出」，代碼不變）。</li>
 * <li>TRANSFER：系統內兩個帳戶互轉，只是左手換右手。</li>
 * <li>RECONCILE：輸入實際餘額；存的是實際餘額，差額（餘額調整）每次由事件推算。</li>
 * <li>LOAN_DISBURSEMENT／LOAN_PRINCIPAL／LOAN_INTEREST：借款撥款（存入帳戶）、還本、利息（從帳戶扣），都有所屬借款。撥款與還本只改變資產形式，不算淨資產變動；利息依應計制另外計算（設計文件 6）。</li>
 * <li>STOCK_BUY／STOCK_SELL：買進股票從交割戶扣、賣出股票的實際入帳金額存入，由股票交易自動記（資金移動，不算淨資產變動）。DIVIDEND：配息的實際入帳金額，記二代健保扣繳額，計入淨資產變動的「配息」。</li>
 * </ul>
 */
public enum CashMovementType {

	OPENING("初始現金餘額"),
	DEPOSIT("存入"),
	WITHDRAWAL("支出"),
	TRANSFER("帳戶互轉"),
	RECONCILE("餘額調整"),
	LOAN_DISBURSEMENT("借款撥款"),
	LOAN_PRINCIPAL("借款還本"),
	LOAN_INTEREST("借款利息"),
	LOAN_FEE("開辦費"),
	STOCK_BUY("買進股票"),
	STOCK_SELL("賣出股票"),
	DIVIDEND("配息");

	private final String label;

	CashMovementType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** 買進、賣出股票：由股票交易自動記，要改請改交易。 */
	public boolean isTrade() {
		return this == STOCK_BUY || this == STOCK_SELL;
	}

	/** 借款的四種類型（含開辦費）：一定有所屬借款，從資金來源頁記，不在「記一筆現金異動」裡。 */
	public boolean isLoan() {
		return this == LOAN_DISBURSEMENT || this == LOAN_PRINCIPAL || this == LOAN_INTEREST || this == LOAN_FEE;
	}

}
