package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sideproject.stockledger.entity.CashMovementType;

/**
 * 現金帳本：從事件推算餘額、餘額調整與每月變動（spec 4.3、4.6、DR-032）。純 Java、不依賴 Spring。
 * 只做加減，不會有除法或進位。
 */
public final class CashLedgerCalculator {

	/**
	 * 依日期排序；同一天先算其他事件、最後才算對帳（對帳輸入的是那天結束時的實際餘額）。
	 * 同一天、同一順位的事件用 id 排，讓結果不受清單順序影響。
	 */
	private static final Comparator<CashEvent> ORDER = Comparator.comparing(CashEvent::date)
		.thenComparing(e -> e.type() == CashMovementType.RECONCILE)
		.thenComparing(CashEvent::id);

	private CashLedgerCalculator() {
	}

	/** 只計日期 ≤ asOf 的事件。 */
	public static CashLedger compute(List<CashEvent> events, LocalDate asOf) {
		Objects.requireNonNull(events, "events");
		Objects.requireNonNull(asOf, "asOf");
		Map<Long, BigDecimal> balances = new HashMap<>();
		Map<Long, BigDecimal> adjustments = new HashMap<>();
		Map<Long, CashEvent> lastReconciliation = new HashMap<>();

		events.stream()
			.filter(e -> !e.date().isAfter(asOf))
			.sorted(ORDER)
			.forEach(e -> {
				switch (e.type()) {
					case OPENING, DEPOSIT -> add(balances, e.accountId(), e.amount());
					case WITHDRAWAL -> add(balances, e.accountId(), e.amount().negate());
					case TRANSFER -> {
						add(balances, e.accountId(), e.amount().negate());
						add(balances, e.toAccountId(), e.amount());
					}
					case RECONCILE -> {
						BigDecimal system = balances.getOrDefault(e.accountId(), BigDecimal.ZERO);
						adjustments.put(e.id(), e.actualBalance().subtract(system));
						balances.put(e.accountId(), e.actualBalance());
						// 已依日期排序，後面的對帳一定比較新
						lastReconciliation.put(e.accountId(), e);
					}
				}
			});
		return new CashLedger(Map.copyOf(balances), Map.copyOf(adjustments), Map.copyOf(lastReconciliation));
	}

	/** 本月存入、支出與餘額調整；初始現金餘額（起點）與帳戶互轉（左手換右手）不算（spec 4.3-2）。 */
	public static MonthlyCashChange monthlyChange(List<CashEvent> events, YearMonth month) {
		Objects.requireNonNull(month, "month");
		Map<Long, BigDecimal> adjustments = compute(events, month.atEndOfMonth()).adjustments();
		BigDecimal deposits = BigDecimal.ZERO;
		BigDecimal withdrawals = BigDecimal.ZERO;
		BigDecimal adjusted = BigDecimal.ZERO;
		for (CashEvent e : events) {
			if (!YearMonth.from(e.date()).equals(month)) {
				continue;
			}
			switch (e.type()) {
				case DEPOSIT -> deposits = deposits.add(e.amount());
				case WITHDRAWAL -> withdrawals = withdrawals.add(e.amount());
				case RECONCILE -> adjusted = adjusted.add(adjustments.get(e.id()));
				case OPENING, TRANSFER -> {
					// 不算變動
				}
			}
		}
		return new MonthlyCashChange(deposits, withdrawals, adjusted, deposits.subtract(withdrawals).add(adjusted));
	}

	private static void add(Map<Long, BigDecimal> balances, Long accountId, BigDecimal delta) {
		balances.merge(accountId, delta, BigDecimal::add);
	}

}
