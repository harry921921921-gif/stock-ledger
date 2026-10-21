package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.CashEvent;
import com.sideproject.stockledger.calculator.CashLedger;
import com.sideproject.stockledger.calculator.CashLedgerCalculator;
import com.sideproject.stockledger.calculator.MonthlyCashChange;
import com.sideproject.stockledger.calculator.ShareCalculator;
import com.sideproject.stockledger.dto.AccountCard;
import com.sideproject.stockledger.dto.AllocationRow;
import com.sideproject.stockledger.dto.Direction;
import com.sideproject.stockledger.dto.MovementRow;
import com.sideproject.stockledger.dto.TotalAssetsView;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;

/**
 * 總資產頁（spec 第 3 節第 2 頁、4.3、4.6）。所有數字在這裡算好，模板只顯示。
 * 這一版只有現金：股票市值固定為 0，等股票功能完成後補上。
 */
@Service
public class TotalAssetsService {

	/** 超過幾天沒對帳要提醒（spec 4.6-7）。 */
	static final int RECONCILE_REMINDER_DAYS = 30;

	private static final int RECENT_COUNT = 10;

	private final CashAccountService accounts;
	private final CashMovementService movements;
	private final MoneyFormatter money;
	private final Clock clock;

	public TotalAssetsService(CashAccountService accounts, CashMovementService movements, MoneyFormatter money,
			Clock clock) {
		this.accounts = accounts;
		this.movements = movements;
		this.money = money;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public TotalAssetsView view() {
		LocalDate today = LocalDate.now(clock);
		List<CashAccount> myAccounts = accounts.listMine();
		List<CashEvent> events = movements.activeEvents();
		CashLedger ledger = CashLedgerCalculator.compute(events, today);

		BigDecimal cash = myAccounts.stream().map(a -> balanceOf(ledger, a.getId())).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		BigDecimal stockValue = BigDecimal.ZERO;
		BigDecimal total = cash.add(stockValue);

		List<AllocationRow> allocation = myAccounts.stream()
			.map(a -> new AllocationRow(a.getName(), balanceOf(ledger, a.getId()), share(balanceOf(ledger, a.getId()), total)))
			.sorted(Comparator.comparing(AllocationRow::amount).reversed().thenComparing(AllocationRow::name))
			.toList();
		AllocationRow largest = allocation.isEmpty() || allocation.get(0).amount().signum() <= 0 ? null
				: allocation.get(0);

		List<AccountCard> cards = myAccounts.stream().map(a -> card(a, ledger, events, today)).toList();
		MonthlyCashChange monthChange = CashLedgerCalculator.monthlyChange(events, YearMonth.from(today));

		List<MovementRow> recent = rows(movements.listAll().stream().filter(m -> !m.isVoided()).limit(RECENT_COUNT)
			.toList(), myAccounts, ledger);

		return new TotalAssetsView(total, stockValue, cash, myAccounts.size(), share(cash, total), monthChange,
				allocation, largest, cards, recent);
	}

	/** 全部異動頁：某個月的所有異動（含作廢的），新的在前。 */
	@Transactional(readOnly = true)
	public List<MovementRow> movements(YearMonth month) {
		LocalDate today = LocalDate.now(clock);
		CashLedger ledger = CashLedgerCalculator.compute(movements.activeEvents(), today);
		List<CashMovement> inMonth = movements.listAll().stream()
			.filter(m -> YearMonth.from(m.getMovementDate()).equals(month))
			.toList();
		return rows(inMonth, accounts.listMine(), ledger);
	}

	/** 某個帳戶今天的系統推算餘額（輸入實際餘額時給使用者對照）。別人的帳戶 → NotFoundException。 */
	@Transactional(readOnly = true)
	public BigDecimal systemBalance(Long accountId) {
		accounts.getMine(accountId);
		return balanceOf(CashLedgerCalculator.compute(movements.activeEvents(), LocalDate.now(clock)), accountId);
	}

	private AccountCard card(CashAccount account, CashLedger ledger, List<CashEvent> events, LocalDate today) {
		BigDecimal balance = balanceOf(ledger, account.getId());
		CashEvent last = ledger.lastReconciliation().get(account.getId());
		LocalDate opening = events.stream()
			.filter(e -> e.type() == CashMovementType.OPENING && e.accountId().equals(account.getId()))
			.map(CashEvent::date)
			.findFirst()
			.orElse(null);
		// 從上次對帳日 → 初始現金餘額日 → 帳戶建立日起算
		LocalDate since = last != null ? last.date() : opening != null ? opening : account.getCreatedAt().toLocalDate();
		boolean overdue = ChronoUnit.DAYS.between(since, today) > RECONCILE_REMINDER_DAYS;
		return new AccountCard(account.getId(), account.getName(), account.getType().label(), balance,
				last == null ? null : last.date(), last == null ? null : ledger.adjustments().get(last.id()), overdue,
				balance.signum() < 0, opening != null);
	}

	private List<MovementRow> rows(List<CashMovement> list, List<CashAccount> myAccounts, CashLedger ledger) {
		Map<Long, String> names = myAccounts.stream()
			.collect(Collectors.toMap(CashAccount::getId, CashAccount::getName));
		Set<Long> revised = movements.revisedIds();
		return list.stream().map(m -> row(m, names, ledger, revised.contains(m.getId()))).toList();
	}

	private MovementRow row(CashMovement m, Map<Long, String> names, CashLedger ledger, boolean revised) {
		Function<Long, String> name = id -> names.getOrDefault(id, "（已刪除的帳戶）");
		String label = m.getType() == CashMovementType.TRANSFER
				? name.apply(m.getAccountId()) + " → " + name.apply(m.getToAccountId())
				: name.apply(m.getAccountId());
		String description = m.getType().label() + (m.getNote() == null ? "" : " · " + m.getNote());
		BigDecimal amount = m.getAmount();
		Direction direction = switch (m.getType()) {
			case DEPOSIT -> Direction.IN;
			case WITHDRAWAL, LIVING_EXPENSE -> Direction.OUT;
			case OPENING, TRANSFER -> Direction.NEUTRAL;
			case RECONCILE -> Direction.NEUTRAL;
		};
		if (m.getType() == CashMovementType.RECONCILE) {
			description = "餘額調整（實際餘額 " + money.plain(m.getActualBalance()) + "）";
			BigDecimal adjustment = m.isVoided() ? null : ledger.adjustments().get(m.getId());
			amount = adjustment == null ? null : adjustment.abs();
			direction = adjustment == null || adjustment.signum() == 0 ? Direction.NEUTRAL
					: adjustment.signum() > 0 ? Direction.IN : Direction.OUT;
		}
		return new MovementRow(m.getId(), m.getMovementDate(), label, description, amount, direction, revised,
				m.isVoided());
	}

	private static BigDecimal balanceOf(CashLedger ledger, Long accountId) {
		return ledger.balances().getOrDefault(accountId, BigDecimal.ZERO);
	}

	private static BigDecimal share(BigDecimal part, BigDecimal total) {
		return ShareCalculator.percent(part, total).orElse(null);
	}

}
