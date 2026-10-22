package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 資金來源頁一筆借款的卡片（設計文件 7）。數字都由 LoanService 算好，模板只顯示。
 *
 * @param ratePercent      目前年利率（%，到 0.01）
 * @param monthInterest    本月利息（應計，整數元）
 * @param paidInterest     累計已付利息（沒作廢的「借款利息」加總）
 * @param undeployed       尚未投入：這一版沒有股票，等於剩餘本金
 * @param nextDue          下次扣款或繳息日；已還清為 null
 * @param nextAmount       下次扣款金額（信貸：本金＋利息；質押：預估利息）；已還清為 null
 * @param periodsLeft      信貸：還剩幾期（含下一期）；質押為 null
 */
public record LoanCard(Long id, String name, String bank, String typeLabel, String originLabel, boolean credit,
		boolean pledge, BigDecimal originalAmount, BigDecimal remaining, BigDecimal ratePercent,
		BigDecimal monthInterest, BigDecimal paidInterest, BigDecimal undeployed, LocalDate nextDue,
		BigDecimal nextAmount, Integer periodsLeft, String payAccountName, boolean paidOff) {
}
