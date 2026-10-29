package com.sideproject.stockledger.dto;

import java.util.List;

/**
 * 引導借款這一步按「儲存」時，Service 檢查完的結果：要存的借款表單、錯誤訊息（空的才能存）、
 * 確認區塊要列的提醒（不擋存檔，例如到期日已經過了）。
 */
public record LoanInput(LoanForm form, List<String> errors, List<String> warnings) {

	public LoanInput {
		errors = List.copyOf(errors);
		warnings = List.copyOf(warnings);
	}

	public boolean ok() {
		return errors.isEmpty();
	}

}
