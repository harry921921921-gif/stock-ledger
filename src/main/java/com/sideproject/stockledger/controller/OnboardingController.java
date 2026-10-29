package com.sideproject.stockledger.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sideproject.stockledger.dto.AccountInput;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.CollateralForm;
import com.sideproject.stockledger.dto.ConfirmRow;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.LoanInput;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.StockInput;
import com.sideproject.stockledger.dto.StockSourceInput;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.OnboardingStep;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.service.LoanShortfallException;
import com.sideproject.stockledger.service.NotFoundException;
import com.sideproject.stockledger.service.OnboardingService;

/**
 * 第一次使用引導（設計文件 2026-10-25-onboarding 3）。只負責綁定、呼叫 Service、畫面與轉址；
 * 規則（必填、餘額選填、能不能往下走）都在 {@link OnboardingService}。引導完成後所有 /welcome/** 一律轉回總覽。
 */
@Controller
public class OnboardingController {

	private static final String HOME = "redirect:/";

	/** 有表單、會留草稿的步驟；網址 /welcome/{step} 的 step 也是草稿的 formKey。擔保頁（collateral-{loanId}）有自己的 go。 */
	private static final Set<String> DRAFT_STEPS = Set.of("accounts", "loans", "stocks");

	/**
	 * 「上一步」「下一步」「先離開」能去的地方（固定，設計文件 4）。只接受這幾個名字，
	 * 避免 to 被改成外部網址而轉到別的網站。
	 */
	private static final Map<String, String> DESTINATIONS = Map.of(
			"welcome", "/welcome",
			"accounts", "/welcome/accounts",
			"loans", "/welcome/loans",
			"stocks", "/welcome/stocks",
			"collateral", "/welcome/collateral",
			"done", "/welcome/done",
			"home", "/");

	/**
	 * 「確認儲存」的代號已經用過或失效（按兩次、按上一頁再送）：什麼都不存。用字比照其他頁的
	 * 「這個表單已經送出過或已失效，沒有重複新增；請在下方確認」，引導的已儲存清單在表單上方、而且也有修改。
	 */
	static final String ALREADY_SUBMITTED = "這個表單已經送出過或已失效，沒有重複儲存；請在上方「已儲存」確認";

	static final String CONFIRM_EXPIRED = "確認畫面已過期，請再按一次儲存";

	private static final String ACCOUNTS = "accounts";

	private static final String ACCOUNTS_URL = "/welcome/accounts";

	private static final String LOANS = "loans";

	private static final String LOANS_URL = "/welcome/loans";

	private static final String STOCKS = "stocks";

	private static final String STOCKS_URL = "/welcome/stocks";

	/** 股票頁「股票記完了，下一步 →」的 to：去處依有沒有質押借款而定（{@link OnboardingService#afterStocks}）。 */
	private static final String NEXT = "next";

	private final OnboardingService onboarding;
	private final OnboardingDrafts drafts;
	private final DraftBinder draftBinder;
	private final FormTokens tokens;

	public OnboardingController(OnboardingService onboarding, OnboardingDrafts drafts, DraftBinder draftBinder,
			FormTokens tokens) {
		this.onboarding = onboarding;
		this.drafts = drafts;
		this.draftBinder = draftBinder;
		this.tokens = tokens;
	}

	@GetMapping("/welcome")
	public String welcome(Model model) {
		return unlessDone(() -> {
			onboarding.reach(OnboardingStep.WELCOME);
			model.addAttribute("stepNumber", 0);
			return "onboarding/welcome";
		});
	}

	@GetMapping("/welcome/done")
	public String done(Model model) {
		return unlessDone(() -> {
			model.addAttribute("stepNumber", 5);
			model.addAttribute("summary", onboarding.summary());
			model.addAttribute("backUrl", onboarding.doneBackUrl()); // 最後一個擔保頁，沒有質押回股票（設計文件 4）
			model.addAttribute("skipped", onboarding.collateralSkipped() ? List.of(4) : List.of());
			return "onboarding/done";
		});
	}

	/**
	 * 「上一步」「下一步」「先離開」「回去修改」：同一個表單裡的按鈕（formaction）送到這裡。只把表單內容存成草稿，
	 * 不驗證、不建立資料，再轉到目的地（設計文件 3.2、4）。不認得的目的地回到同一頁。
	 * 確認區塊顯示時欄位是鎖住的（不會送出），這時存的是確認區塊收起來的那份內容（{@link #formParams}）。
	 */
	@PostMapping("/welcome/{step}/go")
	public String go(@PathVariable String step, @RequestParam(required = false) String to,
			HttpServletRequest request, HttpSession session) {
		if (!DRAFT_STEPS.contains(step)) {
			throw new NotFoundException("找不到這一步");
		}
		return unlessDone(() -> {
			drafts.save(session, step, formParams(request, session, step));
			if (!mayLeave(step, to)) {
				return "redirect:/welcome/" + step;
			}
			if (STOCKS.equals(step) && NEXT.equals(to)) {
				return "redirect:" + onboarding.afterStocks(); // 有質押 → 擔保，沒有 → 完成頁（Service 決定）
			}
			if ("home".equals(to)) {
				onboarding.markLeft(session); // 「先離開」：這個 session 裡 GET / 不再轉回引導
			}
			return "redirect:" + DESTINATIONS.getOrDefault(to == null ? "" : to, "/welcome/" + step);
		});
	}

	/**
	 * 往下走的條件（伺服器再檢查一次，按鈕 disabled 只是畫面）：帳戶頁至少已儲存一個證券交割戶才能到借款；
	 * 不符合就回到同一頁，頁面上會顯示提示。上一步、先離開不受限制。
	 */
	private boolean mayLeave(String step, String to) {
		if (ACCOUNTS.equals(step) && "loans".equals(to)) {
			return onboarding.hasSettlementAccount();
		}
		return true;
	}

