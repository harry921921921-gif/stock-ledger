package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sideproject.stockledger.calculator.DailyInterestCalculator;
import com.sideproject.stockledger.calculator.Installment;
import com.sideproject.stockledger.calculator.InstallmentCalculator;
import com.sideproject.stockledger.calculator.PrincipalChange;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.LoanRepository;

/**
 * 借款自動記帳（設計文件 4、5、11）：信貸每期扣款日記還本與利息兩筆；質押每月繳息日記一筆利息，還清當天記最後一段。
 * app 啟動時補記，每天 00:10（Asia/Taipei）再檢查一次。同一期只產生一次；作廢的期數不再自動補；不產生未來日期。
 * <p>
 * 做法：先算出「到今天為止應有的期數清單」（{@link Planned}），再和已經記的對照。平常（postDue）已經記的都照實際金額，
 * 只補還沒記的；修改借款後（reconcile，DR-051）只有使用者手改過的照實際金額，其他的依新合約改、作廢或補記。
 * <p>
 * 這是系統排程、沒有登入者：逐一處理每個使用者的借款，只讀寫每筆借款自己的 user_id（DR-036 的例外）。
 */
@Service
public class LoanPostingService {

	private static final Logger log = LoggerFactory.getLogger(LoanPostingService.class);

	private final LoanRepository loans;
	private final CashMovementRepository movements;
	private final LoanFactsReader facts;
	private final CashMovementService cashMovements;
	private final TransactionTemplate transaction;
	private final Clock clock;
	private final boolean postOnStartup;

	public LoanPostingService(LoanRepository loans, CashMovementRepository movements, LoanFactsReader facts,
			CashMovementService cashMovements, TransactionTemplate transaction, Clock clock,
			@Value("${app.loan.post-on-startup:true}") boolean postOnStartup) {
		this.loans = loans;
		this.movements = movements;
		this.facts = facts;
		this.cashMovements = cashMovements;
		this.transaction = transaction;
		this.clock = clock;
		this.postOnStartup = postOnStartup;
	}

	/** 新合約應有的一期：類型、期別、日期、金額。 */
	record Planned(CashMovementType type, YearMonth period, LocalDate date, BigDecimal amount) {
	}

	/** 已記的每期紀錄用類型＋期別配對。 */
	private record Key(CashMovementType type, YearMonth period) {
	}

	/** 啟動時補記；整合測試關掉（app.loan.post-on-startup=false）。 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (postOnStartup) {
			postDue();
		}
	}

	@Scheduled(cron = "0 10 0 * * *", zone = "Asia/Taipei")
	public void daily() {
		postDue();
	}

	/** 所有使用者到期、還沒產生的期數。每筆借款各自一個交易，先鎖住再重新讀取；一筆出錯不影響其他。 */
	public int postDue() {
		LocalDate today = LocalDate.now(clock);
		int created = 0;
		for (Loan loan : loans.findAllForPosting()) {
			try {
				Integer n = transaction.execute(status -> loans.findForUpdate(loan.getId(), loan.getUserId())
					.map(fresh -> postDue(fresh, today))
					.orElse(0));
				created += n == null ? 0 : n;
			}
			catch (RuntimeException ex) {
				log.error("借款 {} 自動記帳失敗，下次執行會再試", loan.getId(), ex);
			}
		}
		if (created > 0) {
			log.info("借款自動記帳：產生 {} 筆", created);
		}
		return created;
	}

	/** 一筆借款到 today 為止到期、還沒產生的期數（新增借款、還本、修改時也會呼叫）。已經記的一律不動。 */
	@Transactional
	public int postDue(Loan loan, LocalDate today) {
		LoanFacts f = facts.read(loan);
		Set<Long> all = f.active().stream().map(CashMovement::getId).collect(Collectors.toSet());
		return apply(loan, f, plan(loan, f, today, all, false), all).created();
	}

