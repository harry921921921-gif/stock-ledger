package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.CashEvent;
import com.sideproject.stockledger.dto.CashMovementForm;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.ReconcileForm;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementRevision;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.repository.CashAccountRepository;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.CashMovementRevisionRepository;
import com.sideproject.stockledger.security.CurrentUser;

/**
 * 現金異動：存入、支出、帳戶互轉、初始現金餘額、輸入實際餘額，以及修改與作廢（spec 4.6、4.9、DR-032、DR-033）。
 * 方法參數故意沒有 userId：一律用 {@link CurrentUser#id()}；別人的帳戶或異動在這裡等於「不存在」（404）。
 */
@Service
public class CashMovementService {

	/** 系統不會有 2000 年以前的資料（和費率生效日期的下限一致）。 */
	private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

	/** DECIMAL(19,4) 的整數部分最多 15 位；擋在 1 兆以下，家用綽綽有餘。 */
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e12");

	private static final int MAX_REASON_LENGTH = 200;

	private static final int MAX_NOTE_LENGTH = 100;

	/** V5 migration 裡的唯一限制名稱。 */
	private static final String UNIQUE_OPENING = "uk_cash_movement_opening";
	private static final String UNIQUE_RECONCILE_DAY = "uk_cash_movement_reconcile_day";

	static final String OPENING_EXISTS = "這個帳戶已經有初始現金餘額";
	static final String RECONCILE_EXISTS = "這個帳戶這一天已經輸入過實際餘額";

	private final CashMovementRepository movements;
	private final CashMovementRevisionRepository revisions;
	private final CashAccountRepository accounts;
	private final CurrentUser currentUser;
	private final Clock clock;

