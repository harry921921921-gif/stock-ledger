package com.sideproject.stockledger.dto;

import java.math.BigDecimal;

/** 資金來源頁「借來的錢現在在哪」的一行：「0056 元大高股息」與這筆借款在它裡面的金額（整數元）。 */
public record LoanHoldingLine(String label, BigDecimal amount) {
}
