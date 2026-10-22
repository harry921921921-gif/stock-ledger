package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.RecurringScheduleCalculator;
import com.sideproject.stockledger.dto.FillForm;
import com.sideproject.stockledger.dto.RecurringItemForm;
import com.sideproject.stockledger.dto.RecurringPageView;
import com.sideproject.stockledger.dto.RecurringRow;
import com.sideproject.stockledger.dto.UnfilledPeriod;
import com.sideproject.stockledger.entity.AmountMode;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.RecurringCycle;
import com.sideproject.stockledger.entity.RecurringDirection;
import com.sideproject.stockledger.entity.RecurringItem;
import com.sideproject.stockledger.repository.CashAccountRepository;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.RecurringItemRepository;
import com.sideproject.stockledger.security.CurrentUser;

/**
 * 固定收支項目（設計文件 4、spec 第 3 節第 5 頁、DR-035）：新增、修改、結束，以及「每月自己填」的項目填金額與未填提醒。
 * 方法參數故意沒有 userId：一律用 {@link CurrentUser#id()}；別人的項目或帳戶在這裡等於「不存在」（404）。
 */
@Service
public class RecurringItemService {

	/** 和現金異動、費率生效日期的下限一致。 */
	private static final YearMonth MIN_MONTH = YearMonth.of(2000, 1);

	/** 和 CashMovementService 一樣：DECIMAL(19,4) 的整數部分最多 15 位，擋在 1 兆以下。 */
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e12");

	private static final int MAX_NAME_LENGTH = 50;

	static final String LOCKED_FIELDS = "要改收入或支出、金額方式、週期或開始月份，請結束這個項目後另建一個";

	static final String ALREADY_FILLED = "這一期已經填過";

	/** V8 migration 裡的唯一限制名稱。 */
	private static final String UNIQUE_PERIOD = "uk_cash_movement_recurring_period";

	private final RecurringItemRepository items;
	private final CashAccountRepository accounts;
	private final CashMovementRepository movements;
	private final RecurringPostingService posting;
	private final CurrentUser currentUser;
	private final Clock clock;