	/**
	 * 修改借款後重新對照每期紀錄（DR-051）。由 LoanService.edit 呼叫，借款已經鎖住、確認是登入者的。
	 * 以類型＋期別配對：新合約有、已記也有、沒手改 → 改成新的日期、帳戶、金額；手改過 → 不動；
	 * 新合約沒有、沒手改 → 作廢並讓出期別；新合約有、還沒記 → 補記（使用者作廢的同一期不補，DR-040）。
	 *
	 * @param userEdited 使用者手改過的現金事件 id（系統重算寫的修改紀錄不算）
	 * @return 手改過、而且和新合約算出來不同（或新合約沒有那一期）的紀錄
	 */
	@Transactional
	public List<CashMovement> reconcile(Loan loan, LocalDate today, Set<Long> userEdited) {
		LoanFacts f = facts.read(loan);
		Set<Long> fixed = f.active().stream()
			.map(CashMovement::getId)
			.filter(userEdited::contains)
			.collect(Collectors.toSet());
		return apply(loan, f, plan(loan, f, today, fixed, true), fixed).differs();
	}

	// ---------- 應有的期數 ----------

	/**
	 * 到 today 為止應有的期數。fixed 裡的紀錄照實際金額與日期（攤還表的那一期、質押下一期的計息起點）；
	 * contractValues 為 true 時，fixed 那幾期的金額改放新合約算出來的值，給呼叫端比較。
	 */
	private List<Planned> plan(Loan loan, LoanFacts f, LocalDate today, Set<Long> fixed, boolean contractValues) {
		// 依還款方式（DR-048）：到期一次還本金＋利息平常不記任何利息，到期由使用者按還本時一起記
		return switch (loan.getRepaymentMethod()) {
			case MONTHLY_FIXED -> creditPlan(loan, f, today, fixed, contractValues);
			case INTEREST_ONLY -> pledgePlan(loan, f, today, fixed);
			case BULLET -> List.of();
		};
	}

	/** 信貸：照攤還表，每期扣款日記「借款還本」與「借款利息」；全部提前還清時還清當天記最後一段利息。 */
	private List<Planned> creditPlan(Loan loan, LoanFacts f, LocalDate today, Set<Long> fixed,
			boolean contractValues) {
		Map<YearMonth, InstallmentCalculator.Posted> overrides = overrides(f, fixed);
		List<Installment> schedule = InstallmentCalculator.schedule(creditInput(f, overrides));
		List<Planned> plan = new ArrayList<>();
		for (Installment i : schedule) {
			if (i.dueDate().isAfter(today)) {
				break;
			}
			if (i.period().isBefore(loan.getPostingFrom())) {
				continue;
			}
			plan.add(new Planned(CashMovementType.LOAN_PRINCIPAL, i.period(), i.dueDate(), i.principal()));
			plan.add(new Planned(CashMovementType.LOAN_INTEREST, i.period(), i.dueDate(), i.interest()));
		}
		creditPayoff(loan, f, today, fixed, schedule, plan).ifPresent(plan::add);
		if (!contractValues) {
			return plan;
		}
		// 手改過的那幾期：用「之前各期照實際、這一期照公式」算出新合約的金額，才知道和手改的值差多少
		Map<Key, CashMovement> active = activeByKey(f);
		List<Planned> result = new ArrayList<>();
		for (Planned p : plan) {
			CashMovement m = active.get(new Key(p.type(), p.period()));
			result.add(m == null || !fixed.contains(m.getId()) || !overrides.containsKey(p.period()) ? p
					: new Planned(p.type(), p.period(), p.date(), contractValue(f, overrides, p.period(), p.type())));
		}
		return result;
	}

