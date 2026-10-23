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
 * @param purchases          買進紀錄：用到這筆借款的期初持股與買進，新的在前
 * @param netGain            借款淨賺（spec 5.4）＝ 出資部分的帳面損益＋已實現損益＋配息 − 已付利息
 * @param investGain         投資賺到 ＝ 這筆借款買的股票：帳面損益＋已實現損益＋分到的配息
 * @param investReturnPercent 投資報酬率 ＝ 投資賺到 ÷ 目前還在股票裡的借款成本（%）；沒有成本為 null
 * @param verdict            BEAT（淨賺 ≥ 0，賺贏利息）、BEHIND（還沒賺贏利息）、NOT_INVESTED（還沒拿去投資）
 * @param collateral         質押的擔保股票；信貸為空
 * @param maintenancePercent 維持率（%，到 0.01）；沒有擔保股票或已還清為 null
 * @param maintenanceLevel   OK、WARN（低於提醒線）、CALL（低於追繳線）；沒有維持率為 null
 * @param warnPercent        提醒線（%）；信貸為 null
 * @param callPercent        追繳線（%）；信貸為 null
 * @param repaymentLabel     還款方式（每月固定還款、到期一次還本金＋利息、每月繳息到期還本）
 * @param monthlyPayment     每月固定還款：目前的銀行每月扣款（舊借款是公式算的月付金）；其他方式為 null
 * @param dueDate            到期日；每月固定還款或沒填為 null
 * @param daysToDue          到期還有幾天（負數＝已經過期）；沒有到期日或已還清為 null
 * @param amountAtDue        到期要還：到期一次還本金＋利息是本金＋利息估計，每月繳息是本金；沒有到期日為 null
 * @param accruedInterest    到期一次還本金＋利息：到今天為止還沒付的應計利息；其他方式為 null
 * @param dueSoon            到期前 30 天內或已過期、還沒還清（要提醒）
 */
public record LoanCard(Long id, String name, String bank, String typeLabel, String originLabel, boolean credit,
		boolean pledge, BigDecimal originalAmount, BigDecimal remaining, BigDecimal ratePercent,
		BigDecimal monthInterest, BigDecimal paidInterest, BigDecimal undeployed, LocalDate nextDue,
		BigDecimal nextAmount, Integer periodsLeft, String payAccountName, boolean paidOff,
		List<LoanHoldingLine> deployedIn, List<LoanPurchaseLine> purchases, BigDecimal netGain, BigDecimal investGain,
		BigDecimal investReturnPercent,
		String verdict, List<CollateralLine> collateral,
		BigDecimal maintenancePercent, String maintenanceLevel, BigDecimal warnPercent, BigDecimal callPercent,
		String repaymentLabel, BigDecimal monthlyPayment, LocalDate dueDate, Integer daysToDue, BigDecimal amountAtDue,
		BigDecimal accruedInterest, boolean dueSoon) {
}
