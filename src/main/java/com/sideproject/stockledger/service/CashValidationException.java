package com.sideproject.stockledger.service;

/** 現金異動的輸入不合規則。訊息是給使用者看的中文，Controller 直接顯示在表單上。 */
public class CashValidationException extends IllegalArgumentException {

	public CashValidationException(String message) {
		super(message);
	}

	/** 由資料庫錯誤轉來的（例如連點撞到唯一限制）：保留原因，方便看 log。 */
	public CashValidationException(String message, Throwable cause) {
		super(message, cause);
	}

}