	// ---------- 1 帳戶（設計文件 3、3.2、4）----------
	// 同一頁有三種狀態：填新的一筆、修改已儲存的一筆（editingId）、儲存前的確認區塊（confirm）。
	// 「儲存」POST preview：綁定、檢查、列出確認內容，並把這份內容依一次性代號收起來；
	// 「確認儲存」POST save：只存收起來的那份，不看送出時表單上的值（確認過什麼就存什麼）。

	@GetMapping(ACCOUNTS_URL)
	public String accounts(HttpSession session, Model model) {
		return unlessDone(() -> {
			onboarding.reach(OnboardingStep.ACCOUNTS);
			Optional<Map<String, String[]>> draft = drafts.load(session, ACCOUNTS);
			// 修改中按了上一步／下一步再回來：回到修改那一筆
			Optional<Long> editing = draft.flatMap(OnboardingDrafts::editingId).filter(onboarding::isMyAccount);
			if (editing.isPresent()) {
				return "redirect:" + accountUrl(editing.get()) + "/edit";
			}
			if (draft.isPresent()) {
				bindAccountDraft(model, draft.get());
			}
			else {
				model.addAttribute("form", CashAccountForm.empty());
				model.addAttribute("opening", onboarding.blankOpening());
			}
			return accountsPage(model, null);
		});
	}

	@PostMapping(ACCOUNTS_URL + "/preview")
	public String previewNewAccount(@Valid @ModelAttribute("form") CashAccountForm form, BindingResult formResult,
			@ModelAttribute("opening") OpeningForm opening, BindingResult openingResult, HttpServletRequest request,
			HttpSession session, Model model) {
		return unlessDone(() -> previewAccount(null, onboarding.checkAccount(null, form, formResult, opening,
				openingResult), request, session, model));
	}

	@PostMapping(ACCOUNTS_URL + "/save")
	public String saveNewAccount(@RequestParam(required = false) String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		return unlessDone(() -> saveAccount(null, formToken, session, model, redirect));
	}

	/** 已儲存清單的「修改」：先把打到一半的新帳戶收起來，再到修改畫面。 */
	@PostMapping(ACCOUNTS_URL + "/{id}/edit")
	public String startEditAccount(@PathVariable Long id, HttpServletRequest request, HttpSession session) {
		return unlessDone(() -> {
			onboarding.accountForm(id); // 別人的或不存在的帳戶 → 404
			drafts.beginEdit(session, ACCOUNTS, formParams(request, session, ACCOUNTS));
			return "redirect:" + accountUrl(id) + "/edit";
		});
	}

	/**
	 * 修改畫面：有這一筆改到一半的草稿就放草稿，否則放資料庫裡的內容。
	 * 直接打開這個網址（書籤、上一頁）時表單上可能還有打到一半的新帳戶：一樣先收起來，改完或取消後放回去。
	 */
	@GetMapping(ACCOUNTS_URL + "/{id}/edit")
	public String editAccount(@PathVariable Long id, HttpSession session, Model model) {
		return unlessDone(() -> {
			onboarding.reach(OnboardingStep.ACCOUNTS);
			CashAccountForm saved = onboarding.accountForm(id);
			Optional<Map<String, String[]>> draft = drafts.load(session, ACCOUNTS);
			if (draft.isPresent() && OnboardingDrafts.editingId(draft.get()).filter(id::equals).isPresent()) {
				bindAccountDraft(model, draft.get());
			}
			else {
				draft.ifPresent(p -> drafts.beginEdit(session, ACCOUNTS, p));
				model.addAttribute("form", saved);
				model.addAttribute("opening", onboarding.openingFormOf(id));
			}
			return accountsPage(model, id);
		});
	}

	@PostMapping(ACCOUNTS_URL + "/{id}/preview")
	public String previewEditAccount(@PathVariable Long id, @Valid @ModelAttribute("form") CashAccountForm form,
			BindingResult formResult, @ModelAttribute("opening") OpeningForm opening, BindingResult openingResult,
			HttpServletRequest request, HttpSession session, Model model) {
		return unlessDone(() -> {
			onboarding.accountForm(id);
			return previewAccount(id, onboarding.checkAccount(id, form, formResult, opening, openingResult), request,
					session, model);
		});
	}

	@PostMapping(ACCOUNTS_URL + "/{id}/save")
	public String saveEditAccount(@PathVariable Long id, @RequestParam(required = false) String formToken,
			HttpSession session, Model model, RedirectAttributes redirect) {
		return unlessDone(() -> {
			onboarding.accountForm(id);
			return saveAccount(id, formToken, session, model, redirect);
		});
	}

	/** 「取消修改」：資料庫不變，把收起來的新帳戶放回表單。 */
	@PostMapping(ACCOUNTS_URL + "/cancel-edit")
	public String cancelEditAccount(HttpSession session) {
		return unlessDone(() -> {
			drafts.endEdit(session, ACCOUNTS);
			return "redirect:" + ACCOUNTS_URL;
		});
	}

	private String previewAccount(Long id, AccountInput input, HttpServletRequest request, HttpSession session,
			Model model) {
		String token = input.ok() ? confirmToken(request, session, OnboardingDrafts.target(ACCOUNTS, id, null)) : null;
		List<ConfirmRow> rows = input.ok() ? onboarding.rowsFor(input.form(), input.opening()) : List.of();
		showConfirm(model, ACCOUNTS, id == null ? "帳戶" : "修改", rows, List.of(), input.errors(),
				accountUrl(id) + "/save", token);
		return accountsPage(model, id);
	}

