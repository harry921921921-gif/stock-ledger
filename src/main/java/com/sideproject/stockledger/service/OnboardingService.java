package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpSession;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;

import com.sideproject.stockledger.calculator.LoanShareAllocationCalculator;
import com.sideproject.stockledger.dto.AccountInput;
import com.sideproject.stockledger.dto.AllocationLine;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.CollateralForm;
import com.sideproject.stockledger.dto.CollateralPage;
import com.sideproject.stockledger.dto.CollateralPick;
import com.sideproject.stockledger.dto.ConfirmRow;
import com.sideproject.stockledger.dto.LoanEditResult;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.LoanInput;
import com.sideproject.stockledger.dto.NewPledgeLine;
import com.sideproject.stockledger.dto.OnboardingSummary;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.SavedRow;
import com.sideproject.stockledger.dto.StockInput;
import com.sideproject.stockledger.dto.StockSourceInput;
import com.sideproject.stockledger.dto.StockSourceTable;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.OnboardingStep;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.entity.Trade;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.CurrentUser;

/**
 * 第一次使用引導的進度（設計文件 2026-10-25-onboarding 5、6；DR-050）。
 * 進度存在 app_user.onboarding_step，只往前不往後；user_id 只從 {@link CurrentUser} 取得。
 */
@Service
public class OnboardingService {

	private final AppUserRepository users;
	private final CurrentUser currentUser;
	private final CashAccountService accounts;
	private final LoanService loans;
	private final TradeService trades;
	private final MoneyFormatter money;
	private final CashMovementService movements;
	private final MessageSource messages;

	public OnboardingService(AppUserRepository users, CurrentUser currentUser, CashAccountService accounts,
			LoanService loans, TradeService trades, MoneyFormatter money, CashMovementService movements,
			MessageSource messages) {
		this.messages = messages;
		this.users = users;
		this.currentUser = currentUser;
		this.accounts = accounts;
		this.loans = loans;
		this.trades = trades;
		this.money = money;
		this.movements = movements;
	}

	@Transactional(readOnly = true)
	public OnboardingStep currentStep() {
		return me().getOnboardingStep();
	}

	/** 進到某一步就呼叫；只往前，按上一步回到前面的頁面不會把進度改小。 */
	@Transactional
	public void reach(OnboardingStep step) {
		me().advanceOnboarding(step);
	}

	@Transactional
	public void finish() {
		me().finishOnboarding();
	}

	@Transactional(readOnly = true)
	public boolean done() {
		return currentStep() == OnboardingStep.DONE;
	}

	// ---------- 「先離開」記號（設計文件 3：先離開直接看總覽，下次登入再回引導） ----------

	private static final String LEFT_ATTRIBUTE = OnboardingService.class.getName() + ".left.";

	/** 記下這個 session 裡目前登入者按過「先離開」。記號放 session：新的 session（下次登入）就沒有，會再被帶回引導。 */
	public void markLeft(HttpSession session) {
		session.setAttribute(LEFT_ATTRIBUTE + currentUser.id(), Boolean.TRUE);
	}

	/** GET / 要不要轉去引導：引導沒完成，而且這個 session 裡沒按過「先離開」。 */
	@Transactional(readOnly = true)
	public boolean shouldRedirectHome(HttpSession session) {
		return !done() && session.getAttribute(LEFT_ATTRIBUTE + currentUser.id()) == null;
	}

	/** 每一步的網址（{@link OnboardingStep#url}）；擔保那一步由 controller 再轉到第一筆需要的質押。 */
	public String urlOf(OnboardingStep step) {
		return step.url();
	}

	@Transactional(readOnly = true)
	public OnboardingSummary summary() {
		int stockCount = (int) trades.portfolio().positions().values().stream().filter(p -> p.shares() > 0).count();
		return new OnboardingSummary(accounts.listMine().size(), loans.listMine().size(), stockCount);
	}

	// ---------- 帳戶這一步（設計文件 3、3.2、4） ----------

	/** 引導修改已儲存的資料時自動填的修改原因（設計文件 4，我的判斷）。 */
	static final String EDIT_REASON = "第一次使用引導修改";

	static final String OPENING_CANNOT_CLEAR = "這個帳戶已經有初始現金餘額，不能清空；要改請直接填新的金額";

	/**
	 * 新增帳戶；有填餘額（opening 不是 null）就一起設定初始現金餘額。兩筆在同一個交易：
	 * 餘額不合規則時帳戶也不會建立，不會留下半套資料。
	 */
	@Transactional
	public CashAccount addAccount(CashAccountForm form, OpeningForm opening) {
		CashAccount account = accounts.create(form);
		if (opening != null) {
			movements.setOpening(account.getId(), opening);
		}
		return account;
	}

	/**
	 * 修改已儲存的帳戶（呼叫現有的 update）；初始現金餘額已經有就修改（寫修改紀錄，原因自動填），沒有就新增。
	 * 已經有初始現金餘額卻沒填：不能存（確認畫面寫「（沒填）」，資料庫卻還留著舊的餘額會讓人誤會）。
	 */
	@Transactional
	public void editAccount(Long id, CashAccountForm form, OpeningForm opening) {
		Optional<CashMovement> existing = movements.findOpening(id);
		if (opening == null && existing.isPresent()) {
			throw new CashValidationException(OPENING_CANNOT_CLEAR);
		}
		accounts.update(id, form);
		if (opening == null) {
			return;
		}
		if (existing.isPresent()) {
			movements.editOpening(existing.get().getId(), opening, EDIT_REASON);
		}
		else {
			movements.setOpening(id, opening);
		}
	}

	/**
	 * 預覽時的檢查（只檢查、不存）：和存檔走同一段規則，訊息一樣。id 是修改中的帳戶，新增時為 null。
	 * 必填與長度由 {@link CashAccountForm} 的 Bean Validation 負責，呼叫前要先通過。
	 * 故意不加 {@code @Transactional}：被呼叫的 validate 丟出例外時會把共用的交易標成只能回滾，
	 * 這裡接住例外繼續檢查，最後提交就會失敗（UnexpectedRollbackException）；各自一個唯讀交易就沒有這個問題。
	 */
	public List<String> accountErrors(Long id, CashAccountForm form, OpeningForm opening) {
		List<String> errors = new ArrayList<>();
		try {
			if (id == null) {
				accounts.validate(form);
			}
			else {
				accounts.validate(id, form);
			}
		}
		catch (IllegalArgumentException ex) {
			errors.add(ex.getMessage());
		}
		if (opening == null) {
			if (id != null && movements.findOpening(id).isPresent()) {
				errors.add(OPENING_CANNOT_CLEAR);
			}
		}
		else {
			try {
				// 帳戶已經存在（修改）就連「帳戶已經有更早的異動」一起檢查，和存檔完全一樣
				if (id == null) {
					movements.validateOpening(opening);
				}
				else {
					movements.validateOpening(id, opening);
				}
			}
			catch (CashValidationException ex) {
				errors.add(ex.getMessage());
			}
		}
		return errors;
	}

	/** 錯誤訊息照畫面上欄位的順序列（Bean Validation 回報的順序不固定）。 */
	private static final List<String> ACCOUNT_FIELDS = List.of("name", "type", "institution", "amount", "date");

