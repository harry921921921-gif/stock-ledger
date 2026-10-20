package com.sideproject.stockledger.service;

/**
 * 費率輸入不合規則。訊息是給使用者看的中文，Controller 會直接顯示在表單上。
 * 用專屬的例外型別，Controller 才不會把程式其他地方的 IllegalArgumentException（可能是英文技術訊息）也顯示給使用者。
 */
public class RateValidationException extends IllegalArgumentException {

	public RateValidationException(String message) {
		super(message);
	}

}
