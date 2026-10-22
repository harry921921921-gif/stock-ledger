package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.format.annotation.DateTimeFormat;

import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;

/**
 * 新增或修改借款的表單（設計文件 3、4、5）。所有規則在 LoanService 檢查。沒有 userId：擁有者一律是登入者。
 *
 * @param originalAmount    原始借款金額（顯示用）；新撥款不填時等於撥款金額
 * @param startPrincipal    新撥款：撥款金額；既有借款：起算日當時的剩餘本金
 * @param annualRatePercent 年利率，畫面填百分比（2.5 代表 2.5%）
 * @param disburseAccountId 撥入帳戶，只有新撥款用
 * @param payAccountId      信貸的扣款帳戶、質押的繳息帳戶
 * @param remainingPeriods  信貸：起算時的剩餘期數（新撥款就是總期數）
 * @param firstPeriod       信貸：第一期扣款月份
 * @param dueDate           質押：到期日（可不填）
 */
public record LoanForm(
		String name,
		LoanType type,
		String bank,
		LoanOrigin origin,
		BigDecimal originalAmount,
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
		BigDecimal startPrincipal,
		BigDecimal annualRatePercent,
		Long disburseAccountId,
		Long payAccountId,
		Integer payDay,
		Integer remainingPeriods,
		@DateTimeFormat(pattern = "yyyy-MM") YearMonth firstPeriod,
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {
}
