package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 資金來源頁一筆借款的卡片（設計文件 7；股票階段加「錢在哪」、淨賺、擔保股票與維持率）。數字都由 LoanService 算好，模板只顯示。
 *
 * @param ratePercent        目前年利率（%，到 0.01）
 * @param monthInterest      本月利息（應計，整數元）
 * @param paidInterest       累計已付利息（沒作廢的「借款利息」加總）
 * @param undeployed         尚未投入：撥款後還沒分配到買進的金額（由交易推算）
 * @param nextDue            下次扣款或繳息日；已還清為 null
 * @param nextAmount         下次扣款金額（信貸：本金＋利息；質押：預估利息）；已還清為 null
 * @param periodsLeft        信貸：還剩幾期（含下一期）；質押為 null
 * @param deployedIn         借來的錢現在在哪：各持股中這筆借款的金額（整數元）
 * @param netGain            借款淨賺（spec 5.4）＝ 出資部分的帳面損益＋已實現損益＋配息 − 已付利息
 * @param collateral         質押的擔保股票；信貸為空
 * @param maintenancePercent 維持率（%，到 0.01）；沒有擔保股票或已還清為 null
 * @param maintenanceLevel   OK、WARN（低於提醒線）、CALL（低於追繳線）；沒有維持率為 null
 * @param warnPercent        提醒線（%）；信貸為 null
 * @param callPercent        追繳線（%）；信貸為 null
 */
public record LoanCard(Long id, String name, String bank, String typeLabel, String originLabel, boolean credit,
		boolean pledge, BigDecimal originalAmount, BigDecimal remaining, BigDecimal ratePercent,
		BigDecimal monthInterest, BigDecimal paidInterest, BigDecimal undeployed, LocalDate nextDue,
		BigDecimal nextAmount, Integer periodsLeft, String payAccountName, boolean paidOff,
		List<LoanHoldingLine> deployedIn, BigDecimal netGain, List<CollateralLine> collateral,
		BigDecimal maintenancePercent, String maintenanceLevel, BigDecimal warnPercent, BigDecimal callPercent) {
}