	/**
	 * 帳戶這一步的「儲存」（預覽與確認儲存共用）：收下 Controller 綁好的表單與綁定結果，決定要套哪些規則。
	 * <ul>
	 * <li>餘額是選填：金額沒填就是不設定初始現金餘額（日期不管）；金額有填，或打了不是數字的字，才算有填。</li>
	 * <li>先看必填與格式（DTO 的 Bean Validation，訊息用 messages.properties），都過了才檢查名稱重複、餘額規則。</li>
	 * </ul>
	 * 回傳要存的內容（opening 為 null 表示不設定餘額）與錯誤訊息；訊息照原文顯示。不加交易，理由同 {@link #accountErrors}。
	 */
	public AccountInput checkAccount(Long id, CashAccountForm form, Errors formErrors, OpeningForm opening,
			Errors openingErrors) {
		boolean openingFilled = openingErrors.hasFieldErrors("amount") || (opening != null && opening.amount() != null);
		OpeningForm balance = openingFilled ? opening : null;
		List<String> errors = new ArrayList<>(messagesOf(formErrors, ACCOUNT_FIELDS));
		if (openingFilled) {
			errors.addAll(messagesOf(openingErrors, ACCOUNT_FIELDS));
		}
		if (errors.isEmpty()) {
			errors.addAll(accountErrors(id, form, balance));
		}
		return new AccountInput(form, balance, errors);
	}

	/** 這個 id 是不是自己的帳戶（草稿裡記的「修改中」那筆還在不在）。 */
	@Transactional(readOnly = true)
	public boolean isMyAccount(Long id) {
		return id != null && accounts.listMine().stream().anyMatch(a -> a.getId().equals(id));
	}

	/** 綁定錯誤轉成畫面文字（messages.properties，格式錯不會露出英文），照 fieldOrder 的順序、不重複。 */
	private List<String> messagesOf(Errors errors, List<String> fieldOrder) {
		Locale locale = LocaleContextHolder.getLocale();
		List<String> all = new ArrayList<>();
		errors.getFieldErrors()
			.stream()
			.sorted(Comparator.comparingInt((FieldError e) -> {
				int i = fieldOrder.indexOf(e.getField());
				return i < 0 ? fieldOrder.size() : i;
			}))
			.forEach(e -> all.add(messages.getMessage(e, locale)));
		errors.getGlobalErrors().forEach(e -> all.add(messages.getMessage(e, locale)));
		return all.stream().distinct().toList();
	}

	/** 修改已儲存帳戶時放進表單的內容。 */
	@Transactional(readOnly = true)
	public CashAccountForm accountForm(Long id) {
		return CashAccountForm.from(accounts.getMine(id));
	}

	/** 修改時放進表單的初始現金餘額；還沒設定就是空的金額、今天的日期（和新增時一樣）。 */
	@Transactional(readOnly = true)
	public OpeningForm openingFormOf(Long id) {
		accounts.getMine(id);
		return movements.findOpening(id).map(m -> movements.openingForm(m.getId())).orElseGet(this::blankOpening);
	}

	/** 新帳戶表單的餘額欄：金額空白、日期預設今天（樣稿）。 */
	public OpeningForm blankOpening() {
		return new OpeningForm(movements.today(), null);
	}

	/** 已儲存的帳戶，顯示「名稱（類型）」（樣稿）。 */
	@Transactional(readOnly = true)
	public List<SavedRow> savedAccounts() {
		return accounts.listMine()
			.stream()
			.map(a -> new SavedRow(a.getId(), a.getName() + "（" + a.getType().label() + "）"))
			.toList();
	}

	/** 至少有一個證券交割戶，才能往下走（買股票要從交割戶扣錢；設計文件 3.2）。 */
	@Transactional(readOnly = true)
	public boolean hasSettlementAccount() {
		return accounts.listMine().stream().anyMatch(a -> a.getType() == CashAccountType.SETTLEMENT);
	}

	// ---------- 借款這一步（設計文件 3、3.2、4、7a-1、7a-6） ----------

	/** 錯誤訊息照畫面上欄位的順序列。 */
	private static final List<String> LOAN_FIELDS = List.of("name", "bank", "repaymentMethod", "type", "startDate",
			"startPrincipal", "annualRatePercent", "monthlyPayment", "payDay", "remainingPeriods", "dueDate",
			"payAccountId", "fee");

	/** 新增借款（呼叫正式頁面同一個 create，規則一樣）。 */
	@Transactional
	public Loan addLoan(LoanForm form) {
		return loans.create(form);
	}

	/**
	 * 修改已儲存的借款（呼叫正式頁面同一個 edit，DR-051）。借款改小、不夠付已經用它買的股票時，
	 * confirmShortfall 為 false 會丟 {@link LoanShortfallException}，由畫面先問使用者。
	 */
	@Transactional
	public LoanEditResult editLoan(Long id, LoanForm form, boolean confirmShortfall) {
		requireMyLoan(id);
		return loans.edit(id, form, confirmShortfall);
	}

	/**
	 * 借款這一步的「儲存」（預覽與確認儲存共用）：先看格式（綁定錯誤，訊息用 messages.properties），
	 * 都過了才跑 LoanService 的檢查（和正式的新增借款同一段，訊息一樣）；沒有錯誤時附上確認區塊的提醒。
	 * 引導裡不勾現有持股當擔保（現有持股在第 4 步「擔保」設定，設計文件 3）：表單帶來的擔保持股、維持率門檻一律拿掉。
	 * 「還沒記在系統裡的質押股票」（newPledges）新增時保留，和資金來源頁走同一段檢查（設計文件 2026-11-04-pledged-stocks 4.1）；
	 * 修改已儲存的借款（id 不是 null）不支援，一律拿掉（LoanService.edit 不處理）。
	 * 修改時也用新增的檢查：引導裡的借款都是新寫法的新撥款，修改才會遇到的規則（例如借款改小不夠付股票）存檔時由 edit 再擋。
	 * 不加交易，理由同 {@link #accountErrors}。
	 */
	public LoanInput checkLoan(Long id, LoanForm submitted, Errors formErrors) {
		// 格式錯到建不出表單（submitted 為 null）時一定有綁定錯誤，不會往下檢查
		LoanForm form = submitted == null ? null : withoutCollateral(submitted, id == null);
		List<String> errors = new ArrayList<>(messagesOf(formErrors, LOAN_FIELDS));
		if (errors.isEmpty() && form != null) {
			try {
				loans.validate(form);
			}
			catch (LoanValidationException ex) {
				errors.add(ex.getMessage());
			}
		}
		return new LoanInput(form, errors, errors.isEmpty() ? loanWarnings(form) : List.of());
	}

	/**
	 * 拿掉擔保持股與門檻。「還沒記在系統裡的質押股票」只有新增（keepNewPledges）而且是股票質押時保留：
	 * 沒勾股票質押時畫面上的表格是藏起來的（瀏覽器不送），沒有 JavaScript 時送來的列也一樣不理，不擋存檔。
	 */
	private static LoanForm withoutCollateral(LoanForm f, boolean keepNewPledges) {
		boolean keep = keepNewPledges && f.type() == LoanType.PLEDGE;
		return new LoanForm(f.name(), f.type(), f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				f.remainingPeriods(), f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(),
				List.of(), null, null, keep ? f.newPledges() : List.of());
	}

	/** 新借款表單的預設值：信貸、新撥款、每月固定還款、撥款日今天、一列空的質押股票（和正式的新增借款一樣）。 */
	public LoanForm blankLoan() {
		return new LoanForm(null, LoanType.CREDIT, null, LoanOrigin.NEW, null, loans.today(), null, null, null, null,
				null, null, null, null, RepaymentMethod.MONTHLY_FIXED, null, null, List.of(), null, null,
				List.of(NewPledgeLine.blank()));
	}

	/** 「還沒記在系統裡的質押股票」最多幾列（到了就不顯示「＋ 再加一檔」）。 */
	public int maxNewPledges() {
		return LoanService.MAX_NEW_PLEDGES;
	}

