package com.sideproject.stockledger.dto;

/** 引導完成頁的數字：帳戶數、借款數（沒作廢的）、有持股的檔數。 */
public record OnboardingSummary(int accounts, int loans, int stocks) {

}