	public RecurringItemService(RecurringItemRepository items, CashAccountRepository accounts,
			CashMovementRepository movements, RecurringPostingService posting, CurrentUser currentUser, Clock clock) {
		this.items = items;
		this.accounts = accounts;
		this.movements = movements;
		this.posting = posting;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	/**
	 * 新增的結果。
	 *
	 * @param backfilled 開始月份在過去時，補記了幾筆已到期的入帳
	 */
	public record Created(RecurringItem item, int backfilled) {
	}

	// ---------- 新增、修改 ----------

	/** 新增項目；開始月份在過去時，立刻補記已到期的期數（設計文件 4.2）。 */
	@Transactional
	public Created create(RecurringItemForm form) {
		Long userId = currentUser.id();
		validate(form.name(), form.direction(), form.amountMode(), form.amount(), form.cycle(), form.anchorMonth(),
				form.dayOfMonth(), form.startMonth(), form.endMonth());
		Long accountId = findMyAccount(form.accountId()).getId();
		RecurringItem item = items.save(RecurringItem.create(userId, form.name(), form.direction(), form.amountMode(),
				form.amount(), form.cycle(), form.anchorMonth(), form.dayOfMonth(), accountId, form.startMonth(),
				form.endMonth(), LocalDateTime.now(clock)));
		return new Created(item, posting.postDue(item, today()));
	}

	/**
	 * 修改名稱、金額、入帳日、月份、帳戶、結束月份。只影響還沒產生的期數，已經記好的異動不變；
	 * 這是設定不是交易事件，直接更新、不寫修改紀錄。收入或支出、金額方式、週期、開始月份不能改。
	 */
	@Transactional
	public RecurringItem edit(Long id, RecurringItemForm form) {
		RecurringItem item = findForUpdate(id);
		if (changed(form.direction(), item.getDirection()) || changed(form.amountMode(), item.getAmountMode())
				|| changed(form.cycle(), item.getCycle()) || changed(form.startMonth(), item.getStartMonth())) {
			throw new RecurringValidationException(LOCKED_FIELDS);
		}
		validate(form.name(), item.getDirection(), item.getAmountMode(), form.amount(), item.getCycle(),
				form.anchorMonth(), form.dayOfMonth(), item.getStartMonth(), form.endMonth());
		Long accountId = findMyAccount(form.accountId()).getId();
		item.change(form.name(), form.amount(), form.anchorMonth(), form.dayOfMonth(), accountId, form.endMonth(),
				YearMonth.from(today()));
		// 例如入帳日從 25 號改成 5 號，本月那一期現在就到期了
		posting.postDue(item, today());
		return item;
	}

	// ---------- 每月自己填（設計文件 4.3） ----------

	/**
	 * 填某一期的金額，記成一筆存入或支出。日期 = min(那一期的入帳日, 今天)：過去的期數算在那個月，
	 * 本月提早填也不會產生未來日期。填錯了和一般異動一樣修改或作廢；作廢後那一期可以重填。
	 */
	@Transactional
	public CashMovement fill(Long itemId, FillForm form) {
		RecurringItem item = findForUpdate(itemId);
		if (item.getAmountMode() != AmountMode.MANUAL) {
			throw new RecurringValidationException("這個項目是固定金額，會自動記帳");
		}
		if (form.period() == null) {
			throw new RecurringValidationException("請選擇月份");
		}
		checkAmount(form.amount());
		LocalDate today = today();
		if (filledPeriods(item).contains(form.period())) {
			throw new RecurringValidationException(ALREADY_FILLED);
		}
		UnfilledPeriod period = unfilledOf(item, today).stream()
			.filter(p -> p.period().equals(form.period()))
			.findFirst()
			.orElseThrow(() -> new RecurringValidationException("這一期不用填"));
		LocalDate date = period.dueDate().isAfter(today) ? today : period.dueDate();
		try {
			return movements.saveAndFlush(
					CashMovement.ofRecurring(item, date, form.amount(), form.period(), LocalDateTime.now(clock)));
		}
		catch (DataIntegrityViolationException ex) {
			// 連點兩次：兩個請求同時通過上面的檢查，由資料庫唯一限制擋下第二筆
			String message = ex.getMostSpecificCause().getMessage();
			if (message != null && message.endsWith("for key 'cash_movement." + UNIQUE_PERIOD + "'")) {
				throw new RecurringValidationException(ALREADY_FILLED, ex);
			}
			throw ex;
		}
	}

	/** 所有「每月自己填」的項目還沒填的期數（從開始月份到本月），舊的在前；固定收支頁用。 */
	@Transactional(readOnly = true)
	public List<UnfilledPeriod> unfilled() {
		LocalDate today = today();
		return listMine().stream()
			.filter(i -> i.getAmountMode() == AmountMode.MANUAL)
			.flatMap(i -> unfilledOf(i, today).stream())
			.sorted(Comparator.comparing(UnfilledPeriod::period).thenComparing(UnfilledPeriod::itemName))
			.toList();
	}

	/** 入帳日已經到了、還沒填的期數；總資產頁的提醒（以前月份漏填的也一直提醒，直到補填）。 */
	@Transactional(readOnly = true)
	public List<UnfilledPeriod> overdueReminders() {
		return unfilled().stream().filter(UnfilledPeriod::overdue).toList();
	}

	/** 這個項目從開始月份到本月、還沒有「沒作廢的異動」的期數；略過入帳日早於帳戶初始現金餘額的期數。 */
	private List<UnfilledPeriod> unfilledOf(RecurringItem item, LocalDate today) {
		Set<YearMonth> filled = filledPeriods(item);
		Optional<LocalDate> opening = movements
			.findByUserIdAndAccountIdAndTypeAndVoidedFalse(item.getUserId(), item.getAccountId(), CashMovementType.OPENING)
			.map(CashMovement::getMovementDate);
		return RecurringScheduleCalculator.periodsThrough(item.rule(), YearMonth.from(today)).stream()
			.filter(p -> !p.period().isBefore(item.getPostingFrom()))
			.filter(p -> !filled.contains(p.period()))
			.filter(p -> opening.isEmpty() || !p.dueDate().isBefore(opening.get()))
			.map(p -> new UnfilledPeriod(item.getId(), item.getName(), p.period(), p.dueDate(),
					!p.dueDate().isAfter(today)))
			.toList();
	}

	private Set<YearMonth> filledPeriods(RecurringItem item) {
		return movements.findByUserIdAndRecurringItemIdAndVoidedFalse(item.getUserId(), item.getId()).stream()
			.map(CashMovement::getPeriod)
			.collect(Collectors.toSet());
	}

	// ---------- 查詢 ----------

	/** 目前登入者的一個項目；別人的 → 404。 */
	@Transactional(readOnly = true)
	public RecurringItem find(Long id) {
		return items.findByIdAndUserId(id, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這個固定項目"));
	}

	/** 修改與填金額前讀取並鎖住這一列（SELECT … FOR UPDATE），和自動記帳排程依序執行。 */
	private RecurringItem findForUpdate(Long id) {
		return items.findForUpdate(id, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這個固定項目"));
	}

	@Transactional(readOnly = true)
	public List<RecurringItem> listMine() {
		return items.findByUserIdOrderByNameAscIdAsc(currentUser.id());
	}

	/**
	 * 固定收支頁（spec 第 3 節第 5 頁、設計文件 4.4）。卡片只算本月有效（已開始、還沒結束）的項目；
	 * 月平均合計是各項精確值相加後才取整數，避免每項先四捨五入造成誤差。
	 */
	@Transactional(readOnly = true)
	public RecurringPageView pageView() {
		LocalDate today = today();
		YearMonth thisMonth = YearMonth.from(today);
		List<RecurringItem> mine = listMine();
		Map<Long, String> accountNames = accounts.findByUserIdOrderByIdAsc(currentUser.id()).stream()
			.collect(Collectors.toMap(CashAccount::getId, CashAccount::getName));

		BigDecimal income = BigDecimal.ZERO;
		BigDecimal expense = BigDecimal.ZERO;
		int incomeCount = 0;
		int expenseCount = 0;
		BigDecimal manualFilled = BigDecimal.ZERO;
		int manualCount = 0;
		List<String> manualUnfilled = new ArrayList<>();
		for (RecurringItem item : mine) {
			if (!activeIn(item, thisMonth)) {
				continue;
			}
			if (item.getAmountMode() == AmountMode.FIXED) {
				BigDecimal average = RecurringScheduleCalculator.monthlyAverage(item.getCycle(), item.getAmount());
				if (item.getDirection() == RecurringDirection.INCOME) {
					income = income.add(average);
					incomeCount++;
				}
				else {
					expense = expense.add(average);
					expenseCount++;
				}
				continue;
			}
			manualCount++;
			List<CashMovement> filled = movements
				.findByUserIdAndRecurringItemIdAndVoidedFalse(item.getUserId(), item.getId()).stream()
				.filter(m -> thisMonth.equals(m.getPeriod()))
				.toList();
			if (filled.isEmpty()) {
				manualUnfilled.add(item.getName());
			}
			for (CashMovement m : filled) {
				manualFilled = manualFilled.add(m.getAmount());
			}
		}

		List<RecurringRow> rows = mine.stream().map(i -> row(i, accountNames, today)).toList();
		Map<Long, RecurringItem> byId = mine.stream().collect(Collectors.toMap(RecurringItem::getId, i -> i));
		Predicate<RecurringRow> ended = r -> r.endMonth() != null && r.endMonth().isBefore(thisMonth);
		Predicate<RecurringRow> isIncome = r -> byId.get(r.id()).getDirection() == RecurringDirection.INCOME;
		Comparator<RecurringRow> byAverage = Comparator
			.comparing(RecurringRow::monthlyAverage, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(RecurringRow::name);
		return new RecurringPageView(whole(income), whole(expense), incomeCount, expenseCount, manualFilled,
				manualCount, manualUnfilled, unfilled(),
				rows.stream().filter(ended.negate()).filter(isIncome).sorted(byAverage).toList(),
				rows.stream().filter(ended.negate()).filter(isIncome.negate()).sorted(byAverage).toList(),
				rows.stream().filter(ended).toList());
	}

	private static boolean activeIn(RecurringItem item, YearMonth month) {
		return !item.getStartMonth().isAfter(month) && (item.getEndMonth() == null || !item.getEndMonth().isBefore(month));
	}

	private static RecurringRow row(RecurringItem item, Map<Long, String> accountNames, LocalDate today) {
		boolean manual = item.getAmountMode() == AmountMode.MANUAL;
		BigDecimal amount = manual ? null : whole(item.getAmount());
		BigDecimal average = manual ? null
				: whole(RecurringScheduleCalculator.monthlyAverage(item.getCycle(), item.getAmount()));
		LocalDate next = RecurringScheduleCalculator.nextDueAfter(item.rule(), today).orElse(null);
		return new RecurringRow(item.getId(), item.getName(), amount, manual, scheduleLabel(item), average, next,
				accountNames.getOrDefault(item.getAccountId(), "—"), item.getStartMonth(),
				item.getEndMonth());
	}

	/** 例如「每月 5 號」「每年 3 月 15 號」「每半年 1、7 月 10 號」。 */
	static String scheduleLabel(RecurringItem item) {
		int day = item.getDayOfMonth();
		return switch (item.getCycle()) {
			case MONTHLY -> "每月 " + day + " 號";
			case YEARLY -> "每年 " + item.getAnchorMonth() + " 月 " + day + " 號";
			case SEMIANNUAL -> {
				int first = (item.getAnchorMonth() - 1) % 6 + 1;
				yield "每半年 " + first + "、" + (first + 6) + " 月 " + day + " 號";
			}
		};
	}

	/** 顯示用的整數元（HALF_UP）。 */
	private static BigDecimal whole(BigDecimal value) {
		return value.setScale(0, RoundingMode.HALF_UP);
	}

	/** 新增表單的預設值：支出、固定金額、每月、開始月份是本月。 */
	public RecurringItemForm newForm() {
		return new RecurringItemForm(null, RecurringDirection.EXPENSE, AmountMode.FIXED, null, RecurringCycle.MONTHLY,
				null, null, null, YearMonth.from(today()), null);
	}

	/** 修改表單的預設值（金額轉成整數元，不顯示資料庫的 8000.0000）。 */
	@Transactional(readOnly = true)
	public RecurringItemForm form(Long id) {
		RecurringItem item = find(id);
		BigDecimal amount = item.getAmount() == null ? null : item.getAmount().setScale(0, RoundingMode.UNNECESSARY);
		return new RecurringItemForm(item.getName(), item.getDirection(), item.getAmountMode(), amount, item.getCycle(),
				item.getAnchorMonth(), item.getDayOfMonth(), item.getAccountId(), item.getStartMonth(),
				item.getEndMonth());
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	// ---------- 檢查 ----------

	/** 表單把不能改的欄位也送回來（hidden）；沒送（例如之後的 REST API）就當作沒改。 */
	private static boolean changed(Object submitted, Object current) {
		return submitted != null && !Objects.equals(submitted, current);
	}

	private static void validate(String name, RecurringDirection direction, AmountMode mode, BigDecimal amount,
			RecurringCycle cycle, Integer anchorMonth, Integer dayOfMonth, YearMonth startMonth, YearMonth endMonth) {
		String cleanName = CashAccount.normalizeName(name);
		if (cleanName == null || cleanName.isEmpty()) {
			throw new RecurringValidationException("請輸入名稱");
		}
		if (cleanName.length() > MAX_NAME_LENGTH) {
			throw new RecurringValidationException("名稱最多 50 個字");
		}
		if (direction == null) {
			throw new RecurringValidationException("請選擇收入或支出");
		}
		if (mode == null) {
			throw new RecurringValidationException("請選擇金額方式");
		}
		if (cycle == null) {
			throw new RecurringValidationException("請選擇週期");
		}
		if (mode == AmountMode.FIXED) {
			checkAmount(amount);
		}
		else {
			if (amount != null) {
				throw new RecurringValidationException("每月自己填的項目不用填金額");
			}
			if (cycle != RecurringCycle.MONTHLY) {
				throw new RecurringValidationException("每月自己填只能選每月");
			}
		}
		if (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31) {
			throw new RecurringValidationException("入帳日要在 1～31 號");
		}
		if (cycle == RecurringCycle.MONTHLY) {
			if (anchorMonth != null) {
				throw new RecurringValidationException("每月的項目不用選月份");
			}
		}
		else if (anchorMonth == null || anchorMonth < 1 || anchorMonth > 12) {
			throw new RecurringValidationException("請選擇月份");
		}
		if (startMonth == null) {
			throw new RecurringValidationException("請選擇開始月份");
		}
		if (startMonth.isBefore(MIN_MONTH)) {
			throw new RecurringValidationException("開始月份要在 2000-01 之後");
		}
		if (endMonth != null && endMonth.isBefore(startMonth)) {
			throw new RecurringValidationException("結束月份不能早於開始月份");
		}
	}

	/** 新台幣只收整數元；也先擋掉過大的數字。 */
	static void checkAmount(BigDecimal amount) {
		if (amount == null) {
			throw new RecurringValidationException("請輸入金額");
		}
		if (amount.abs().compareTo(MAX_AMOUNT) >= 0) {
			throw new RecurringValidationException("金額太大");
		}
		if (amount.stripTrailingZeros().scale() > 0) {
			throw new RecurringValidationException("金額只能是整數元");
		}
		if (amount.signum() <= 0) {
			throw new RecurringValidationException("金額要大於 0");
		}
	}

	private CashAccount findMyAccount(Long accountId) {
		if (accountId == null) {
			throw new RecurringValidationException("請選擇帳戶");
		}
		return accounts.findByIdAndUserId(accountId, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這個帳戶"));
	}

}