	/**
	 * 信貸全部提前還清：還清當天按天數記最後一段利息（上一次扣款日到還清日，算頭不算尾、無條件進位），
	 * 期別是原本下一期的月份（使用者決定，2026-10-23）。剩餘本金用應有的每期本金推算。
	 */
	private static Optional<Planned> creditPayoff(Loan loan, LoanFacts f, LocalDate today, Set<Long> fixed,
			List<Installment> schedule, List<Planned> plan) {
		List<PrincipalChange> changes = new ArrayList<>(fixedPrincipal(f, fixed));
		Set<YearMonth> fixedPeriods = f.active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_PRINCIPAL && m.getPeriod() != null
					&& fixed.contains(m.getId()))
			.map(CashMovement::getPeriod)
			.collect(Collectors.toSet());
		for (Planned p : plan) {
			if (p.type() == CashMovementType.LOAN_PRINCIPAL && p.amount().signum() > 0
					&& !fixedPeriods.contains(p.period())) {
				changes.add(new PrincipalChange(p.date(), p.amount().negate()));
			}
		}
		Optional<LocalDate> payoff = LoanFacts.payoffDate(f.startBalance(), changes).filter(d -> !d.isAfter(today));
		if (payoff.isEmpty()) {
			return Optional.empty();
		}
		LocalDate day = payoff.get();
		LocalDate from = schedule.stream()
			.map(Installment::dueDate)
			.filter(d -> !d.isAfter(day))
			.max(LocalDate::compareTo)
			.orElse(loan.getStartDate());
		YearMonth period = YearMonth.from(day);
		if (!period.atDay(Math.min(loan.getPayDay(), period.lengthOfMonth())).isAfter(day)) {
			period = period.plusMonths(1);
		}
		if (period.isBefore(loan.getPostingFrom())) {
			return Optional.empty();
		}
		BigDecimal interest = DailyInterestCalculator.roundUp(
				DailyInterestCalculator.interest(f.startBalance(), changes, f.rates(), from, day));
		return Optional.of(new Planned(CashMovementType.LOAN_INTEREST, period, day, interest));
	}

	/**
	 * 質押：每月繳息日記一筆「借款利息」＝ 上一期繳息日（第一期為起算日）到這次繳息日的利息（算頭不算尾、無條件進位）。
	 * 上一期是自動記的，起點用它應有的繳息日；手改過的（fixed）用它實際的日期。
	 * 還清的那一期改在還清當天記（期別仍是那一期），之後餘額 0、不再繳息。
	 */
	private static List<Planned> pledgePlan(Loan loan, LoanFacts f, LocalDate today, Set<Long> fixed) {
		List<PrincipalChange> changes = fixedPrincipal(f, fixed);
		Optional<LocalDate> payoffDate = LoanFacts.payoffDate(f.startBalance(), changes);
		List<LocalDate> anchors = f.active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_INTEREST
					&& (m.getPeriod() == null || fixed.contains(m.getId())))
			.map(CashMovement::getMovementDate)
			.toList();
		Map<Key, CashMovement> active = activeByKey(f);
		Set<Key> blocked = blocked(f);
		List<Planned> plan = new ArrayList<>();
		LocalDate lastPlanned = null;
		YearMonth period = YearMonth.from(loan.getStartDate());
		// 多看一個月：還清日在當月繳息日之後時，那一期的期別是下個月，但要在還清當天記（未來的日期由下面擋住）
		YearMonth last = YearMonth.from(today).plusMonths(1);
		for (; !period.isAfter(last); period = period.plusMonths(1)) {
			LocalDate due = period.atDay(Math.min(loan.getPayDay(), period.lengthOfMonth()));
			if (!due.isAfter(loan.getStartDate()) || period.isBefore(loan.getPostingFrom())) {
				continue;
			}
			LocalDate from = interestFrom(loan.getStartDate(), anchors, lastPlanned, due);
			Optional<LocalDate> payoff = payoffDate.filter(d -> d.isAfter(from) && !d.isAfter(due));
			LocalDate to = payoff.orElse(due);
			if (to.isAfter(today)) {
				break;
			}
			BigDecimal interest = DailyInterestCalculator.roundUp(
					DailyInterestCalculator.interest(f.startBalance(), changes, f.rates(), from, to));
			if (interest.signum() > 0) {
				plan.add(new Planned(CashMovementType.LOAN_INTEREST, period, to, interest));
				Key key = new Key(CashMovementType.LOAN_INTEREST, period);
				CashMovement existing = active.get(key);
				if ((existing == null || !fixed.contains(existing.getId())) && !blocked.contains(key)) {
					lastPlanned = to;
				}
			}
		}
		return plan;
	}

	/** 某一天以前（不含）最後一次繳息：固定的日期或上一期應有的繳息日；都沒有就是起算日。 */
	private static LocalDate interestFrom(LocalDate start, List<LocalDate> anchors, LocalDate lastPlanned,
			LocalDate due) {
		LocalDate from = start;
		for (LocalDate d : anchors) {
			if (d.isBefore(due) && d.isAfter(from)) {
				from = d;
			}
		}
		if (lastPlanned != null && lastPlanned.isBefore(due) && lastPlanned.isAfter(from)) {
			from = lastPlanned;
		}
		return from;
	}

	/** 不會因為重算而變的本金變動：撥款、使用者自己記的還本（沒有期別），以及 fixed 裡的每期本金。 */
	private static List<PrincipalChange> fixedPrincipal(LoanFacts f, Set<Long> fixed) {
		return f.active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_DISBURSEMENT
					|| (m.getType() == CashMovementType.LOAN_PRINCIPAL
							&& (m.getPeriod() == null || fixed.contains(m.getId()))))
			.map(m -> new PrincipalChange(m.getMovementDate(),
					m.getType() == CashMovementType.LOAN_DISBURSEMENT ? m.getAmount() : m.getAmount().negate()))
			.toList();
	}

	/**
	 * 攤還表裡照實際金額的期數：fixed 的用實際金額；這一項沒有沒作廢的紀錄（使用者作廢、或那一期只記了另一項）是 0；
	 * 有沒作廢、但不在 fixed 的照公式算（null）。fixed 是全部時和 {@link LoanFacts#posted()} 相同。
	 */
	private static Map<YearMonth, InstallmentCalculator.Posted> overrides(LoanFacts f, Set<Long> fixed) {
		Map<YearMonth, List<CashMovement>> byPeriod = f.movements().stream()
			.filter(LoanPostingService::periodic)
			.collect(Collectors.groupingBy(CashMovement::getPeriod));
		Map<YearMonth, InstallmentCalculator.Posted> result = new HashMap<>();
		byPeriod.forEach((period, list) -> {
			BigDecimal principal = override(list, CashMovementType.LOAN_PRINCIPAL, fixed);
			BigDecimal interest = override(list, CashMovementType.LOAN_INTEREST, fixed);
			if (principal != null || interest != null) {
				result.put(period, new InstallmentCalculator.Posted(principal, interest));
			}
		});
		return result;
	}

	private static BigDecimal override(List<CashMovement> list, CashMovementType type, Set<Long> fixed) {
		List<CashMovement> active = list.stream().filter(m -> m.getType() == type && !m.isVoided()).toList();
		if (active.stream().anyMatch(m -> !fixed.contains(m.getId()))) {
			return null;
		}
		return active.stream().map(CashMovement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/** 新合約這一期的金額：之前各期照 overrides、這一期照公式；攤還表到不了這一期為 0。 */
	private static BigDecimal contractValue(LoanFacts f, Map<YearMonth, InstallmentCalculator.Posted> overrides,
			YearMonth period, CashMovementType type) {
		Map<YearMonth, InstallmentCalculator.Posted> earlier = new HashMap<>(overrides);
		earlier.keySet().removeIf(p -> !p.isBefore(period));
		return InstallmentCalculator.schedule(creditInput(f, earlier)).stream()
			.filter(i -> i.period().equals(period))
			.findFirst()
			.map(i -> type == CashMovementType.LOAN_PRINCIPAL ? i.principal() : i.interest())
			.orElse(BigDecimal.ZERO);
	}

	private static InstallmentCalculator.Input creditInput(LoanFacts f,
			Map<YearMonth, InstallmentCalculator.Posted> posted) {
		InstallmentCalculator.Input in = f.installmentInput();
		return new InstallmentCalculator.Input(in.startPrincipal(), in.remainingPeriods(), in.firstPeriod(),
				in.payDay(), in.rates(), in.prepayments(), posted, in.payments());
	}

	// ---------- 對照 ----------

	private record Outcome(int created, List<CashMovement> differs) {
	}

	/**
	 * 把應有的期數套到已記的紀錄。fixed 裡的不動（金額、日期或帳戶和應有的不同就放進 differs）；其他的改成應有的值、
	 * 應有清單裡沒有的作廢；還沒記的補記（使用者作廢過的同一期不補）。金額 0 的期數不記。
	 */
	private Outcome apply(Loan loan, LoanFacts f, List<Planned> plan, Set<Long> fixed) {
		Map<Key, CashMovement> active = activeByKey(f);
		Set<Key> blocked = blocked(f);
		Set<Key> seen = new HashSet<>();
		List<CashMovement> differs = new ArrayList<>();
		LocalDateTime now = LocalDateTime.now(clock);
		int created = 0;
		for (Planned p : plan) {
			Key key = new Key(p.type(), p.period());
			if (p.amount().signum() <= 0 || !seen.add(key)) {
				continue;
			}
			CashMovement m = active.get(key);
			if (m != null && fixed.contains(m.getId())) {
				if (m.getAmount().compareTo(p.amount()) != 0 || !m.getMovementDate().equals(p.date())
						|| !Objects.equals(m.getAccountId(), loan.getPayAccountId())) {
					differs.add(m);
				}
			}
			else if (m != null) {
				cashMovements.followLoan(m, p.date(), loan.getPayAccountId(), p.amount(), LoanService.EDIT_REASON);
			}
			else if (!blocked.contains(key)) {
				movements.saveAndFlush(CashMovement.ofLoan(loan, p.type(), p.date(), loan.getPayAccountId(), p.amount(),
						p.period(), now));
				created++;
			}
		}
		for (Map.Entry<Key, CashMovement> e : active.entrySet()) {
			if (seen.contains(e.getKey())) {
				continue;
			}
			if (fixed.contains(e.getValue().getId())) {
				differs.add(e.getValue());
			}
			else {
				cashMovements.voidForLoanEdit(e.getValue(), LoanService.EDIT_REASON);
			}
		}
		return new Outcome(created, differs);
	}

	/** 沒作廢的每期還本、利息（同一期同一類型最多一筆，資料庫唯一限制）。 */
	private static Map<Key, CashMovement> activeByKey(LoanFacts f) {
		Map<Key, CashMovement> result = new LinkedHashMap<>();
		f.movements().stream()
			.filter(m -> periodic(m) && !m.isVoided())
			.forEach(m -> result.put(new Key(m.getType(), m.getPeriod()), m));
		return result;
	}

	/** 使用者作廢過、而且沒有再記的期數：不再自動補（DR-040）。系統重算作廢的已經讓出期別，不在這裡。 */
	private static Set<Key> blocked(LoanFacts f) {
		Set<Key> active = activeByKey(f).keySet();
		return f.movements().stream()
			.filter(m -> periodic(m) && m.isVoided())
			.map(m -> new Key(m.getType(), m.getPeriod()))
			.filter(k -> !active.contains(k))
			.collect(Collectors.toSet());
	}

	private static boolean periodic(CashMovement m) {
		return m.getPeriod() != null
				&& (m.getType() == CashMovementType.LOAN_PRINCIPAL || m.getType() == CashMovementType.LOAN_INTEREST);
	}

}
