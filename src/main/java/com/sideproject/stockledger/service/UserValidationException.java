package com.sideproject.stockledger.service;

/** 使用者管理的輸入不合規則。訊息是給管理者看的中文，Controller 直接顯示在表單上。 */
public class UserValidationException extends IllegalArgumentException {

	public UserValidationException(String message) {
		super(message);
	}

}