	public CashMovementService(CashMovementRepository movements, CashMovementRevisionRepository revisions,
			CashAccountRepository accounts, CurrentUser currentUser, Clock clock) {
		this.movements = movements;
		this.revisions = revisions;
		this.accounts = accounts;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	// ---------- 新增 ----------

	/** 記一筆存入、支出或帳戶互轉。 */
	@Transactional
	public CashMovement record(CashMovementForm form) {
		Long userId = currentUser.id();
		if (!isAmountMovement(form.type())) {
			throw new CashValidationException("請選擇存入、支出或帳戶互轉");
		}
		Accounts resolved = validateAmountMovement(userId, form.type(), form);
		return save(CashMovement.ofAmount(userId, form.type(), form.date(), resolved.from(), resolved.to(),
				form.amount(), form.note(), LocalDateTime.now(clock)));
	}

	/** 設定初始現金餘額：每個帳戶最多一筆，不算存入（spec 4.6-3）。 */
	@Transactional
	public CashMovement setOpening(Long accountId, OpeningForm form) {
		Long userId = currentUser.id();
		CashAccount account = findMyAccount(accountId);
		// 先檢查「已經有初始現金餘額」：否則既有的初始現金餘額會被當成「更早的異動」，顯示錯的訊息
		if (movements.findByUserIdAndAccountIdAndTypeAndVoidedFalse(userId, account.getId(), CashMovementType.OPENING)
			.isPresent()) {
			throw new CashValidationException(OPENING_EXISTS);
		}
		validateOpening(userId, account.getId(), form, null);
		return save(CashMovement.ofAmount(userId, CashMovementType.OPENING, form.date(), account.getId(), null,
				form.amount(), null, LocalDateTime.now(clock)));
	}

	/** 輸入實際餘額：存實際餘額，差額（餘額調整）由事件推算；同一帳戶同一天最多一筆（spec 4.6-4）。 */
	@Transactional
	public CashMovement reconcile(Long accountId, ReconcileForm form) {
		Long userId = currentUser.id();
		CashAccount account = findMyAccount(accountId);
		validateReconcile(userId, account.getId(), form, null);
		return save(CashMovement.reconcile(userId, form.date(), account.getId(), form.actualBalance(),
				LocalDateTime.now(clock)));
	}

	// ---------- 修改與作廢（spec 4.9） ----------

	/** 修改存入、支出或互轉。類型不能改；每個改到的欄位寫一列修改紀錄，沒有改變就什麼都不做。 */
	@Transactional
	public CashMovement edit(Long id, CashMovementForm form, String reason) {
		Long userId = currentUser.id();
		CashMovement m = findMineForEdit(id);
		if (!isAmountMovement(m.getType())) {
			throw new CashValidationException("這筆不是存入、支出或互轉，請用對應的修改畫面");
		}
		if (form.type() != null && form.type() != m.getType()) {
			throw new CashValidationException("類型不能修改；要改類型請作廢後重新記一筆");
		}
		Accounts resolved = validateAmountMovement(userId, m.getType(), form);
		String cleanReason = cleanReason(reason);
		Map<String, String> before = snapshot(m);
		m.change(form.date(), resolved.from(), resolved.to(), form.amount(), form.note());
		writeEditRevisions(m, before, cleanReason);
		return flush(m);
	}

	/** 修改初始現金餘額的金額或日期。 */
	@Transactional
	public CashMovement editOpening(Long id, OpeningForm form, String reason) {
		Long userId = currentUser.id();
		CashMovement m = findMineForEdit(id);
		if (m.getType() != CashMovementType.OPENING) {
			throw new CashValidationException("這筆不是初始現金餘額");
		}
		validateOpening(userId, m.getAccountId(), form, m.getId());
		String cleanReason = cleanReason(reason);
		Map<String, String> before = snapshot(m);
		m.change(form.date(), m.getAccountId(), null, form.amount(), m.getNote());
		writeEditRevisions(m, before, cleanReason);
		return flush(m);
	}

	/** 修改對帳：改的是當時輸入的實際餘額與日期。 */
	@Transactional
	public CashMovement editReconcile(Long id, ReconcileForm form, String reason) {
		Long userId = currentUser.id();
		CashMovement m = findMineForEdit(id);
		if (m.getType() != CashMovementType.RECONCILE) {
			throw new CashValidationException("這筆不是輸入實際餘額");
		}
		validateReconcile(userId, m.getAccountId(), form, m.getId());
		String cleanReason = cleanReason(reason);
		Map<String, String> before = snapshot(m);
		m.changeReconcile(form.date(), form.actualBalance());
		writeEditRevisions(m, before, cleanReason);
		return flush(m);
	}

	/** 作廢：不硬刪除，寫一列修改紀錄（field = voided），之後不計入任何金額。 */
	@Transactional
	public CashMovement voidMovement(Long id, String reason) {
		CashMovement m = findMineForEdit(id);
		String cleanReason = cleanReason(reason);
		revisions.save(CashMovementRevision.of(m.getId(), currentUser.id(), LocalDateTime.now(clock),
				CashMovementRevision.VOID, "voided", "false", "true", cleanReason));
		m.markVoided();
		return flush(m);
	}

	// ---------- 查詢 ----------

	/** 目前登入者的一筆異動（含作廢的），修改畫面用。 */
	@Transactional(readOnly = true)
	public CashMovement find(Long id) {
		return movements.findByIdAndUserId(id, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這筆異動"));
	}

	/** 目前登入者所有沒作廢的事件，交給 CashLedgerCalculator 計算。 */
	@Transactional(readOnly = true)
	public List<CashEvent> activeEvents() {
		return movements.findByUserIdAndVoidedFalse(currentUser.id()).stream().map(CashMovement::toEvent).toList();
	}

	/** 目前登入者所有異動（含作廢的），新的在前；列表用。 */
	@Transactional(readOnly = true)
	public List<CashMovement> listAll() {
		return movements.findByUserIdOrderByMovementDateDescIdDesc(currentUser.id());
	}

	/**
	 * 目前登入者備註包含 text 的異動（不分大小寫，含作廢的），新的在前；text 由呼叫端整理好。
	 * %、_ 是 LIKE 的萬用字元，先跳脫成一般文字。
	 */
	@Transactional(readOnly = true)
	public List<CashMovement> searchByNote(String text) {
		String escaped = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
		return movements.searchNote(currentUser.id(), "%" + escaped + "%");
	}

	/** 某個帳戶的初始現金餘額日期；沒有初始現金餘額時為 empty。 */
	@Transactional(readOnly = true)
	public Optional<LocalDate> openingDate(Long accountId) {
		return movements.findByUserIdAndAccountIdAndTypeAndVoidedFalse(currentUser.id(), accountId,
				CashMovementType.OPENING).map(CashMovement::getMovementDate);
	}

	/** 今天（Asia/Taipei），表單日期的預設值與「本月」的基準。 */
	public LocalDate today() {
		return LocalDate.now(clock);
	}

	/** 「記一筆現金異動」表單的預設值：存入、今天。 */
	public CashMovementForm newForm() {
		return new CashMovementForm(CashMovementType.DEPOSIT, today(), null, null, null, null);
	}

	/**
	 * 修改存入、支出、互轉的表單預設值（金額轉成整數元，不顯示資料庫的 62000.0000）。
	 * 存入的帳戶放在「轉入帳戶」，支出放在「轉出帳戶」，互轉兩個都有。
	 */
	@Transactional(readOnly = true)
	public CashMovementForm movementForm(Long id) {
		CashMovement m = find(id);
		boolean deposit = m.getType() == CashMovementType.DEPOSIT;
		return new CashMovementForm(m.getType(), m.getMovementDate(), deposit ? null : m.getAccountId(),
				deposit ? m.getAccountId() : m.getToAccountId(), wholeDollars(m.getAmount()), m.getNote());
	}

	/** 修改初始現金餘額的表單預設值。 */
	@Transactional(readOnly = true)
	public OpeningForm openingForm(Long id) {
		CashMovement m = find(id);
		return new OpeningForm(m.getMovementDate(), wholeDollars(m.getAmount()));
	}

	/** 修改實際餘額的表單預設值。 */
	@Transactional(readOnly = true)
	public ReconcileForm reconcileForm(Long id) {
		CashMovement m = find(id);
		return new ReconcileForm(m.getMovementDate(), wholeDollars(m.getActualBalance()));
	}

	/** 資料庫是 DECIMAL(19,4)，但金額一定是整數元（Service 與資料庫都有檢查），所以去掉小數位不會有誤差。 */
	private static BigDecimal wholeDollars(BigDecimal value) {
		return value == null ? null : value.setScale(0, RoundingMode.UNNECESSARY);
	}

	/** 某個帳戶目前（沒作廢的）初始現金餘額；修改帳戶畫面用。 */
	@Transactional(readOnly = true)
	public Optional<CashMovement> findOpening(Long accountId) {
		return movements.findByUserIdAndAccountIdAndTypeAndVoidedFalse(currentUser.id(), accountId,
				CashMovementType.OPENING);
	}

	/** 有修改紀錄的異動 id（列表顯示「曾修改」，spec 4.9-3）。 */
	@Transactional(readOnly = true)
	public Set<Long> revisedIds() {
		return new HashSet<>(revisions.findRevisedMovementIds(currentUser.id()));
	}

	// ---------- 檢查 ----------

	/**
	 * 存進資料表的帳戶：from 是錢動到的帳戶（account_id；存入時是轉入帳戶，支出與互轉時是轉出帳戶），
	 * to 只有互轉才有（to_account_id）。
	 */
	private record Accounts(Long from, Long to) {
	}

	private static boolean isAmountMovement(CashMovementType type) {
		return type == CashMovementType.DEPOSIT || type == CashMovementType.WITHDRAWAL
				|| type == CashMovementType.TRANSFER;
	}

	/** 存入、支出、互轉共用的檢查（新增與修改都用）。 */
	private Accounts validateAmountMovement(Long userId, CashMovementType type, CashMovementForm form) {
		// 沒有 JavaScript 時畫面兩個帳戶欄位都會顯示：選了用不到的欄位要提醒，而不是默默忽略
		boolean usesFrom = type != CashMovementType.DEPOSIT;
		boolean usesTo = type != CashMovementType.WITHDRAWAL;
		if (!usesFrom && form.fromAccountId() != null) {
			throw new CashValidationException(type.label() + "不用選轉出帳戶");
		}
		if (!usesTo && form.toAccountId() != null) {
			throw new CashValidationException(type.label() + "不用選轉入帳戶");
		}
		if (usesFrom && form.fromAccountId() == null) {
			throw new CashValidationException("請選擇轉出帳戶");
		}
		if (usesTo && form.toAccountId() == null) {
			throw new CashValidationException("請選擇轉入帳戶");
		}
		Long fromId = usesFrom ? findMyAccount(form.fromAccountId()).getId() : null;
		Long toId = usesTo ? findMyAccount(form.toAccountId()).getId() : null;
		if (fromId != null && fromId.equals(toId)) {
			throw new CashValidationException("轉出和轉入不能是同一個帳戶");
		}
		Long moved = type == CashMovementType.DEPOSIT ? toId : fromId;
		Long to = type == CashMovementType.TRANSFER ? toId : null;
		checkPositiveWholeAmount(form.amount());
		checkDate(form.date());
		// 表單已經檢查過；之後給 App 用的 REST API 不一定有，超過欄位長度會變成資料庫錯誤（500）
		if (form.note() != null && form.note().length() > MAX_NOTE_LENGTH) {
			throw new CashValidationException("備註最多 100 個字");
		}
		checkNotBeforeOpening(userId, moved, form.date());
		if (to != null) {
			checkNotBeforeOpening(userId, to, form.date());
		}
		return new Accounts(moved, to);
	}

	/** 初始現金餘額是起點：不能晚於這個帳戶已經記過的其他異動。selfId 是修改中的那筆（新增時為 null）。 */
	private void validateOpening(Long userId, Long accountId, OpeningForm form, Long selfId) {
		checkWholeAmount(form.amount());
		if (form.amount().signum() < 0) {
			throw new CashValidationException("初始現金餘額不能是負數");
		}
		checkDate(form.date());
		Optional<LocalDate> earliest = movements.findEarliestDate(userId, accountId, selfId == null ? -1L : selfId);
		if (earliest.isPresent() && earliest.get().isBefore(form.date())) {
			throw new CashValidationException("這個帳戶在 " + earliest.get() + " 已經有異動，初始現金餘額日期不能晚於這一天");
		}
	}

	/** 對帳的檢查。selfId 是修改中的那筆（新增時為 null），檢查「同一天一筆」時要排除自己。 */
	private void validateReconcile(Long userId, Long accountId, ReconcileForm form, Long selfId) {
		checkWholeAmount(form.actualBalance());
		checkDate(form.date());
		checkNotBeforeOpening(userId, accountId, form.date());
		boolean taken = selfId == null
				? movements.existsByUserIdAndAccountIdAndTypeAndMovementDateAndVoidedFalse(userId, accountId,
						CashMovementType.RECONCILE, form.date())
				: movements.existsByUserIdAndAccountIdAndTypeAndMovementDateAndVoidedFalseAndIdNot(userId, accountId,
						CashMovementType.RECONCILE, form.date(), selfId);
		if (taken) {
			throw new CashValidationException(RECONCILE_EXISTS);
		}
	}

	private CashAccount findMyAccount(Long accountId) {
		if (accountId == null) {
			throw new CashValidationException("請選擇帳戶");
		}
		return accounts.findByIdAndUserId(accountId, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這個帳戶"));
	}

	/** 自己的、而且還沒作廢的異動；讀取時鎖住這一列，避免兩個請求同時修改或作廢。 */
	private CashMovement findMineForEdit(Long id) {
		CashMovement m = movements.findForUpdate(id, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這筆異動"));
		if (m.isVoided()) {
			throw new CashValidationException("這筆已經作廢，不能再修改");
		}
		return m;
	}

	private void checkNotBeforeOpening(Long userId, Long accountId, LocalDate date) {
		movements.findByUserIdAndAccountIdAndTypeAndVoidedFalse(userId, accountId, CashMovementType.OPENING)
			.map(CashMovement::getMovementDate)
			.filter(date::isBefore)
			.ifPresent(opening -> {
				throw new CashValidationException("日期不能早於這個帳戶的初始現金餘額日期（" + opening + "）");
			});
	}

	private void checkDate(LocalDate date) {
		if (date == null) {
			throw new CashValidationException("請選擇日期");
		}
		if (date.isAfter(LocalDate.now(clock))) {
			throw new CashValidationException("日期不能晚於今天");
		}
		if (date.isBefore(MIN_DATE)) {
			throw new CashValidationException("日期要在 2000-01-01 之後");
		}
	}

	private static void checkPositiveWholeAmount(BigDecimal amount) {
		checkWholeAmount(amount);
		if (amount.signum() <= 0) {
			throw new CashValidationException("金額要大於 0");
		}
	}

	/** 新台幣帳戶沒有角，金額只收整數元；也先擋掉過大的數字（像 1e2147483647 這種輸入會讓後續計算出錯變 500）。 */
	private static void checkWholeAmount(BigDecimal amount) {
		if (amount == null) {
			throw new CashValidationException("請輸入金額");
		}
		if (amount.abs().compareTo(MAX_AMOUNT) >= 0) {
			throw new CashValidationException("金額太大");
		}
		if (amount.stripTrailingZeros().scale() > 0) {
			throw new CashValidationException("金額只能是整數元");
		}
	}

	private static String cleanReason(String reason) {
		if (reason == null || reason.isBlank()) {
			return null;
		}
		String stripped = reason.strip();
		if (stripped.length() > MAX_REASON_LENGTH) {
			throw new CashValidationException("原因最多 200 個字");
		}
		return stripped;
	}

	// ---------- 修改紀錄 ----------

	/** 修改前的欄位值（字串）。金額去掉多餘的 0，讓 62000 和 62000.0000 視為相同。 */
	private static Map<String, String> snapshot(CashMovement m) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("movementDate", String.valueOf(m.getMovementDate()));
		values.put("accountId", text(m.getAccountId()));
		values.put("toAccountId", text(m.getToAccountId()));
		values.put("amount", money(m.getAmount()));
		values.put("actualBalance", money(m.getActualBalance()));
		values.put("note", m.getNote());
		return values;
	}

	private void writeEditRevisions(CashMovement m, Map<String, String> before, String reason) {
		Map<String, String> after = snapshot(m);
		LocalDateTime now = LocalDateTime.now(clock);
		before.forEach((field, oldValue) -> {
			String newValue = after.get(field);
			if (!Objects.equals(oldValue, newValue)) {
				revisions.save(CashMovementRevision.of(m.getId(), currentUser.id(), now, CashMovementRevision.EDIT,
						field, oldValue, newValue, reason));
			}
		});
	}

	private static String text(Long value) {
		return value == null ? null : value.toString();
	}

	private static String money(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	// ---------- 存檔 ----------

	private CashMovement save(CashMovement movement) {
		try {
			return movements.saveAndFlush(movement);
		}
		catch (DataIntegrityViolationException ex) {
			throw translateDuplicate(ex);
		}
	}

	/** 修改後立刻寫進資料庫，讓唯一限制的錯誤在方法裡就出現。 */
	private CashMovement flush(CashMovement movement) {
		try {
			movements.flush();
			return movement;
		}
		catch (DataIntegrityViolationException ex) {
			throw translateDuplicate(ex);
		}
	}

	/**
	 * 連點兩次時，兩個請求可能同時通過上面的檢查，由資料庫唯一限制擋下第二筆；
	 * 轉成和一般檢查相同的中文訊息，其他資料庫錯誤照原樣丟出。
	 */
	private static RuntimeException translateDuplicate(DataIntegrityViolationException ex) {
		String message = ex.getMostSpecificCause().getMessage();
		if (message != null && message.endsWith("for key 'cash_movement." + UNIQUE_OPENING + "'")) {
			return new CashValidationException(OPENING_EXISTS, ex);
		}
		if (message != null && message.endsWith("for key 'cash_movement." + UNIQUE_RECONCILE_DAY + "'")) {
			return new CashValidationException(RECONCILE_EXISTS, ex);
		}
		return ex;
	}

}
