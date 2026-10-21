package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.sideproject.stockledger.entity.CashMovementType;

/**
 * 交給 {@link CashLedgerCalculator} 計算的一筆現金事件。呼叫端只傳「沒作廢」的事件。
 * RECONCILE 用 actualBalance（amount 為 null）；其他類型用 amount（一律正數，方向由類型決定）。
 * toAccountId 只有 TRANSFER 才有。
 */
public record CashEvent(Long id, CashMovementType type, LocalDate date, Long accountId, Long toAccountId,
		BigDecimal amount, BigDecimal actualBalance) {

	public CashEvent {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(date, "date");
		Objects.requireNonNull(accountId, "accountId");
		if (type == CashMovementType.RECONCILE) {
			Objects.requireNonNull(actualBalance, "對帳一定要有實際餘額");
		}
		else {
			Objects.requireNonNull(amount, "金額不能是 null");
		}
		if (type == CashMovementType.TRANSFER) {
			Objects.requireNonNull(toAccountId, "互轉一定要有轉入帳戶");
		}
	}

}