	/**
	 * 質押股票表格的「＋ 再加一檔」（add）、「拿掉」（remove，第 index 列）、「帶入收盤價」（prices）：
	 * 和資金來源頁同一個 {@link LoanService#changePledgeRows}，不檢查、不存。
	 * 不加交易：帶入收盤價要連網路，不放在資料庫交易裡（理由見 {@link LoanService#fillPrices}）。
	 */
	public List<NewPledgeLine> changePledgeRows(List<NewPledgeLine> lines, String action, Integer index) {
		return loans.changePledgeRows(lines, action, index);
	}

	/** 修改已儲存借款時放進表單的內容；別人的、不存在或已作廢的借款 → 404。 */
	@Transactional(readOnly = true)
	public LoanForm loanForm(Long id) {
		requireMyLoan(id);
		return loans.form(id);
	}

	/** 這個 id 是不是自己的、沒作廢的借款（草稿裡記的「修改中」那筆還在不在）。 */
	@Transactional(readOnly = true)
	public boolean isMyLoan(Long id) {
		return id != null && loans.listMine().stream().anyMatch(l -> l.getId().equals(id));
	}

	private void requireMyLoan(Long id) {
		if (!isMyLoan(id)) {
			throw new NotFoundException("找不到這筆借款");
		}
	}

	/** 已儲存的借款，顯示「名稱（還款方式、股票質押）」。 */
	@Transactional(readOnly = true)
	public List<SavedRow> savedLoans() {
		return loans.listMine()
			.stream()
			.map(l -> new SavedRow(l.getId(), l.getName() + "（" + l.repaymentMethodOrLegacy().label()
					+ (l.getType() == LoanType.PLEDGE ? "、股票質押" : "") + "）"))
			.toList();
	}

	/** 往來帳戶的選項（自己的帳戶）。 */
	@Transactional(readOnly = true)
	public List<CashAccount> myAccounts() {
		return accounts.listMine();
	}

	// ---------- 股票這一步（設計文件 3、3.1、3.2、4） ----------

	/** 錯誤訊息照畫面上欄位的順序列。 */
	private static final List<String> STOCK_FIELDS = List.of("stockCode", "type", "date", "shares", "price",
			"accountId", "allocations");

	/** 新增股票（呼叫股票頁同一個 create，規則與訊息一樣）。 */
	@Transactional
	public Trade addStock(TradeForm form) {
		return trades.create(form);
	}

	/** 修改已儲存的股票（呼叫股票頁同一個 edit，寫修改紀錄，原因自動填）；記法、股票不能改，由 edit 擋下。 */
	@Transactional
	public void editStock(Long id, TradeForm form) {
		requireMyStock(id);
		trades.edit(id, form, EDIT_REASON);
	}

	/**
	 * 股票這一步的「儲存」（預覽與確認儲存共用）：先看格式（綁定錯誤，訊息用 messages.properties），
	 * 都過了才算這筆的總成本、檢查並換算資金來源表格（設計文件 3.4），最後跑 TradeService 的檢查
	 * （新增用 validate、修改用 validateEdit，和存檔同一段，訊息一樣：DR-044 尚未投入、交易日不能早於撥款日）。
	 * 送來的 allocations 不看：資金來源只從表格（sources）換算。id 是修改中的那筆，新增時為 null。
	 * 不加交易，理由同 {@link #accountErrors}。
	 */
	public StockInput checkStock(Long id, TradeForm submitted, Errors formErrors, StockSourceInput sources) {
		List<String> errors = new ArrayList<>(messagesOf(formErrors, STOCK_FIELDS));
		// 格式錯到建不出表單（submitted 為 null）時一定有綁定錯誤，不會往下檢查
		if (submitted == null) {
			return new StockInput(null, errors, List.of());
		}
		TradeForm form = withAllocations(keptAccount(id, submitted), List.of());
		if (!errors.isEmpty()) {
			return new StockInput(form, errors, List.of());
		}
		BigDecimal total;
		try {
			total = totalCost(id, form);
		}
		catch (TradeValidationException ex) { // 例如還沒填價格：和直接存檔時同一句
			return new StockInput(form, List.of(ex.getMessage()), List.of());
		}
		SourcePlan plan = plan(form.shares(), total, sources == null ? StockSourceInput.none() : sources);
		if (!plan.errors().isEmpty()) {
			return new StockInput(form, plan.errors(), List.of());
		}
		TradeForm withSources = withAllocations(form, plan.lines());
		try {
			if (id == null) {
				trades.validate(withSources);
			}
			else {
				trades.validateEdit(id, withSources);
			}
		}
		catch (TradeValidationException ex) {
			return new StockInput(withSources, List.of(ex.getMessage()), List.of());
		}
		return new StockInput(withSources, List.of(), sourceLines(form.shares(), plan));
	}

	static final String DUPLICATE_LOAN = "同一筆借款不能出現兩列";

	static final String LOTS_NOT_WHOLE = "張數要填正整數";

	static final String ODD_LOT = "這筆有零股，請改填金額";

	static final String AMOUNT_NOT_POSITIVE = "借款的金額要大於 0";

	static final String NOT_WHOLE_LOTS = "這些金額換不回整數張，請繼續填金額";

	private static final long SHARES_PER_LOT = 1000;

	/**
	 * 資金來源表格檢查與換算的結果。lines 是要存的每筆借款出的錢（照表格順序）；lots 是每筆的張數（填金額時為 null）；
	 * own 是自己的錢（總成本 − 借款合計）。
	 */
	private record SourcePlan(List<String> errors, List<AllocationLine> lines, List<Long> lots, BigDecimal own) {

		static SourcePlan failed(List<String> errors) {
			return new SourcePlan(errors, List.of(), null, null);
		}

	}

	/**
	 * 檢查表格並把張數（或金額）換成每筆借款出的錢（設計文件 3.4）。shares、total 已由 TradeService 算過（大於 0）。
	 * 填張數：張數是正整數、沒有零股、借款張數合計不超過總張數，再由 {@link LoanShareAllocationCalculator} 換算；
	 * 填金額：照填的金額，合計不超過總成本（整數元、大於 0 等由 TradeService 檢查，訊息和股票頁一樣）。
	 */
	private SourcePlan plan(long shares, BigDecimal total, StockSourceInput sources) {
		List<StockSourceInput.Row> rows = sources.sources().stream().filter(r -> !r.blank()).toList();
		List<String> errors = new ArrayList<>();
		if (rows.stream().anyMatch(r -> r.loanId() == null)) {
			errors.add("請選擇借款");
		}
		List<Long> ids = rows.stream().map(StockSourceInput.Row::loanId).filter(Objects::nonNull).toList();
		if (ids.stream().distinct().count() != ids.size()) {
			errors.add(DUPLICATE_LOAN);
		}
		if (rows.stream().anyMatch(r -> r.value().isEmpty())) {
			errors.add("還沒填：借款的" + (sources.units() ? "張數" : "金額"));
		}
		if (sources.units()) {
			List<Long> lots = rows.stream().map(r -> positiveLots(r.value())).toList();
			if (rows.stream().anyMatch(r -> !r.value().isEmpty() && positiveLots(r.value()) == null)) {
				errors.add(LOTS_NOT_WHOLE);
			}
			if (!rows.isEmpty() && shares % SHARES_PER_LOT != 0) {
				errors.add(ODD_LOT);
			}
			if (!errors.isEmpty()) {
				return SourcePlan.failed(errors);
			}
			long loanLots = lots.stream().mapToLong(Long::longValue).sum();
			if (loanLots * SHARES_PER_LOT > shares) {
				return SourcePlan.failed(List.of("借款的張數加起來超過這筆的張數（"
						+ money.plain(BigDecimal.valueOf(shares / SHARES_PER_LOT)) + " 張）"));
			}
			List<BigDecimal> amounts = rows.isEmpty() ? List.of()
					: LoanShareAllocationCalculator.amountsFromLots(total, shares, lots);
			return new SourcePlan(List.of(), lines(rows, amounts), lots, total.subtract(sum(amounts)));
		}
		List<BigDecimal> amounts = rows.stream().map(r -> parseNumber(r.value())).toList();
		if (rows.stream().anyMatch(r -> !r.value().isEmpty() && parseNumber(r.value()) == null)) {
			errors.add("借款的金額請輸入數字");
		}
		else if (amounts.stream().anyMatch(a -> a != null && a.signum() <= 0)) {
			errors.add(AMOUNT_NOT_POSITIVE);
		}
		if (!errors.isEmpty()) {
			return SourcePlan.failed(errors);
		}
		if (sum(amounts).compareTo(total) > 0) {
			return SourcePlan.failed(List.of("借款出的錢加起來超過這筆的總成本（" + money.plain(total) + " 元）"));
		}
		return new SourcePlan(List.of(), lines(rows, amounts), null, total.subtract(sum(amounts)));
	}

