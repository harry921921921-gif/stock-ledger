package com.sideproject.stockledger.service;

/** 借款的輸入不合規則。訊息是給使用者看的中文，Controller 直接顯示在表單上。 */
public class LoanValidationException extends IllegalArgumentException {

	public LoanValidationException(String message) {
		super(message);
	}

}
