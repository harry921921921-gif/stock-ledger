package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sideproject.stockledger.entity.CashMovementType;

/** 現金帳本：餘額、餘額調整、每月變動都由事件推算（spec 4.3、4.6、DR-032）。純 Java，不需要 Spring。 */
class CashLedgerCalculatorTest {

	private static final Long A = 1L;
	private static final Long B = 2L;
	private static final LocalDate FAR_FUTURE = LocalDate.of(2100, 1, 1);

	private long nextId = 1;

	@Test
	void openingDepositWithdrawal() {
		List<CashEvent> events = List.of(
				opening(A, "2026-10-01", "1000"),
				deposit(A, "2026-10-02", "500"),
				withdrawal(A, "2026-10-03", "200"));

		assertThat(balance(events, A)).isEqualByComparingTo("1300");
	}

	@Test
	void transferMovesBetweenAccounts() {
		List<CashEvent> events = List.of(
				opening(A, "2026-10-01", "1000"),
				transfer(A, B, "2026-10-02", "300"));

		CashLedger ledger = CashLedgerCalculator.compute(events, FAR_FUTURE);
		assertThat(ledger.balances().get(A)).isEqualByComparingTo("700");
		assertThat(ledger.balances().get(B)).isEqualByComparingTo("300");
	}

	@Test
	void reconcileSetsBalanceAndAdjustment() {
		CashEvent reconcile = reconcile(A, "2026-10-10", "900");
		List<CashEvent> events = List.of(opening(A, "2026-10-01", "1000"), reconcile);

		CashLedger ledger = CashLedgerCalculator.compute(events, FAR_FUTURE);
		assertThat(ledger.balances().get(A)).isEqualByComparingTo("900");
		assertThat(ledger.adjustments().get(reconcile.id())).isEqualByComparingTo("-100");
	}

	/** 同一天先算其他異動，再算對帳：對帳輸入的是「那天結束時」的實際餘額。 */
	@Test
	void sameDayReconcileAfterOtherEvents() {
		CashEvent reconcile = reconcile(A, "2026-10-10", "1500");
		List<CashEvent> events = List.of(
				opening(A, "2026-10-01", "1000"),
				reconcile,
				deposit(A, "2026-10-10", "500"));

		CashLedger ledger = CashLedgerCalculator.compute(events, FAR_FUTURE);
		assertThat(ledger.adjustments().get(reconcile.id())).isEqualByComparingTo("0");
		assertThat(ledger.balances().get(A)).isEqualByComparingTo("1500");
	}

	/** 補記一筆比對帳更早的領出：對帳過的餘額仍是當時輸入的實際餘額，只有那筆餘額調整改變（Review Focus #4）。 */
	@Test
	void backdatedMovementKeepsReconciledBalance() {
		CashEvent reconcile = reconcile(A, "2026-10-10", "900");
		List<CashEvent> events = new ArrayList<>(List.of(opening(A, "2026-10-01", "1000"), reconcile));
		events.add(withdrawal(A, "2026-10-05", "50"));

		CashLedger ledger = CashLedgerCalculator.compute(events, FAR_FUTURE);
		assertThat(ledger.balances().get(A)).isEqualByComparingTo("900");
		assertThat(ledger.adjustments().get(reconcile.id())).isEqualByComparingTo("-50");
	}

	@Test
	void eventsAfterAsOfIgnored() {
		List<CashEvent> events = List.of(
				opening(A, "2026-10-01", "1000"),
				deposit(A, "2026-10-20", "500"));

		CashLedger ledger = CashLedgerCalculator.compute(events, LocalDate.of(2026, 10, 19));
		assertThat(ledger.balances().get(A)).isEqualByComparingTo("1000");
	}

	@Test
	void lastReconciliationIsLatestDate() {
		CashEvent later = reconcile(A, "2026-10-15", "800");
		List<CashEvent> events = List.of(
				opening(A, "2026-10-01", "1000"),
				later,
				reconcile(A, "2026-10-01", "1000"));

		CashLedger ledger = CashLedgerCalculator.compute(events, FAR_FUTURE);
		assertThat(ledger.lastReconciliation().get(A)).isEqualTo(later);
	}

	@Test
	void accountNeverReconciledHasNoLastReconciliation() {
		List<CashEvent> events = List.of(opening(B, "2026-10-01", "1000"), reconcile(A, "2026-10-02", "5"));

		assertThat(CashLedgerCalculator.compute(events, FAR_FUTURE).lastReconciliation()).doesNotContainKey(B);
	}

	/** 月底對帳與同一天的存入：存入先算，調整算在這個月。 */
	@Test
	void monthEndReconcileCountsInThatMonth() {
		List<CashEvent> events = List.of(
				opening(A, "2026-10-01", "1000"),
				reconcile(A, "2026-10-31", "1400"),
				deposit(A, "2026-10-31", "500"));

		MonthlyCashChange october = CashLedgerCalculator.monthlyChange(events, YearMonth.of(2026, 10));
		assertThat(october.adjustments()).isEqualByComparingTo("-100");
		assertThat(october.total()).isEqualByComparingTo("400");
	}

	/** 下個月的對帳不算進這個月；這個月的調整要用到上個月的事件。 */
	@Test
	void monthlyAdjustmentsUseFullHistoryButOnlyThatMonth() {
		List<CashEvent> events = List.of(
				opening(A, "2026-09-01", "1000"),
				deposit(A, "2026-09-15", "200"),
				reconcile(A, "2026-10-10", "1100"),
				reconcile(A, "2026-11-05", "50"));

		MonthlyCashChange october = CashLedgerCalculator.monthlyChange(events, YearMonth.of(2026, 10));
		assertThat(october.adjustments()).isEqualByComparingTo("-100");
	}

