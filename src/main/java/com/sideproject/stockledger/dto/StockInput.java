package com.sideproject.stockledger.dto;

import java.util.List;

/**
 * 引導股票這一步按「儲存」時，Service 檢查完的結果：要存的交易表單（資金來源已經從張數或金額換算成每筆借款出的錢）、
 * 錯誤訊息（空的才能存）、確認畫面「資金來源」要列的每一行（例如「玉山質押 4 張（294,419 元）」「自己的錢 3 張（220,814 元）」）。
 */
public record StockInput(TradeForm form, List<String> errors, List<String> sourceLines) {

	public StockInput {
		errors = List.copyOf(errors);
		sourceLines = List.copyOf(sourceLines);
	}

	public boolean ok() {
		return errors.isEmpty();
	}

}
