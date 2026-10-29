package com.sideproject.stockledger.dto;

import java.util.List;

/**
 * 引導帳戶這一步按「儲存」時，Service 檢查完的結果：要存的帳戶、要存的初始現金餘額（null 表示沒填餘額、不設定），
 * 和要顯示的錯誤訊息（空的才能存）。
 */
public record AccountInput(CashAccountForm form, OpeningForm opening, List<String> errors) {

	public AccountInput {
		errors = List.copyOf(errors);
	}

	public boolean ok() {
		return errors.isEmpty();
	}

}