	private static List<AllocationLine> lines(List<StockSourceInput.Row> rows, List<BigDecimal> amounts) {
		List<AllocationLine> lines = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			lines.add(new AllocationLine(rows.get(i).loanId(), amounts.get(i)));
		}
		return lines;
	}

	/** 確認畫面的資金來源：「玉山質押 4 張（294,419 元）」…最後「自己的錢 3 張（220,814 元）」；填金額時不寫張。 */
	private List<String> sourceLines(long shares, SourcePlan plan) {
		Map<Long, String> names = loanNames();
		List<String> out = new ArrayList<>();
		for (int i = 0; i < plan.lines().size(); i++) {
			AllocationLine line = plan.lines().get(i);
			out.add(names.getOrDefault(line.loanId(), "") + (plan.lots() == null ? "" : " " + plan.lots().get(i) + " 張")
					+ "（" + money.plain(line.amount()) + " 元）");
		}
		boolean units = plan.lots() != null && shares % SHARES_PER_LOT == 0;
		long ownLots = units ? shares / SHARES_PER_LOT - plan.lots().stream().mapToLong(Long::longValue).sum() : 0;
		out.add("自己的錢" + (units ? " " + money.plain(BigDecimal.valueOf(ownLots)) + " 張" : "") + "（"
				+ money.plain(plan.own()) + " 元）");
		return out;
	}

	/** 這筆的總成本（買進含手續費；目前持有是平均成本 × 股數），由 TradeService 算（和存檔時一樣）。 */
	private BigDecimal totalCost(Long id, TradeForm form) {
		TradeForm own = withAllocations(form, List.of());
		return (id == null ? trades.quote(own) : trades.quoteEdit(id, own)).amount();
	}

	/** 畫面上用：算不出總成本（還沒填價格、代號打錯…）就 empty，不擋畫面。 */
	private Optional<BigDecimal> totalCostIfKnown(Long id, TradeForm form) {
		if (form == null || form.shares() == null || form.shares() <= 0) {
			return Optional.empty();
		}
		try {
			return Optional.of(totalCost(id, keptAccount(id, form)));
		}
		catch (TradeValidationException | NotFoundException ex) {
			return Optional.empty();
		}
	}

	/** 正整數張數；空白、小數、負數、0、不是數字都是 null。 */
	private static Long positiveLots(String value) {
		BigDecimal n = parseNumber(value);
		if (n == null || n.signum() <= 0 || n.stripTrailingZeros().scale() > 0 || n.compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0) {
			return null;
		}
		return n.longValueExact();
	}

	private static BigDecimal parseNumber(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(value.strip().replace(",", ""));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static BigDecimal sum(List<BigDecimal> values) {
		return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private Map<Long, String> loanNames() {
		return loans.listMine().stream().collect(Collectors.toMap(Loan::getId, Loan::getName));
	}

	/**
	 * 修改已儲存股票時表格的內容（設計文件 3.4）：存的金額換得回整數張（{@link #savedLots}）就用填張數，
	 * 否則用填金額、照原本的金額，不改動使用者的數字。沒有借款是空表格（全部自己的錢）。
	 */
	@Transactional(readOnly = true)
	public StockSourceInput stockSources(Long id) {
		requireMyStock(id);
		Trade trade = trades.find(id);
		List<AllocationLine> lines = trades.form(id).allocations();
		if (lines.isEmpty()) {
			return StockSourceInput.none();
		}
		Optional<List<Long>> lots = savedLots(trade, lines);
		List<StockSourceInput.Row> rows = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			rows.add(new StockSourceInput.Row(lines.get(i).loanId(),
					lots.isPresent() ? lots.get().get(i).toString() : plainNumber(lines.get(i).amount())));
		}
		return new StockSourceInput(lots.isPresent() ? StockSourceInput.Mode.UNITS : StockSourceInput.Mode.AMOUNT,
				rows);
	}

	/** 已存的金額換回張數：整張、換回來每筆都剛好等於原金額、每筆至少 1 張、合計不超過總張數才算。 */
	private static Optional<List<Long>> savedLots(Trade trade, List<AllocationLine> lines) {
		long shares = trade.getShares();
		if (shares <= 0 || shares % SHARES_PER_LOT != 0) {
			return Optional.empty();
		}
		return LoanShareAllocationCalculator
			.lotsFromAmounts(trade.getAmount(), shares, lines.stream().map(AllocationLine::amount).toList())
			.filter(lots -> lots.stream().allMatch(l -> l > 0)
					&& lots.stream().mapToLong(Long::longValue).sum() * SHARES_PER_LOT <= shares);
	}

	/**
	 * 畫面上的資金來源表格（設計文件 3.4；樣稿 stock 的「這筆錢從哪裡來？」）：每列的換算金額、自己的錢的張數與金額。
	 * 空白的列不換算、不顯示金額（也不吸收差額）；算不出來（總成本不知道、張數打錯、零股填張數）顯示「—」。存檔前的檢查在 {@link #checkStock}。
	 * 不加交易：試算總成本時 TradeService 的檢查例外會把外層交易標成 rollback-only（理由同 {@link #accountErrors}）。
	 */
	public StockSourceTable sourceTable(Long id, TradeForm form, StockSourceInput sources) {
		StockSourceInput s = sources == null ? StockSourceInput.none() : sources;
		List<SavedRow> loanRows = savedLoans();
		boolean canAdd = !loanRows.isEmpty() && s.sources().size() < Math.min(loanRows.size(), StockSourceInput.MAX_ROWS);
		Optional<BigDecimal> total = totalCostIfKnown(id, form);
		Long shares = form == null ? null : form.shares();
		List<StockSourceInput.Row> rows = s.sources();
		List<StockSourceTable.Row> out = new ArrayList<>();
		if (s.units()) {
			Optional<List<Long>> lots = filledLots(rows);
			boolean lotsOk = lots.isPresent() && shares != null && shares > 0 && shares % SHARES_PER_LOT == 0;
			List<BigDecimal> amounts = lotsOk && total.isPresent() ? amountsOfFilledRows(total.get(), shares, rows, lots.get())
					: null;
			for (int i = 0; i < rows.size(); i++) {
				String amount = rows.get(i).value().isEmpty() ? "" : amounts == null ? DASH : money.plain(amounts.get(i));
				out.add(new StockSourceTable.Row(rows.get(i).loanId(), rows.get(i).value(), amount));
			}
			String ownUnits = lotsOk ? money.plain(BigDecimal.valueOf(shares / SHARES_PER_LOT
					- lots.get().stream().filter(Objects::nonNull).mapToLong(Long::longValue).sum())) + " 張" : DASH;
			String ownAmount = total.isPresent() && amounts != null ? money.plain(total.get().subtract(sumNonNull(amounts)))
					: DASH;
			return new StockSourceTable(true, out, ownUnits, ownAmount, canAdd);
		}
		List<BigDecimal> amounts = rows.stream().map(r -> r.value().isEmpty() ? null : parseNumber(r.value())).toList();
		boolean amountsOk = true;
		for (int i = 0; i < rows.size(); i++) {
			boolean blank = rows.get(i).value().isEmpty();
			amountsOk &= blank || amounts.get(i) != null;
			String amount = blank ? "" : amounts.get(i) == null ? DASH : money.plain(amounts.get(i));
			out.add(new StockSourceTable.Row(rows.get(i).loanId(), rows.get(i).value(), amount));
		}
		String ownAmount = total.isPresent() && amountsOk ? money.plain(total.get().subtract(sumNonNull(amounts))) : DASH;
		return new StockSourceTable(false, out, "", ownAmount, canAdd);
	}

	/**
	 * 表格每列的張數：空白列是 null（不算進換算）；有任何一列不是正整數就 empty。
	 */
	private static Optional<List<Long>> filledLots(List<StockSourceInput.Row> rows) {
		List<Long> lots = new ArrayList<>();
		for (StockSourceInput.Row r : rows) {
			if (r.value().isEmpty()) {
				lots.add(null);
				continue;
			}
			Long n = positiveLots(r.value());
			if (n == null) {
				return Optional.empty();
			}
			lots.add(n);
		}
		return Optional.of(lots);
	}

	/**
	 * 只拿有填的列去換算（空白列不參與，所以全部借款時是最後一列「有填的」借款吸收差額），結果放回原本的位置；空白列是 null。
	 */
	private static List<BigDecimal> amountsOfFilledRows(BigDecimal total, long shares, List<StockSourceInput.Row> rows,
			List<Long> lots) {
		List<Long> filled = lots.stream().filter(Objects::nonNull).toList();
		List<BigDecimal> computed = filled.isEmpty() ? List.of()
				: LoanShareAllocationCalculator.amountsFromLots(total, shares, filled);
		List<BigDecimal> out = new ArrayList<>();
		int next = 0;
		for (Long l : lots) {
			out.add(l == null ? null : computed.get(next++));
		}
		return out;
	}

	private static BigDecimal sumNonNull(List<BigDecimal> values) {
		return values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static final String DASH = "—";

	/**
	 * 表格的「＋ 加一筆借款」「拿掉」「改填金額／改填張數」（伺服器端改列，不用 JavaScript）。
	 * add：加一列，借款預設第一筆還沒用到的；remove：拿掉第 index 列；mode：切換填張數／填金額，並把目前的數字換算過去：
	 * 張數 → 金額照 {@link LoanShareAllocationCalculator#amountsFromLots}（算不出來就清空讓使用者重填）；
	 * 金額 → 張數只有換得回整數張才換，否則維持填金額並回傳「這些金額換不回整數張，請繼續填金額」。
	 * 不加交易，理由同 {@link #sourceTable}。
	 */
	public StockSourceInput.Change changeSources(Long id, TradeForm form, StockSourceInput sources, String action,
			Integer index) {
		StockSourceInput s = sources == null ? StockSourceInput.none() : sources;
		List<StockSourceInput.Row> rows = new ArrayList<>(s.sources());
		switch (action) {
			case "add" -> {
				List<SavedRow> loanRows = savedLoans();
				if (!loanRows.isEmpty() && rows.size() < Math.min(loanRows.size(), StockSourceInput.MAX_ROWS)) {
					List<Long> used = rows.stream().map(StockSourceInput.Row::loanId).toList();
					Long next = loanRows.stream().map(SavedRow::id).filter(lid -> !used.contains(lid)).findFirst()
						.orElse(loanRows.get(0).id());
					rows.add(new StockSourceInput.Row(next, ""));
				}
				return new StockSourceInput.Change(new StockSourceInput(s.sourceMode(), rows), null);
			}
			case "remove" -> {
				if (index != null && index >= 0 && index < rows.size()) {
					rows.remove(index.intValue());
				}
				return new StockSourceInput.Change(new StockSourceInput(s.sourceMode(), rows), null);
			}
			case "mode" -> {
				return s.units() ? toAmounts(id, form, rows) : toLots(id, form, s, rows);
			}
			default -> throw new NotFoundException("找不到這個動作");
		}
	}

	private StockSourceInput.Change toAmounts(Long id, TradeForm form, List<StockSourceInput.Row> rows) {
		Optional<BigDecimal> total = totalCostIfKnown(id, form);
		Optional<List<Long>> lots = filledLots(rows);
		List<BigDecimal> amounts = total.isPresent() && lots.isPresent()
				? amountsOfFilledRows(total.get(), form.shares(), rows, lots.get()) : null;
		List<StockSourceInput.Row> converted = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			String value = amounts == null || amounts.get(i) == null ? "" : plainNumber(amounts.get(i));
			converted.add(new StockSourceInput.Row(rows.get(i).loanId(), value));
		}
		return new StockSourceInput.Change(new StockSourceInput(StockSourceInput.Mode.AMOUNT, converted), null);
	}

	/** 金額 → 張數：空白列不看、照樣留空白；有填的列都換得回整數張（每列至少 1 張）才換。 */
	private StockSourceInput.Change toLots(Long id, TradeForm form, StockSourceInput current,
			List<StockSourceInput.Row> rows) {
		List<StockSourceInput.Row> filled = rows.stream().filter(r -> !r.value().isEmpty()).toList();
		if (filled.isEmpty()) {
			return new StockSourceInput.Change(new StockSourceInput(StockSourceInput.Mode.UNITS, rows), null);
		}
		Optional<BigDecimal> total = totalCostIfKnown(id, form);
		List<BigDecimal> amounts = filled.stream().map(r -> parseNumber(r.value())).toList();
		Long shares = form == null ? null : form.shares();
		Optional<List<Long>> lots = Optional.empty();
		if (total.isPresent() && amounts.stream().allMatch(a -> a != null && a.signum() > 0)
				&& shares % SHARES_PER_LOT == 0) {
			lots = LoanShareAllocationCalculator.lotsFromAmounts(total.get(), shares, amounts)
				.filter(l -> l.stream().allMatch(n -> n > 0));
		}
		if (lots.isEmpty()) {
			return new StockSourceInput.Change(current, NOT_WHOLE_LOTS);
		}
		List<StockSourceInput.Row> converted = new ArrayList<>();
		int next = 0;
		for (StockSourceInput.Row r : rows) {
			converted.add(new StockSourceInput.Row(r.loanId(), r.value().isEmpty() ? "" : lots.get().get(next++).toString()));
		}
		return new StockSourceInput.Change(new StockSourceInput(StockSourceInput.Mode.UNITS, converted), null);
	}

	/** 金額寫回輸入框：不加千分位、不帶多餘的 0（300000.0000 → 300000）。 */
	private static String plainNumber(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	/** 修改的那筆買進記在不是交割戶的帳戶：沿用原本的帳戶（畫面上只顯示說明，{@link #stockAccountNote}）。 */
	private TradeForm keptAccount(Long id, TradeForm form) {
		if (id == null) {
			return form;
		}
		Trade trade = trades.find(id);
		return keepsAccount(trade) ? withAccount(form, trade.getAccountId()) : form;
	}

	/** 買進記在不是自己交割戶的帳戶（股票頁可以選任何帳戶）：引導的下拉沒有這個選項，沿用原本的帳戶。 */
	private boolean keepsAccount(Trade trade) {
		return trade.getType() == TradeType.BUY && trade.getAccountId() != null
				&& settlementAccounts().stream().noneMatch(a -> a.getId().equals(trade.getAccountId()));
	}

	/** 修改的那筆記在不是交割戶的帳戶：畫面上顯示「記在「X」帳戶，要改請到股票頁」、不放下拉；不是就回傳 null。 */
	@Transactional(readOnly = true)
	public String stockAccountNote(Long id) {
		requireMyStock(id);
		Trade trade = trades.find(id);
		return keepsAccount(trade) ? "記在「" + accountName(trade.getAccountId()) + "」帳戶，要改請到股票頁" : null;
	}

	private static TradeForm withAccount(TradeForm f, Long accountId) {
		return new TradeForm(f.type(), f.date(), f.stockCode(), accountId, f.shares(), f.price(), f.fee(), f.tax(),
				f.amount(), f.allocations());
	}

	private static TradeForm withAllocations(TradeForm f, List<AllocationLine> lines) {
		return new TradeForm(f.type(), f.date(), f.stockCode(), f.accountId(), f.shares(), f.price(), f.fee(), f.tax(),
				f.amount(), lines);
	}

	/** 新股票表單的預設值：一次買進、日期今天（樣稿）。 */
	public TradeForm blankStock() {
		return new TradeForm(TradeType.BUY, trades.today(), null, null, null, null, null, null, null, List.of());
	}

	/** 修改已儲存股票時放進表單的內容；別人的、不存在或已作廢的 → 404。 */
	@Transactional(readOnly = true)
	public TradeForm stockForm(Long id) {
		requireMyStock(id);
		return trades.form(id);
	}

	/** 這個 id 是不是自己的、沒作廢的期初持股或買進（引導只記這兩種）。 */
	@Transactional(readOnly = true)
	public boolean isMyStock(Long id) {
		return id != null && wizardTrades().stream().anyMatch(t -> t.getId().equals(id));
	}

	private void requireMyStock(Long id) {
		if (!isMyStock(id)) {
			throw new NotFoundException("找不到這筆交易");
		}
	}

	private List<Trade> wizardTrades() {
		return trades.listMine()
			.stream()
			.filter(t -> t.getType() == TradeType.OPENING || t.getType() == TradeType.BUY)
			.toList();
	}

	/**
	 * 已儲存的股票（設計文件 3.4），例如「0050　10 張　73.5 元（玉山質押 4、土銀信貸 3、自己 3）」：股數是整張寫張、否則寫股；
	 * 價格是買進的成交價或目前持有的平均成本；有借款時括號列來源，換得回整數張寫張數，否則寫金額
	 * 「（玉山質押 300,000 元、自己 436,047 元）」。自己的錢是 0 不列。
	 */
	@Transactional(readOnly = true)
	public List<SavedRow> savedStocks() {
		Map<Long, String> names = loanNames();
		return wizardTrades().stream().map(t -> {
			TradeForm f = trades.form(t.getId());
			long shares = f.shares();
			String size = shares % SHARES_PER_LOT == 0
					? money.plain(BigDecimal.valueOf(shares / SHARES_PER_LOT)) + " 張"
					: money.plain(BigDecimal.valueOf(shares)) + " 股";
			return new SavedRow(t.getId(), f.stockCode() + "　" + size + "　" + decimal(f.price()) + " 元"
					+ savedSources(t, f.allocations(), names));
		}).toList();
	}

	private String savedSources(Trade trade, List<AllocationLine> lines, Map<Long, String> names) {
		if (lines.isEmpty()) {
			return "";
		}
		Optional<List<Long>> lots = savedLots(trade, lines);
		List<String> parts = new ArrayList<>();
		if (lots.isPresent()) {
			for (int i = 0; i < lines.size(); i++) {
				parts.add(names.getOrDefault(lines.get(i).loanId(), "") + " " + lots.get().get(i));
			}
			long own = trade.getShares() / SHARES_PER_LOT - lots.get().stream().mapToLong(Long::longValue).sum();
			if (own > 0) {
				parts.add("自己 " + own);
			}
		}
		else {
			lines.forEach(l -> parts.add(names.getOrDefault(l.loanId(), "") + " " + money.plain(l.amount()) + " 元"));
			BigDecimal own = trade.getAmount().subtract(sum(lines.stream().map(AllocationLine::amount).toList()));
			if (own.signum() > 0) {
				parts.add("自己 " + money.plain(own) + " 元");
			}
		}
		return "（" + String.join("、", parts) + "）";
	}

	/** 「從哪個交割戶扣款」的選項（自己的證券交割戶）。 */
	@Transactional(readOnly = true)
	public List<CashAccount> settlementAccounts() {
		return accounts.listMine().stream().filter(a -> a.getType() == CashAccountType.SETTLEMENT).toList();
	}

	/**
	 * 「股票記完了，下一步」要去哪裡：有沒作廢的股票質押 → 記到擔保那一步，到擔保頁（由擔保頁轉到第一筆需要的質押）；
	 * 沒有 → 完成頁（進度不變成完成，按「到總覽看看」才算）。
	 */
	@Transactional
	public String afterStocks() {
		boolean pledged = loans.listMine().stream().anyMatch(l -> l.getType() == LoanType.PLEDGE);
		if (!pledged) {
			return "/welcome/done";
		}
		reach(OnboardingStep.COLLATERAL);
		return urlOf(OnboardingStep.COLLATERAL);
	}

	// ---------- 擔保這一步（設計文件 3、4、7a-3、7a-4） ----------
	// 每筆沒作廢的股票質押一頁（照借款 id 的順序）。擔保股票的規則與訊息都在 LoanService（setCollateral／setThresholds），
	// 這裡只決定要呼叫哪些、順序與畫面要的資料。

	private static final String COLLATERAL_URL = "/welcome/collateral/";

	/** 擔保頁的內容；別人的、不存在、已作廢或不是質押的借款 → 404。持股照股票代號排。 */
	@Transactional(readOnly = true)
	public CollateralPage collateralPage(Long loanId) {
		Loan loan = requireMyPledge(loanId);
		Map<Long, Long> pledged = loans.collateralOf(loanId);
		Map<Long, Long> elsewhere = loans.collateralOfOtherLoans(loanId);
		List<CollateralPage.Row> rows = trades.holdingValues()
			.stream()
			.sorted(Comparator.comparing(TradeService.HoldingValue::code))
			.map(h -> new CollateralPage.Row(h.stockId(), h.code(), h.label(), h.shares(), pledged.get(h.stockId()),
					Math.max(0, h.shares() - elsewhere.getOrDefault(h.stockId(), 0L))))
			.toList();
		List<String> saved = rows.stream()
			.filter(r -> r.pledged() != null)
			.map(r -> r.label() + "　" + money.plain(BigDecimal.valueOf(r.pledged())) + " 股")
			.toList();
		LoanService.Thresholds thresholds = loans.thresholdsOf(loanId);
		boolean last = pledges().stream().noneMatch(id -> id > loanId);
		return new CollateralPage(loanId, loan.getName(), rows, thresholds.warnPercent(), thresholds.callPercent(),
				saved, last ? "下一步：完成 →" : "下一步：下一筆質押 →");
	}

	/**
	 * 擔保頁表單的預設內容（第 i 列對應持股清單第 i 列）。預設股數是還能押的股數（持股扣掉其他沒作廢的質押已押的，
	 * 審查決定：第二筆質押押同一批股票時，預設全部持股會一按儲存就超過）。
	 * 這筆質押還沒有擔保股票 → 還能押的大於 0 就勾（設計文件 3「全部勾」）；
	 * 已經有 → 照存的勾與股數，沒擔保的那幾檔不勾。門檻是目前生效的值。
	 */
	@Transactional(readOnly = true)
	public CollateralForm collateralForm(Long loanId) {
		CollateralPage page = collateralPage(loanId);
		boolean none = page.saved().isEmpty();
		List<CollateralPick> picks = page.rows()
			.stream()
			.map(r -> {
				if (r.pledged() != null) {
					return new CollateralPick(r.stockId(), r.pledged());
				}
				boolean check = none && r.available() > 0;
				return new CollateralPick(check ? r.stockId() : null, r.available());
			})
			.toList();
		return new CollateralForm(picks, page.warnPercent(), page.callPercent());
	}

	/**
	 * 擔保頁的「儲存」（預覽與確認儲存共用）：先看格式（綁定錯誤，訊息用 messages.properties），都過了才跑
	 * LoanService 的檢查（和資金來源頁同一段，訊息一樣）：每一檔勾了的股數、提醒線與追繳線。
	 * 不加交易，理由同 {@link #accountErrors}。
	 */
	public List<String> checkCollateral(Long loanId, CollateralForm form, Errors formErrors) {
		CollateralPage page = collateralPage(loanId);
		List<String> errors = new ArrayList<>(messagesOf(formErrors, List.of()));
		if (!errors.isEmpty() || form == null) {
			return errors;
		}
		Map<Long, Long> picks = picked(page, form.collateral());
		for (CollateralPage.Row row : page.rows()) {
			if (picks.containsKey(row.stockId())) {
				try {
					loans.validateCollateral(loanId, row.code(), picks.get(row.stockId()));
				}
				catch (LoanValidationException ex) {
					errors.add(ex.getMessage());
				}
			}
		}
		try {
			loans.validateThresholds(loanId, form.warnPercent(), form.callPercent());
		}
		catch (LoanValidationException ex) {
			errors.add(ex.getMessage());
		}
		return errors.stream().distinct().toList();
	}

	/**
	 * 儲存這筆質押的擔保股票與門檻，全部在同一個交易：有一檔不合規則就全部不存。
	 * 持股清單上每一檔都呼叫 {@link LoanService#setCollateral}（沒勾＝0，拿掉）；
	 * 門檻只有和目前生效的值不同才呼叫 {@link LoanService#setThresholds}（每呼叫一次就多一個 RateSetting 版本，設計文件 7a-4）。
	 * 不在持股清單上的股票 id（被改過的參數）不理。
	 */
	@Transactional
	public void saveCollateral(Long loanId, List<CollateralPick> picks, BigDecimal warn, BigDecimal call) {
		CollateralPage page = collateralPage(loanId);
		Map<Long, Long> chosen = picked(page, picks);
		for (CollateralPage.Row row : page.rows()) {
			loans.setCollateral(loanId, row.code(), chosen.getOrDefault(row.stockId(), 0L));
		}
		boolean same = warn != null && call != null && warn.compareTo(page.warnPercent()) == 0
				&& call.compareTo(page.callPercent()) == 0;
		if (!same) {
			loans.setThresholds(loanId, warn, call);
		}
	}

	/** 勾了的持股：股票 id → 股數（股數沒填是 null，由 LoanService 回「請輸入擔保股數」）。只認持股清單上的股票。 */
	private static Map<Long, Long> picked(CollateralPage page, List<CollateralPick> picks) {
		Map<Long, Long> chosen = new java.util.HashMap<>();
		picks.stream()
			.filter(Objects::nonNull)
			.filter(p -> p.stockId() != null)
			.filter(p -> page.rows().stream().anyMatch(r -> r.stockId().equals(p.stockId())))
			.forEach(p -> chosen.put(p.stockId(), p.shares()));
		return chosen;
	}

	/** 確認區塊：勾了的每一檔「0050 元大台灣50」→「3,000 股」，沒勾任何一檔列「擔保股票（沒填）」，再列兩個門檻。 */
	@Transactional(readOnly = true)
	public List<ConfirmRow> rowsFor(Long loanId, CollateralForm form) {
		CollateralPage page = collateralPage(loanId);
		Map<Long, Long> chosen = picked(page, form.collateral());
		List<ConfirmRow> rows = new ArrayList<>();
		page.rows()
			.stream()
			.filter(r -> chosen.containsKey(r.stockId()))
			.forEach(r -> rows.add(new ConfirmRow(r.label(), chosen.get(r.stockId()) == null ? ""
					: money.plain(BigDecimal.valueOf(chosen.get(r.stockId()))) + " 股")));
		if (rows.isEmpty()) {
			rows.add(new ConfirmRow("擔保股票", ""));
		}
		rows.add(new ConfirmRow("提醒線（%）", decimal(form.warnPercent())));
		rows.add(new ConfirmRow("追繳線（%）", decimal(form.callPercent())));
		return rows;
	}

	/** 進擔保那一步（設計文件 7a-3）：第一筆還沒設定擔保股票的質押；都設定過就第一筆；沒有質押就到完成頁。 */
	@Transactional(readOnly = true)
	public String collateralEntryUrl() {
		Optional<Long> loan = nextPledgeNeedingCollateral(null).or(() -> pledges().stream().findFirst());
		return loan.map(id -> COLLATERAL_URL + id).orElse("/welcome/done");
	}

	/** afterLoanId 之後（null ＝ 從頭）第一筆還沒有任何擔保股票的質押。 */
	@Transactional(readOnly = true)
	public Optional<Long> nextPledgeNeedingCollateral(Long afterLoanId) {
		return pledges().stream()
			.filter(id -> afterLoanId == null || id > afterLoanId)
			.filter(id -> loans.collateralOf(id).isEmpty())
			.findFirst();
	}

	/** 擔保頁的「下一步」「之後再設定」：下一筆質押，最後一筆之後到完成頁。 */
	@Transactional(readOnly = true)
	public String collateralNextUrl(Long loanId) {
		requireMyPledge(loanId);
		return pledges().stream().filter(id -> id > loanId).findFirst().map(id -> COLLATERAL_URL + id)
			.orElse("/welcome/done");
	}

	/** 擔保頁的「上一步」（設計文件 4）：上一筆質押，第一筆回股票。 */
	@Transactional(readOnly = true)
	public String collateralBackUrl(Long loanId) {
		requireMyPledge(loanId);
		return pledges().stream().filter(id -> id < loanId).reduce((a, b) -> b).map(id -> COLLATERAL_URL + id)
			.orElse(urlOf(OnboardingStep.STOCKS));
	}

	/** 完成頁的「上一步」（設計文件 4）：最後一個擔保頁，沒有質押就回股票。 */
	@Transactional(readOnly = true)
	public String doneBackUrl() {
		return pledges().stream().reduce((a, b) -> b).map(id -> COLLATERAL_URL + id)
			.orElse(urlOf(OnboardingStep.STOCKS));
	}

	/** 步驟列的「4 擔保」算略過：沒有質押，或有質押還沒設定擔保股票（按了「之後再設定」）。不用另外記，由資料推得。 */
	@Transactional(readOnly = true)
	public boolean collateralSkipped() {
		return pledges().isEmpty() || nextPledgeNeedingCollateral(null).isPresent();
	}

	/** 自己沒作廢的股票質押 id，照借款 id。 */
	private List<Long> pledges() {
		return loans.listMine().stream().filter(l -> l.getType() == LoanType.PLEDGE).map(Loan::getId).toList();
	}

	private Loan requireMyPledge(Long loanId) {
		return loans.listMine()
			.stream()
			.filter(l -> l.getId().equals(loanId) && l.getType() == LoanType.PLEDGE)
			.findFirst()
			.orElseThrow(() -> new NotFoundException("找不到這筆質押"));
	}

	// ---------- 確認區塊（設計文件 3.2）：欄位名稱照樣稿，內容轉成畫面看得懂的文字；沒填是空字串 ----------

	/** 帳戶＋初始現金餘額（opening 為 null 表示沒填餘額）。 */
	public List<ConfirmRow> rowsFor(CashAccountForm form, OpeningForm opening) {
		return List.of(new ConfirmRow("名稱", form.name()),
				new ConfirmRow("類型", form.type() == null ? "" : form.type().label()),
				new ConfirmRow("銀行或券商", form.institution()),
				new ConfirmRow("現在的餘額", opening == null ? "" : amount(opening.amount())),
				new ConfirmRow("餘額日期", opening == null ? "" : date(opening.date())));
	}

	/** 借款：只列這個還款方式用得到的欄位（和表單上顯示的欄位一致）。 */
	@Transactional(readOnly = true)
	public List<ConfirmRow> rowsFor(LoanForm form) {
		RepaymentMethod method = form.repaymentMethod();
		List<ConfirmRow> rows = new ArrayList<>();
		rows.add(new ConfirmRow("名稱", form.name()));
		rows.add(new ConfirmRow("銀行", form.bank()));
		rows.add(new ConfirmRow("還款方式", method == null ? "" : method.label()));
		rows.add(new ConfirmRow("股票質押", form.type() == LoanType.PLEDGE ? "是" : "否"));
		rows.add(new ConfirmRow("撥款日", date(form.startDate())));
		rows.add(new ConfirmRow("借款金額", amount(form.startPrincipal())));
		rows.add(new ConfirmRow("年利率（%）", decimal(form.annualRatePercent())));
		if (method == RepaymentMethod.MONTHLY_FIXED) {
			rows.add(new ConfirmRow("每月扣款金額", amount(form.monthlyPayment())));
			rows.add(new ConfirmRow("每月扣款日", number(form.payDay())));
			rows.add(new ConfirmRow("總期數", number(form.remainingPeriods())));
		}
		if (method == RepaymentMethod.INTEREST_ONLY) {
			rows.add(new ConfirmRow("每月繳息日", number(form.payDay())));
		}
		if (method == RepaymentMethod.BULLET || method == RepaymentMethod.INTEREST_ONLY) {
			rows.add(new ConfirmRow("到期日", date(form.dueDate())));
		}
		rows.add(new ConfirmRow("往來帳戶", accountName(form.payAccountId())));
		rows.add(new ConfirmRow("開辦費、手續費", amount(form.fee())));
		if (form.type() == LoanType.PLEDGE) {
			// 每檔一行「質押的股票　00646 3,000 股（2026-07-28，每股 75.15）」（設計文件 2026-11-04-pledged-stocks 4.2）
			form.newPledges().stream().filter(Objects::nonNull).filter(NewPledgeLine::filled)
				.forEach(l -> rows.add(new ConfirmRow("質押的股票", pledgeLine(l))));
		}
		return rows;
	}

	private String pledgeLine(NewPledgeLine l) {
		String shares = l.shares() == null ? "" : money.plain(BigDecimal.valueOf(l.shares()));
		return (l.stockCode() == null ? "" : l.stockCode()) + " " + shares + " 股（" + date(l.date()) + "，每股 "
				+ decimal(l.price()) + "）";
	}

	/** 股票：一次買進列交割戶，目前持有（期初持股）不用；資金來源列借款名稱，沒選是自己的錢。 */
	@Transactional(readOnly = true)
	public List<ConfirmRow> rowsFor(TradeForm form) {
		List<ConfirmRow> rows = new ArrayList<>();
		rows.add(new ConfirmRow("股票代號", form.stockCode()));
		rows.add(new ConfirmRow("記法", kindLabel(form.type())));
		rows.add(new ConfirmRow("日期", date(form.date())));
		rows.add(new ConfirmRow("股數", form.shares() == null ? "" : money.plain(BigDecimal.valueOf(form.shares()))));
		rows.add(new ConfirmRow("價格", decimal(form.price())));
		if (form.type() == TradeType.BUY || form.type() == TradeType.SELL) {
			rows.add(new ConfirmRow("交割戶", accountName(form.accountId())));
		}
		rows.add(new ConfirmRow("資金來源", sources(form.allocations())));
		return rows;
	}

	/**
	 * 引導股票的確認畫面（設計文件 3.4）：欄位同 {@link #rowsFor(TradeForm)}，資金來源改列 checkStock 換算好的每一行
	 * （「玉山質押 4 張（294,419 元）」…「自己的錢 3 張（220,814 元）」），第一行標「資金來源」，其餘標題空白。
	 */
	@Transactional(readOnly = true)
	public List<ConfirmRow> rowsFor(StockInput input) {
		List<ConfirmRow> rows = new ArrayList<>(rowsFor(input.form()));
		rows.remove(rows.size() - 1);
		for (int i = 0; i < input.sourceLines().size(); i++) {
			rows.add(new ConfirmRow(i == 0 ? "資金來源" : "", input.sourceLines().get(i)));
		}
		return rows;
	}

	/** 借款確認畫面的提醒（不擋存檔）：到期日已經過了、合約月付金和銀行扣款差超過 1 元。 */
	@Transactional(readOnly = true)
	public List<String> loanWarnings(LoanForm form) {
		List<String> warnings = new ArrayList<>();
		String due = loans.dueWarning(form);
		if (due != null) {
			warnings.add(due);
		}
		String payment = loans.paymentWarning(form);
		if (payment != null) {
			warnings.add(payment);
		}
		return warnings;
	}

	private static String kindLabel(TradeType type) {
		if (type == null) {
			return "";
		}
		return switch (type) {
			case BUY -> "一次買進";
			case OPENING -> "目前持有";
			default -> type.label();
		};
	}

	private String sources(List<AllocationLine> lines) {
		List<AllocationLine> used = lines.stream()
			.filter(Objects::nonNull)
			.filter(l -> l.loanId() != null && (l.amount() == null || l.amount().signum() != 0))
			.toList();
		if (used.isEmpty()) {
			return "自己的錢";
		}
		Map<Long, String> names = loans.listMine().stream().collect(Collectors.toMap(Loan::getId, Loan::getName));
		return used.stream()
			.map(l -> names.getOrDefault(l.loanId(), "") + (l.amount() == null ? "" : " " + money.plain(l.amount()) + " 元"))
			.collect(Collectors.joining("、"));
	}

	/** 自己的帳戶顯示名稱；沒選或不是自己的顯示沒填（存檔時 Service 會擋）。 */
	private String accountName(Long id) {
		if (id == null) {
			return "";
		}
		return accounts.listMine().stream()
			.filter(a -> a.getId().equals(id))
			.map(CashAccount::getName)
			.findFirst()
			.orElse("");
	}

	private String amount(BigDecimal value) {
		return value == null ? "" : money.plain(value);
	}

	/** 利率、股價：照填的數字，去掉多餘的 0（73.50 → 73.5）。 */
	private static String decimal(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}

	private static String number(Integer value) {
		return value == null ? "" : value.toString();
	}

	private static String date(LocalDate value) {
		return value == null ? "" : value.toString();
	}

	private AppUser me() {
		return users.findById(currentUser.id()).orElseThrow(() -> new NotFoundException("找不到使用者"));
	}

}
