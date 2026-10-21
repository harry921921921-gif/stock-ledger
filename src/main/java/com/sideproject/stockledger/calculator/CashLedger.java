package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.util.Map;

/**
 * {@link CashLedgerCalculator#compute} 的結果。
 *
 * @param balances           各帳戶餘額（以帳戶 id 為 key）；沒有任何事件的帳戶不會出現
 * @param adjustments        各筆對帳的餘額調整（以對帳事件 id 為 key）＝ 實際餘額 − 對帳前的系統餘額
 * @param lastReconciliation 各帳戶最近一次對帳（以帳戶 id 為 key）；從沒對帳的帳戶不會出現
 */
public record CashLedger(Map<Long, BigDecimal> balances, Map<Long, BigDecimal> adjustments,
		Map<Long, CashEvent> lastReconciliation) {
}
