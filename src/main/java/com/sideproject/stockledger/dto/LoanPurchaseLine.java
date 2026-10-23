package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 資金來源頁「買進紀錄」的一列：一筆用到這筆借款的期初持股或買進。
 *
 * @param price      成交價；期初持股是含手續費的平均成本
 * @param loanAmount 這筆交易裡這筆借款出的錢（整數元）
 */
public record LoanPurchaseLine(Long tradeId, LocalDate date, String code, String label, long shares, BigDecimal price,
		BigDecimal loanAmount) {
}