	/**
	 * 「確認儲存」：先用掉代號（按兩次時第二次直接回清單），再拿出預覽時收起來的內容，重新綁定、檢查後存檔。
	 * 收起來的內容不見了（過期）就什麼都不存，回到表單請使用者再按一次儲存。
	 * 存檔時才發現的錯誤（例如另一個分頁剛存了同名帳戶）顯示錯誤畫面，打的字都還在。
	 */
	private String saveAccount(Long id, String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("notice", ALREADY_SUBMITTED);
			return "redirect:" + ACCOUNTS_URL;
		}
		Optional<Map<String, String[]>> confirmed = drafts.takeConfirmed(session, formToken,
				OnboardingDrafts.target(ACCOUNTS, id, null));
		if (confirmed.isEmpty()) {
			redirect.addFlashAttribute("notice", CONFIRM_EXPIRED);
			return "redirect:" + (id == null ? ACCOUNTS_URL : accountUrl(id) + "/edit");
		}
		BindingResult formResult = draftBinder.bind("form", CashAccountForm.class, confirmed.get(), true);
		BindingResult openingResult = draftBinder.bind("opening", OpeningForm.class, confirmed.get(), false);
		AccountInput input = onboarding.checkAccount(id, (CashAccountForm) formResult.getTarget(), formResult,
				(OpeningForm) openingResult.getTarget(), openingResult);
		List<String> errors = input.errors();
		if (input.ok()) {
			try {
				if (id == null) {
					onboarding.addAccount(input.form(), input.opening());
				}
				else {
					onboarding.editAccount(id, input.form(), input.opening());
				}
			}
			catch (IllegalArgumentException ex) { // 含 CashValidationException
				errors = List.of(ex.getMessage());
			}
		}
		if (!errors.isEmpty()) {
			DraftBinder.put(model, formResult);
			DraftBinder.put(model, openingResult);
			showConfirm(model, ACCOUNTS, id == null ? "帳戶" : "修改", List.of(), List.of(), errors,
					accountUrl(id) + "/save", null);
			return accountsPage(model, id);
		}
		if (id == null) {
			drafts.clear(session, ACCOUNTS);
		}
		else {
			drafts.endEdit(session, ACCOUNTS);
		}
		return "redirect:" + ACCOUNTS_URL;
	}

	private void bindAccountDraft(Model model, Map<String, String[]> draft) {
		draftBinder.bindInto(model, "form", CashAccountForm.class, draft);
		draftBinder.bindInto(model, "opening", OpeningForm.class, draft);
	}

	private static String accountUrl(Long id) {
		return id == null ? ACCOUNTS_URL : ACCOUNTS_URL + "/" + id;
	}

	private String accountsPage(Model model, Long editingId) {
		model.addAttribute("stepNumber", 1);
		model.addAttribute("types", CashAccountType.values());
		model.addAttribute("saved", onboarding.savedAccounts());
		model.addAttribute("editingId", editingId);
		model.addAttribute("canGoNext", onboarding.hasSettlementAccount());
		model.addAttribute("goUrl", ACCOUNTS_URL + "/go");
		model.addAttribute("previewUrl", accountUrl(editingId) + "/preview");
		if (!model.containsAttribute("confirm")) {
			model.addAttribute("confirm", false);
			model.addAttribute("locked", false);
		}
		return "onboarding/accounts";
	}

	// ---------- 2 借款（設計文件 3、3.2、4、7a-1、7a-6）----------
	// 還沒有借款、也還沒按過「有」：先問「你有拿去投資的借款嗎？」；按了「有」（或已經有草稿、已經存過借款）就是清單＋表單。
	// 表單、確認區塊、修改的做法和帳戶這一步一樣。修改遇到借款改小、不夠付已經用它買的股票（LoanShortfallException）時，
	// 先列出要調整的交易（和資金來源頁同一個 fragment），按「確定修改」才存。

	/** 按過「有，我要記借款」的記號（session 裡，跟草稿放一起；按「沒有，略過」時清掉）。 */
	private static final String WANTS_LOANS = "loans:wanted";

	private static final String SHORTFALL = "shortfall";

	@GetMapping(LOANS_URL)
	public String loans(HttpSession session, Model model) {
		return unlessDone(() -> {
			onboarding.reach(OnboardingStep.LOANS);
			Optional<Map<String, String[]>> draft = drafts.load(session, LOANS);
			if (draft.isEmpty() && drafts.load(session, WANTS_LOANS).isEmpty() && onboarding.savedLoans().isEmpty()) {
				model.addAttribute("stepNumber", 2);
				return "onboarding/loans-ask";
			}
			return newLoanPage(draft, model);
		});
	}

	/** 「有，我要記借款」：記住這個選擇（回到 /welcome/loans 直接是表單），顯示表單。 */
	@GetMapping(LOANS_URL + "/form")
	public String wantLoans(HttpSession session, Model model) {
		return unlessDone(() -> {
			onboarding.reach(OnboardingStep.LOANS);
			drafts.save(session, WANTS_LOANS, Map.of());
			return newLoanPage(drafts.load(session, LOANS), model);
		});
	}

	/** 「沒有，略過」：記到股票那一步，轉到股票頁。 */
	@PostMapping(LOANS_URL + "/none")
	public String noLoans(HttpSession session) {
		return unlessDone(() -> {
			drafts.clear(session, WANTS_LOANS);
			onboarding.reach(OnboardingStep.STOCKS);
			return "redirect:" + onboarding.urlOf(OnboardingStep.STOCKS);
		});
	}

	private String newLoanPage(Optional<Map<String, String[]>> draft, Model model) {
		// 修改中按了上一步／下一步再回來：回到修改那一筆
		Optional<Long> editing = draft.flatMap(OnboardingDrafts::editingId).filter(onboarding::isMyLoan);
		if (editing.isPresent()) {
			return "redirect:" + loanUrl(editing.get()) + "/edit";
		}
		if (draft.isPresent()) {
			DraftBinder.put(model, bindLoan(draft.get(), false));
		}
		else {
			model.addAttribute("form", onboarding.blankLoan());
		}
		return loansPage(model, null);
	}

	@PostMapping(LOANS_URL + "/preview")
	public String previewNewLoan(HttpServletRequest request, HttpSession session, Model model) {
		return unlessDone(() -> previewLoan(null, request, session, model));
	}

	@PostMapping(LOANS_URL + "/save")
	public String saveNewLoan(@RequestParam(required = false) String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		return unlessDone(() -> saveLoan(null, formToken, false, session, model, redirect));
	}

	/** 已儲存清單的「修改」：先把打到一半的新借款收起來，再到修改畫面。 */
	@PostMapping(LOANS_URL + "/{id}/edit")
	public String startEditLoan(@PathVariable Long id, HttpServletRequest request, HttpSession session) {
		return unlessDone(() -> {
			onboarding.loanForm(id); // 別人的、不存在或已作廢的借款 → 404
			drafts.beginEdit(session, LOANS, formParams(request, session, LOANS));
			return "redirect:" + loanUrl(id) + "/edit";
		});
	}

	/** 修改畫面：有這一筆改到一半的草稿就放草稿，否則放資料庫裡的內容（打到一半的新借款先收起來）。 */
	@GetMapping(LOANS_URL + "/{id}/edit")
	public String editLoan(@PathVariable Long id, HttpSession session, Model model) {
		return unlessDone(() -> {
			onboarding.reach(OnboardingStep.LOANS);
			LoanForm saved = onboarding.loanForm(id);
			Optional<Map<String, String[]>> draft = drafts.load(session, LOANS);
			if (draft.isPresent() && OnboardingDrafts.editingId(draft.get()).filter(id::equals).isPresent()) {
				DraftBinder.put(model, bindLoan(draft.get(), false));
			}
			else {
				draft.ifPresent(p -> drafts.beginEdit(session, LOANS, p));
				model.addAttribute("form", saved);
			}
			return loansPage(model, id);
		});
	}

	@PostMapping(LOANS_URL + "/{id}/preview")
	public String previewEditLoan(@PathVariable Long id, HttpServletRequest request, HttpSession session,
			Model model) {
		return unlessDone(() -> {
			onboarding.loanForm(id);
			return previewLoan(id, request, session, model);
		});
	}

	@PostMapping(LOANS_URL + "/{id}/save")
	public String saveEditLoan(@PathVariable Long id, @RequestParam(required = false) String formToken,
			HttpSession session, Model model, RedirectAttributes redirect) {
		return unlessDone(() -> {
			onboarding.loanForm(id);
			return saveLoan(id, formToken, false, session, model, redirect);
		});
	}

	/** 借款改小、不夠付已經用它買的股票：使用者看過要調整的交易，按「確定修改」。 */
	@PostMapping(LOANS_URL + "/{id}/confirm-shortfall")
	public String confirmLoanShortfall(@PathVariable Long id, @RequestParam(required = false) String formToken,
			HttpSession session, Model model, RedirectAttributes redirect) {
		return unlessDone(() -> {
			onboarding.loanForm(id);
			return saveLoan(id, formToken, true, session, model, redirect);
		});
	}

	@PostMapping(LOANS_URL + "/cancel-edit")
	public String cancelEditLoan(HttpSession session) {
		return unlessDone(() -> {
			drafts.endEdit(session, LOANS);
			return "redirect:" + LOANS_URL;
		});
	}

	private String previewLoan(Long id, HttpServletRequest request, HttpSession session, Model model) {
		BindingResult result = bindLoan(request.getParameterMap(), true);
		LoanInput input = onboarding.checkLoan((LoanForm) result.getTarget(), result);
		DraftBinder.put(model, result);
		String token = input.ok() ? confirmToken(request, session, OnboardingDrafts.target(LOANS, id, null)) : null;
		List<ConfirmRow> rows = input.ok() ? onboarding.rowsFor(input.form()) : List.of();
		showConfirm(model, LOANS, id == null ? "借款" : "修改", rows, input.warnings(), input.errors(),
				loanUrl(id) + "/save", token);
		return loansPage(model, id);
	}

	/**
	 * 「確認儲存」與「確定修改」：順序和帳戶一樣（用掉代號 → 拿出收起來的內容 → 綁定、檢查 → 存檔）。
	 * 修改時借款改小、不夠付已經用它買的股票：不存，把同一份內容再收起來（只給 confirm-shortfall 用），畫出要調整的交易。
	 */
	private String saveLoan(Long id, String formToken, boolean confirmShortfall, HttpSession session, Model model,
			RedirectAttributes redirect) {
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("notice", ALREADY_SUBMITTED);
			return "redirect:" + LOANS_URL;
		}
		Optional<Map<String, String[]>> confirmed = drafts.takeConfirmed(session, formToken,
				OnboardingDrafts.target(LOANS, id, confirmShortfall ? SHORTFALL : null));
		if (confirmed.isEmpty()) {
			redirect.addFlashAttribute("notice", CONFIRM_EXPIRED);
			return "redirect:" + (id == null ? LOANS_URL : loanUrl(id) + "/edit");
		}
		BindingResult result = bindLoan(confirmed.get(), true);
		LoanInput input = onboarding.checkLoan((LoanForm) result.getTarget(), result);
		List<String> errors = input.errors();
		if (input.ok()) {
			try {
				if (id == null) {
					onboarding.addLoan(input.form());
				}
				else {
					onboarding.editLoan(id, input.form(), confirmShortfall);
				}
			}
			catch (LoanShortfallException ex) {
				DraftBinder.put(model, result);
				model.addAttribute("shortfall", true);
				model.addAttribute("locked", true);
				model.addAttribute("confirm", false);
				model.addAttribute("shortfallMessage", ex.getMessage());
				model.addAttribute("cuts", ex.cuts());
				model.addAttribute("shortfallUrl", loanUrl(id) + "/confirm-shortfall");
				model.addAttribute("formToken",
						stash(session, OnboardingDrafts.target(LOANS, id, SHORTFALL), confirmed.get()));
				return loansPage(model, id);
			}
			catch (IllegalArgumentException ex) { // 含 LoanValidationException
				errors = List.of(ex.getMessage());
			}
		}
		if (!errors.isEmpty()) {
			DraftBinder.put(model, result);
			showConfirm(model, LOANS, id == null ? "借款" : "修改", List.of(), List.of(), errors,
					loanUrl(id) + "/save", null);
			return loansPage(model, id);
		}
		if (id == null) {
			drafts.clear(session, LOANS);
		}
		else {
			drafts.endEdit(session, LOANS);
		}
		return "redirect:" + LOANS_URL;
	}

	/**
	 * 把表單參數綁成 LoanForm。「這是股票質押」勾選框決定類型（和資金來源頁一樣，DR-048）：勾了是股票質押，沒勾是信貸，
	 * 不看參數裡的 type。
	 */
	private BindingResult bindLoan(Map<String, String[]> params, boolean validate) {
		Map<String, String[]> copy = new LinkedHashMap<>(params);
		String[] pledge = params.get("pledge");
		boolean pledged = pledge != null && pledge.length > 0 && Boolean.parseBoolean(pledge[0]);
		copy.put("type", new String[] { (pledged ? LoanType.PLEDGE : LoanType.CREDIT).name() });
		return draftBinder.bind("form", LoanForm.class, copy, validate);
	}

	private static String loanUrl(Long id) {
		return id == null ? LOANS_URL : LOANS_URL + "/" + id;
	}

	private String loansPage(Model model, Long editingId) {
		model.addAttribute("stepNumber", 2);
		model.addAttribute("saved", onboarding.savedLoans());
		model.addAttribute("editingId", editingId);
		model.addAttribute("myAccounts", onboarding.myAccounts());
		model.addAttribute("repaymentMethods", RepaymentMethod.values());
		model.addAttribute("goUrl", LOANS_URL + "/go");
		model.addAttribute("previewUrl", loanUrl(editingId) + "/preview");
		if (!model.containsAttribute("confirm")) {
			model.addAttribute("confirm", false);
			model.addAttribute("locked", false);
		}
		if (!model.containsAttribute("shortfall")) {
			model.addAttribute("shortfall", false);
		}
		return "onboarding/loans";
	}

	// ---------- 3 股票（設計文件 3、3.1、3.2、4）----------
	// 做法和帳戶、借款一樣。記法二選一：一次買進（BUY）、目前持有（OPENING，期初持股）。資金來源是表格（設計文件 3.4）：
	// 每列一筆借款和張數（或金額），表單參數 sourceMode、sources[i].*，綁成 StockSourceInput 放在 model 的 sources；
	// 換算成每筆借款出的錢在 OnboardingService.checkStock。加列、拿掉、切換模式見 changeSources()。「股票記完了，下一步」見 go()。

	@GetMapping(STOCKS_URL)
	public String stocks(HttpSession session, Model model) {
		return unlessDone(() -> {
			onboarding.reach(OnboardingStep.STOCKS);
			Optional<Map<String, String[]>> draft = drafts.load(session, STOCKS);
			// 修改中按了上一步／下一步再回來：回到修改那一筆
			Optional<Long> editing = draft.flatMap(OnboardingDrafts::editingId).filter(onboarding::isMyStock);
			if (editing.isPresent()) {
				return "redirect:" + stockUrl(editing.get()) + "/edit";
			}
			if (draft.isPresent()) {
				DraftBinder.put(model, bindStock(draft.get()));
				model.addAttribute("sources", bindSources(draft.get()));
			}
			else {
				model.addAttribute("form", onboarding.blankStock());
			}
			return stocksPage(model, null);
		});
	}

	@PostMapping(STOCKS_URL + "/preview")
	public String previewNewStock(HttpServletRequest request, HttpSession session, Model model) {
		return unlessDone(() -> previewStock(null, request, session, model));
	}

	@PostMapping(STOCKS_URL + "/save")
	public String saveNewStock(@RequestParam(required = false) String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		return unlessDone(() -> saveStock(null, formToken, session, model, redirect));
	}

	/** 已儲存清單的「修改」：先把打到一半的新股票收起來，再到修改畫面。 */
	@PostMapping(STOCKS_URL + "/{id}/edit")
	public String startEditStock(@PathVariable Long id, HttpServletRequest request, HttpSession session) {
		return unlessDone(() -> {
			onboarding.stockForm(id); // 別人的、不存在或已作廢的 → 404
			drafts.beginEdit(session, STOCKS, formParams(request, session, STOCKS));
			return "redirect:" + stockUrl(id) + "/edit";
		});
	}

	/** 修改畫面：有這一筆改到一半的草稿就放草稿，否則放資料庫裡的內容（打到一半的新股票先收起來）。 */
	@GetMapping(STOCKS_URL + "/{id}/edit")
	public String editStock(@PathVariable Long id, HttpSession session, Model model) {
		return unlessDone(() -> {
			onboarding.reach(OnboardingStep.STOCKS);
			TradeForm saved = onboarding.stockForm(id);
			Optional<Map<String, String[]>> draft = drafts.load(session, STOCKS);
			if (draft.isPresent() && OnboardingDrafts.editingId(draft.get()).filter(id::equals).isPresent()) {
				DraftBinder.put(model, bindStock(draft.get()));
				model.addAttribute("sources", bindSources(draft.get()));
			}
			else {
				draft.ifPresent(p -> drafts.beginEdit(session, STOCKS, p));
				model.addAttribute("form", saved);
				model.addAttribute("sources", onboarding.stockSources(id)); // 換得回整數張用張數，否則填金額
			}
			return stocksPage(model, id);
		});
	}

	@PostMapping(STOCKS_URL + "/{id}/preview")
	public String previewEditStock(@PathVariable Long id, HttpServletRequest request, HttpSession session,
			Model model) {
		return unlessDone(() -> {
			onboarding.stockForm(id);
			return previewStock(id, request, session, model);
		});
	}

	@PostMapping(STOCKS_URL + "/{id}/save")
	public String saveEditStock(@PathVariable Long id, @RequestParam(required = false) String formToken,
			HttpSession session, Model model, RedirectAttributes redirect) {
		return unlessDone(() -> {
			onboarding.stockForm(id);
			return saveStock(id, formToken, session, model, redirect);
		});
	}

	@PostMapping(STOCKS_URL + "/cancel-edit")
	public String cancelEditStock(HttpSession session) {
		return unlessDone(() -> {
			drafts.endEdit(session, STOCKS);
			return "redirect:" + STOCKS_URL;
		});
	}

	/**
	 * 資金來源表格的「＋ 加一筆借款」（add）、「拿掉」（remove/{index}）、「改填金額／改填張數」（mode）：
	 * 同一個表單的按鈕（formaction）送到這裡，不用 JavaScript。照 go() 先拿這次的表單內容，由 Service 改列或換算，
	 * 存成草稿再轉回同一頁（新增或修改中的那一筆），打到一半的字都在。換不回整數張時 Service 給的話顯示在頁面上方。
	 */
	@PostMapping({ STOCKS_URL + "/sources/{action}", STOCKS_URL + "/sources/{action}/{index}" })
	public String changeSources(@PathVariable String action, @PathVariable(required = false) Integer index,
			HttpServletRequest request, HttpSession session, RedirectAttributes redirect) {
		return unlessDone(() -> {
			Map<String, String[]> params = formParams(request, session, STOCKS);
			Optional<Long> editing = OnboardingDrafts.editingId(params).filter(onboarding::isMyStock);
			StockSourceInput.Change change = onboarding.changeSources(editing.orElse(null),
					(TradeForm) bindStock(params).getTarget(), bindSources(params), action, index);
			drafts.save(session, STOCKS, withSources(params, change.sources()));
			if (change.notice() != null) {
				redirect.addFlashAttribute("notice", change.notice());
			}
			return "redirect:" + editing.map(id -> stockUrl(id) + "/edit").orElse(STOCKS_URL);
		});
	}

	/** 草稿換上新的表格：拿掉原本的 sourceMode、sources[*]，照新的列重新編號（索引連續）。 */
	private static Map<String, String[]> withSources(Map<String, String[]> params, StockSourceInput sources) {
		Map<String, String[]> copy = new LinkedHashMap<>();
		params.forEach((name, values) -> {
			if (!name.equals("sourceMode") && !name.startsWith("sources[")) {
				copy.put(name, values);
			}
		});
		copy.put("sourceMode", new String[] { sources.sourceMode().name() });
		List<StockSourceInput.Row> rows = sources.sources();
		for (int i = 0; i < rows.size(); i++) {
			Long loanId = rows.get(i).loanId();
			copy.put("sources[" + i + "].loanId", new String[] { loanId == null ? "" : loanId.toString() });
			copy.put("sources[" + i + "].value", new String[] { rows.get(i).value() });
		}
		return copy;
	}

	private String previewStock(Long id, HttpServletRequest request, HttpSession session, Model model) {
		BindingResult result = bindStock(request.getParameterMap());
		StockSourceInput sources = bindSources(request.getParameterMap());
		StockInput input = onboarding.checkStock(id, (TradeForm) result.getTarget(), result, sources);
		DraftBinder.put(model, result);
		model.addAttribute("sources", sources);
		String token = input.ok() ? confirmToken(request, session, OnboardingDrafts.target(STOCKS, id, null)) : null;
		List<ConfirmRow> rows = input.ok() ? onboarding.rowsFor(input) : List.of();
		showConfirm(model, STOCKS, id == null ? "股票" : "修改", rows, List.of(), input.errors(),
				stockUrl(id) + "/save", token);
		return stocksPage(model, id);
	}

	/** 「確認儲存」：順序和帳戶、借款一樣（用掉代號 → 拿出收起來的內容 → 綁定、檢查 → 存檔）。 */
	private String saveStock(Long id, String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("notice", ALREADY_SUBMITTED);
			return "redirect:" + STOCKS_URL;
		}
		Optional<Map<String, String[]>> confirmed = drafts.takeConfirmed(session, formToken,
				OnboardingDrafts.target(STOCKS, id, null));
		if (confirmed.isEmpty()) {
			redirect.addFlashAttribute("notice", CONFIRM_EXPIRED);
			return "redirect:" + (id == null ? STOCKS_URL : stockUrl(id) + "/edit");
		}
		BindingResult result = bindStock(confirmed.get());
		StockSourceInput sources = bindSources(confirmed.get());
		StockInput input = onboarding.checkStock(id, (TradeForm) result.getTarget(), result, sources);
		List<String> errors = input.errors();
		if (input.ok()) {
			try {
				if (id == null) {
					onboarding.addStock(input.form());
				}
				else {
					onboarding.editStock(id, input.form());
				}
			}
			catch (IllegalArgumentException ex) { // 含 TradeValidationException
				errors = List.of(ex.getMessage());
			}
		}
		if (!errors.isEmpty()) {
			DraftBinder.put(model, result);
			model.addAttribute("sources", sources);
			showConfirm(model, STOCKS, id == null ? "股票" : "修改", List.of(), List.of(), errors,
					stockUrl(id) + "/save", null);
			return stocksPage(model, id);
		}
		if (id == null) {
			drafts.clear(session, STOCKS);
		}
		else {
			drafts.endEdit(session, STOCKS);
		}
		return "redirect:" + STOCKS_URL;
	}

	private BindingResult bindStock(Map<String, String[]> params) {
		return draftBinder.bind("form", TradeForm.class, params, false);
	}

	/** 資金來源表格；參數被改壞（例如借款 id 不是數字）時盡量綁，綁不出來就是空表格（全部自己的錢）。 */
	private StockSourceInput bindSources(Map<String, String[]> params) {
		Object sources = draftBinder.bind("sources", StockSourceInput.class, params, false).getTarget();
		return sources instanceof StockSourceInput s ? s : StockSourceInput.none();
	}

	private static String stockUrl(Long id) {
		return id == null ? STOCKS_URL : STOCKS_URL + "/" + id;
	}

	private String stocksPage(Model model, Long editingId) {
		model.addAttribute("stepNumber", 3);
		model.addAttribute("saved", onboarding.savedStocks());
		model.addAttribute("editingId", editingId);
		model.addAttribute("kinds", List.of(TradeType.BUY, TradeType.OPENING));
		model.addAttribute("settlementAccounts", onboarding.settlementAccounts());
		model.addAttribute("loans", onboarding.savedLoans());
		// 資金來源表格的換算金額與自己的錢（Service 算好）；model 裡沒有表格就是空的（全部自己的錢）
		StockSourceInput sources = model.getAttribute("sources") instanceof StockSourceInput s ? s
				: StockSourceInput.none();
		model.addAttribute("sources", sources);
		model.addAttribute("sourceTable", onboarding.sourceTable(editingId,
				model.getAttribute("form") instanceof TradeForm f ? f : null, sources));
		// 修改的那筆記在不是交割戶的帳戶，引導的下拉表示不了：只顯示說明，存檔時沿用原本的帳戶
		model.addAttribute("accountNote", editingId == null ? null : onboarding.stockAccountNote(editingId));
		model.addAttribute("goUrl", STOCKS_URL + "/go");
		model.addAttribute("previewUrl", stockUrl(editingId) + "/preview");
		if (!model.containsAttribute("confirm")) {
			model.addAttribute("confirm", false);
			model.addAttribute("locked", false);
		}
		return "onboarding/stocks";
	}

	// ---------- 4 擔保（設計文件 3、4、7a-3、7a-4）----------
	// 每筆質押一頁 /welcome/collateral/{loanId}，草稿 key 是 collateral-{loanId}。沒有「修改」：表單本身就是這筆質押目前的
	// 擔保股票（存過就照存的內容），存好在上方「已儲存」列出。預覽、確認儲存、鎖欄位、過期訊息的做法和其他步驟一樣。
	// 帶 {loanId} 的先 collateralPage(loanId)：別人的、不存在、已作廢或不是質押 → 404。

	private static final String COLLATERAL_URL = "/welcome/collateral";

	/** 進擔保那一步：第一筆還沒設定擔保股票的質押，都設定過就第一筆（Service 決定）。 */
	@GetMapping(COLLATERAL_URL)
	public String collateral() {
		return unlessDone(() -> {
			onboarding.reach(OnboardingStep.COLLATERAL);
			return "redirect:" + onboarding.collateralEntryUrl();
		});
	}

	@GetMapping(COLLATERAL_URL + "/{loanId}")
	public String collateralOf(@PathVariable Long loanId, HttpSession session, Model model) {
		return unlessDone(() -> {
			onboarding.collateralPage(loanId);
			onboarding.reach(OnboardingStep.COLLATERAL);
			Optional<Map<String, String[]>> draft = drafts.load(session, collateralKey(loanId));
			if (draft.isPresent()) {
				DraftBinder.put(model, bindCollateral(draft.get()));
			}
			else {
				model.addAttribute("form", onboarding.collateralForm(loanId));
			}
			return collateralPage(model, loanId);
		});
	}

	@PostMapping(COLLATERAL_URL + "/{loanId}/preview")
	public String previewCollateral(@PathVariable Long loanId, HttpServletRequest request, HttpSession session,
			Model model) {
		return unlessDone(() -> {
			onboarding.collateralPage(loanId);
			BindingResult result = bindCollateral(request.getParameterMap());
			CollateralForm form = (CollateralForm) result.getTarget();
			List<String> errors = onboarding.checkCollateral(loanId, form, result);
			DraftBinder.put(model, result);
			String target = OnboardingDrafts.target(collateralKey(loanId), null, null);
			String token = errors.isEmpty() ? confirmToken(request, session, target) : null;
			List<ConfirmRow> rows = errors.isEmpty() ? onboarding.rowsFor(loanId, form) : List.of();
			showConfirmAt(model, collateralUrl(loanId) + "/go?to=stay", "擔保股票", rows, List.of(), errors,
					collateralUrl(loanId) + "/save", token);
			return collateralPage(model, loanId);
		});
	}

	/** 「確認儲存」：順序和其他步驟一樣（用掉代號 → 拿出收起來的內容 → 綁定、檢查 → 存檔），存好回到同一頁。 */
	@PostMapping(COLLATERAL_URL + "/{loanId}/save")
	public String saveCollateral(@PathVariable Long loanId, @RequestParam(required = false) String formToken,
			HttpSession session, Model model, RedirectAttributes redirect) {
		return unlessDone(() -> {
			onboarding.collateralPage(loanId);
			String page = "redirect:" + collateralUrl(loanId);
			if (!tokens.consume(session, formToken)) {
				redirect.addFlashAttribute("notice", ALREADY_SUBMITTED);
				return page;
			}
			Optional<Map<String, String[]>> confirmed = drafts.takeConfirmed(session, formToken,
					OnboardingDrafts.target(collateralKey(loanId), null, null));
			if (confirmed.isEmpty()) {
				redirect.addFlashAttribute("notice", CONFIRM_EXPIRED);
				return page;
			}
			BindingResult result = bindCollateral(confirmed.get());
			CollateralForm form = (CollateralForm) result.getTarget();
			List<String> errors = onboarding.checkCollateral(loanId, form, result);
			if (errors.isEmpty()) {
				try {
					onboarding.saveCollateral(loanId, form.collateral(), form.warnPercent(), form.callPercent());
				}
				catch (IllegalArgumentException ex) { // 含 LoanValidationException
					errors = List.of(ex.getMessage());
				}
			}
			if (!errors.isEmpty()) {
				DraftBinder.put(model, result);
				showConfirmAt(model, collateralUrl(loanId) + "/go?to=stay", "擔保股票", List.of(), List.of(), errors,
						collateralUrl(loanId) + "/save", null);
				return collateralPage(model, loanId);
			}
			drafts.clear(session, collateralKey(loanId));
			return page;
		});
	}

	/**
	 * 擔保頁的「上一步」「下一步」「之後再設定」「先離開」「回去修改」：先存草稿（不驗證、不存資料），再轉到
	 * 上一筆質押或股票（back）、下一筆質押或完成頁（next、later）、總覽（home）、同一頁（stay 或不認得的 to）。
	 */
	@PostMapping(COLLATERAL_URL + "/{loanId}/go")
	public String goFromCollateral(@PathVariable Long loanId, @RequestParam(required = false) String to,
			HttpServletRequest request, HttpSession session) {
		return unlessDone(() -> {
			onboarding.collateralPage(loanId);
			String key = collateralKey(loanId);
			drafts.save(session, key, formParams(request, session, key));
			String destination = switch (to == null ? "" : to) {
				case "back" -> onboarding.collateralBackUrl(loanId);
				case NEXT, "later" -> onboarding.collateralNextUrl(loanId);
				case "home" -> "/";
				default -> collateralUrl(loanId);
			};
			return "redirect:" + destination;
		});
	}

	private BindingResult bindCollateral(Map<String, String[]> params) {
		return draftBinder.bind("form", CollateralForm.class, params, false);
	}

	private static String collateralKey(Long loanId) {
		return "collateral-" + loanId;
	}

	private static String collateralUrl(Long loanId) {
		return COLLATERAL_URL + "/" + loanId;
	}

	private String collateralPage(Model model, Long loanId) {
		model.addAttribute("stepNumber", 4);
		model.addAttribute("page", onboarding.collateralPage(loanId));
		model.addAttribute("goUrl", collateralUrl(loanId) + "/go");
		model.addAttribute("previewUrl", collateralUrl(loanId) + "/preview");
		if (!model.containsAttribute("confirm")) {
			model.addAttribute("confirm", false);
			model.addAttribute("locked", false);
		}
		return "onboarding/collateral";
	}

	// ---------- 每一步共用：確認區塊 ----------

	/**
	 * 預覽成功：發一次性代號，並把這次送來的表單內容依代號收起來（{@link OnboardingDrafts#stashForConfirm}）。
	 * 「確認儲存」只存這一份；確認中按上一步、下一步、修改、回去修改，也是用這一份當草稿。
	 */
	private String confirmToken(HttpServletRequest request, HttpSession session, String target) {
		return stash(session, target, request.getParameterMap());
	}

	/** 發一次性代號，並把 params 收起來給 target 那個存檔用（只有那個存檔網址拿得到）。 */
	private String stash(HttpSession session, String target, Map<String, String[]> params) {
		String token = tokens.issue(session);
		drafts.stashForConfirm(session, token, target, params);
		return token;
	}

	/**
	 * 這次送來的表單內容：確認區塊顯示時欄位是鎖住的（disabled 不會送出），只送來代號，
	 * 這時用預覽時收起來的那份（只認這一步的）；其他時候就是送來的參數。
	 * 收起來的那份拿回去當草稿後，代號也一起用掉：那份內容已經不在了，代號留著只會佔掉 FormTokens 的名額
	 * （每個 session 最多 {@link FormTokens#MAX_TOKENS} 個），來回幾次就會把其他分頁還有效的代號擠掉。
	 */
	private Map<String, String[]> formParams(HttpServletRequest request, HttpSession session, String step) {
		String token = request.getParameter("formToken");
		Optional<Map<String, String[]>> stashed = drafts.takeConfirmedForStep(session, token, step);
		if (stashed.isEmpty()) {
			return request.getParameterMap();
		}
		tokens.consume(session, token);
		return stashed.get();
	}

	/**
	 * 確認區塊（onboarding/confirm.html）要的資料。what 是「確認要儲存這筆○○嗎？」的○○；errors 不是空的就是錯誤畫面
	 * （只有「回去修改」，欄位不鎖，打的字跟著送回去）。沒有錯誤時欄位鎖住（locked），確認的內容就是畫面上看到的。
	 * warnings 是 Service 給的提醒（不擋存檔）。「回去修改」送到 go?to=自己：先存草稿再回表單。
	 */
	private static void showConfirm(Model model, String step, String what, List<ConfirmRow> rows,
			List<String> warnings, List<String> errors, String saveUrl, String token) {
		showConfirmAt(model, "/welcome/" + step + "/go?to=" + step, what, rows, warnings, errors, saveUrl, token);
	}

	/** 同 {@link #showConfirm}，「回去修改」送到 backUrl（擔保頁的網址帶 loanId，不是 /welcome/{step}/go）。 */
	private static void showConfirmAt(Model model, String backUrl, String what, List<ConfirmRow> rows,
			List<String> warnings, List<String> errors, String saveUrl, String token) {
		model.addAttribute("confirm", true);
		model.addAttribute("locked", errors.isEmpty());
		model.addAttribute("what", what);
		model.addAttribute("rows", rows);
		model.addAttribute("warnings", warnings);
		model.addAttribute("errors", errors);
		model.addAttribute("saveUrl", saveUrl);
		model.addAttribute("backUrl", backUrl);
		model.addAttribute("formToken", token);
	}

	/** 「我自己來，不用引導」：標成完成、回總覽。 */
	@PostMapping("/welcome/skip")
	public String skip() {
		onboarding.finish();
		return HOME;
	}

	/** 完成頁的「到總覽看看」。 */
	@PostMapping("/welcome/finish")
	public String finish() {
		onboarding.finish();
		return HOME;
	}

	/** 「先離開，下次再繼續」（沒有表單的頁面）：進度保留，直接回總覽。 */
	@PostMapping("/welcome/leave")
	public String leave(HttpSession session) {
		onboarding.markLeft(session);
		return HOME;
	}

	/** 引導已經完成：/welcome/** 一律回總覽（設計文件 7a-5）；否則照常處理這一頁。 */
	private String unlessDone(Supplier<String> page) {
		return onboarding.done() ? HOME : page.get();
	}

}
