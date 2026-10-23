package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;

/**
 * 一個月內股票部分的淨資產變動（spec 4.3-1）。保留 10 位，呼叫端再進位到元。
 *
 * @param priceChange 股價漲跌（帳面）
 * @param realized    賣出損益（已實現）
 */
public record MonthlyStockChange(BigDecimal priceChange, BigDecimal realized) {
}