	@Test
	void adjustmentsOfSeveralAccountsAreSummed() {
		List<CashEvent> events = List.of(
				opening(A, "2026-10-01", "1000"),
				opening(B, "2026-10-01", "500"),
				reconcile(A, "2026-10-10", "900"),
				reconcile(B, "2026-10-10", "530"));

		MonthlyCashChange october = CashLedgerCalculator.monthlyChange(events, YearMonth.of(2026, 10));
		assertThat(october.adjustments()).isEqualByComparingTo("-70");
	}

	@Test
	void accountWithOnlyIncomingTransferHasBalance() {
		List<CashEvent> events = List.of(transfer(A, B, "2026-10-02", "300"));

		CashLedger ledger = CashLedgerCalculator.compute(events, FAR_FUTURE);
		assertThat(ledger.balances().get(A)).isEqualByComparingTo("-300");
		assertThat(ledger.balances().get(B)).isEqualByComparingTo("300");
	}

	/** 初始現金餘額是起點、互轉只是左手換右手：都不算每月變動（spec 4.3-2）。 */
	@Test
	void monthlyChangeExcludesOpeningAndTransfer() {
		List<CashEvent> events = List.of(
				opening(A, "2026-10-01", "1000"),
				deposit(A, "2026-10-02", "500"),
				withdrawal(A, "2026-10-03", "200"),
				transfer(A, B, "2026-10-04", "300"),
				reconcile(A, "2026-10-05", "900"));

		MonthlyCashChange change = CashLedgerCalculator.monthlyChange(events, YearMonth.of(2026, 10));
		assertThat(change.deposits()).isEqualByComparingTo("500");
		assertThat(change.withdrawals()).isEqualByComparingTo("200");
		// 對帳前系統餘額 1000 + 500 − 200 − 300 = 1000，實際 900 → 調整 −100
		assertThat(change.adjustments()).isEqualByComparingTo("-100");
		assertThat(change.total()).isEqualByComparingTo("200");
	}

	/** 生活費：使用者每月直接輸入一筆，減少餘額，在本月淨資產變動裡單獨列一行（DR-034）。 */
	@Test
	void livingExpenseReducesBalanceAndIsListedSeparately() {
		List<CashEvent> events = List.of(
				opening(A, "2026-10-01", "100000"),
				deposit(A, "2026-10-05", "62000"),
				livingExpense(A, "2026-10-31", "27000"),
				withdrawal(A, "2026-10-31", "3000"));

		assertThat(balance(events, A)).isEqualByComparingTo("132000");
		MonthlyCashChange change = CashLedgerCalculator.monthlyChange(events, YearMonth.of(2026, 10));
		assertThat(change.livingExpenses()).isEqualByComparingTo("27000");
		assertThat(change.withdrawals()).isEqualByComparingTo("3000");
		assertThat(change.total()).isEqualByComparingTo("32000");
	}

	@Test
	void monthlyChangeOnlyCountsThatMonth() {
		List<CashEvent> events = List.of(
				opening(A, "2026-09-01", "1000"),
				deposit(A, "2026-09-30", "100"),
				deposit(A, "2026-10-01", "200"),
				deposit(A, "2026-11-01", "400"));

		MonthlyCashChange change = CashLedgerCalculator.monthlyChange(events, YearMonth.of(2026, 10));
		assertThat(change.deposits()).isEqualByComparingTo("200");
		assertThat(change.total()).isEqualByComparingTo("200");
	}

	@Test
	void monthlyChangeOfEmptyMonthIsZero() {
		MonthlyCashChange change = CashLedgerCalculator.monthlyChange(List.of(), YearMonth.of(2026, 10));
		assertThat(change.total()).isEqualByComparingTo("0");
		assertThat(change.adjustments()).isEqualByComparingTo("0");
	}

	private BigDecimal balance(List<CashEvent> events, Long account) {
		return CashLedgerCalculator.compute(events, FAR_FUTURE).balances().get(account);
	}

	private CashEvent opening(Long account, String date, String amount) {
		return event(CashMovementType.OPENING, account, null, date, amount, null);
	}

	private CashEvent deposit(Long account, String date, String amount) {
		return event(CashMovementType.DEPOSIT, account, null, date, amount, null);
	}

	private CashEvent withdrawal(Long account, String date, String amount) {
		return event(CashMovementType.WITHDRAWAL, account, null, date, amount, null);
	}

	private CashEvent livingExpense(Long account, String date, String amount) {
		return event(CashMovementType.LIVING_EXPENSE, account, null, date, amount, null);
	}

	private CashEvent transfer(Long from, Long to, String date, String amount) {
		return event(CashMovementType.TRANSFER, from, to, date, amount, null);
	}

	private CashEvent reconcile(Long account, String date, String actual) {
		return event(CashMovementType.RECONCILE, account, null, date, null, actual);
	}

	private CashEvent event(CashMovementType type, Long account, Long to, String date, String amount, String actual) {
		return new CashEvent(nextId++, type, LocalDate.parse(date), account, to,
				amount == null ? null : new BigDecimal(amount), actual == null ? null : new BigDecimal(actual));
	}

}
