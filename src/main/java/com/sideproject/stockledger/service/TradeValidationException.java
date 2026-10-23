package com.sideproject.stockledger.service;

/** 股票交易的輸入不合規則。訊息是給使用者看的中文，Controller 直接顯示在表單上。 */
public class TradeValidationException extends IllegalArgumentException {

	public TradeValidationException(String message) {
		super(message);
	}

}
