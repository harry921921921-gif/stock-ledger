package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 銀行每月扣款金額的一個版本（spec 4.8a-4、DR-048）：從生效日起的扣款日都扣這個金額。
 *
 * @param effectiveDate 生效日（含）
 * @param amount        每月扣款金額（整數元）
 */
public record PaymentChange(LocalDate effectiveDate, BigDecimal amount) {

	/** 某個扣款日適用的扣款金額：生效日 ≤ 扣款日的最後一筆（同一天以清單後面的為準）；沒有為 empty。 */
	public static Optional<BigDecimal> amountOn(List<PaymentChange> payments, LocalDate due) {
		BigDecimal result = null;
		LocalDate best = null;
		for (PaymentChange p : payments) {
			if (!p.effectiveDate().isAfter(due) && (best == null || !p.effectiveDate().isBefore(best))) {
				best = p.effectiveDate();
				result = p.amount();
			}
		}
		return Optional.ofNullable(result);
	}

}
