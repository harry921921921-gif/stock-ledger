package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.RepaymentMethod;

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
 * @param dueDate           到期一次還本金＋利息、每月繳息到期還本：到期日
 * @param repaymentMethod   還款方式（DR-048）；null 表示 V11 以前的寫法，照類型對應（信貸每月固定還款、質押每月繳息）
 * @param monthlyPayment    每月固定還款：銀行每月扣款金額（整數元）
 * @param fee               開辦費、手續費（選填，整數元）
 * @param collateral        股票質押：勾選的擔保持股
 * @param warnPercent       股票質押：維持率提醒線（%，選填；沒填用 150）
 * @param callPercent       股票質押：維持率追繳線（%，選填；沒填用 130）
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
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
		RepaymentMethod repaymentMethod,
		BigDecimal monthlyPayment,
		BigDecimal fee,
		List<CollateralPick> collateral,
		BigDecimal warnPercent,
		BigDecimal callPercent) {

	public LoanForm {
		collateral = collateral == null ? List.of() : collateral;
	}

	/** V11 以前的欄位（沒有還款方式、扣款金額、開辦費、擔保股票）。 */
	public LoanForm(String name, LoanType type, String bank, LoanOrigin origin, BigDecimal originalAmount,
			LocalDate startDate, BigDecimal startPrincipal, BigDecimal annualRatePercent, Long disburseAccountId,
			Long payAccountId, Integer payDay, Integer remainingPeriods, YearMonth firstPeriod, LocalDate dueDate) {
		this(name, type, bank, origin, originalAmount, startDate, startPrincipal, annualRatePercent, disburseAccountId,
				payAccountId, payDay, remainingPeriods, firstPeriod, dueDate, null, null, null, List.of(), null, null);
	}

}
