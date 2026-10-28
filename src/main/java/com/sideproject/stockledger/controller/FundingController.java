package com.sideproject.stockledger.controller;

import java.math.BigDecimal;
import java.util.List;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sideproject.stockledger.dto.LoanEditResult;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.PrincipalForm;
import com.sideproject.stockledger.dto.RateVersionForm;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.service.CashAccountService;
import com.sideproject.stockledger.service.LoanService;
import com.sideproject.stockledger.service.LoanShortfallException;
import com.sideproject.stockledger.service.LoanValidationException;
import com.sideproject.stockledger.service.TradeService;

/**
 * 資金來源頁（spec 第 3 節第 4 頁、設計文件 7）：借款清單、新增、修改、利率版本、還本、質押再動撥。
 * 只負責收表單、轉頁；規則與計算都在 LoanService。故意不加 @Transactional（同 CashMovementController）。
 */
@Controller
public class FundingController {

	private static final String LOAN_FORM = "funding/loan-form";
	private static final String SHORTFALL_CONFIRM = "funding/shortfall-confirm";
	private static final String RATE_FORM = "funding/rate-form";
	private static final String PRINCIPAL_FORM = "funding/principal-form";

	private final LoanService loans;
	private final CashAccountService accounts;
	private final FormTokens tokens;
	private final TradeService trades;

	public FundingController(LoanService loans, CashAccountService accounts, FormTokens tokens, TradeService trades) {
		this.trades = trades;
		this.loans = loans;
		this.accounts = accounts;
		this.tokens = tokens;
	}

	@ModelAttribute("myAccounts")
	public List<CashAccount> myAccounts() {
		return accounts.listMine();
	}

	@ModelAttribute("loanTypes")
	public LoanType[] loanTypes() {
		return LoanType.values();
	}

	@ModelAttribute("repaymentMethods")
	public RepaymentMethod[] repaymentMethods() {
		return RepaymentMethod.values();
	}

	/** 新增股票質押時可以勾選的擔保持股（spec 4.8a-3）。 */
	@ModelAttribute("myHoldings")
	public List<TradeService.HoldingValue> myHoldings() {
		return trades.holdingValues();
	}

	@ModelAttribute("origins")
	public LoanOrigin[] origins() {
		return LoanOrigin.values();
	}

	@GetMapping("/funding")
	public String index(Model model) {
		model.addAttribute("view", loans.fundingView());
		return "funding/index";
	}

	// ---------- 新增、修改 ----------

	@GetMapping("/funding/new")
	public String newLoan(Model model, HttpSession session) {
		model.addAttribute("form", new LoanForm(null, LoanType.CREDIT, null, LoanOrigin.NEW, null, loans.today(),
				null, null, null, null, null, null, null, null, RepaymentMethod.MONTHLY_FIXED, null, null, List.of(), null,
				null));
		model.addAttribute("formToken", tokens.issue(session));
		return LOAN_FORM;
	}

	@PostMapping("/funding/new")
	public String create(@ModelAttribute("form") LoanForm submitted, BindingResult result,
			@RequestParam(required = false) String formToken, @RequestParam(required = false) Boolean pledge,
			HttpSession session, Model model, RedirectAttributes redirect) {
		// 新表單用「這是股票質押」勾選框決定類型（DR-048）；舊的寫法直接送 type
		LoanForm form = submitted.repaymentMethod() == null ? submitted : withType(submitted,
				Boolean.TRUE.equals(pledge) ? LoanType.PLEDGE : LoanType.CREDIT);
		model.addAttribute("form", form);
		if (result.hasErrors()) {
			model.addAttribute("formToken", formToken);
			return LOAN_FORM;
		}
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("message", "這個表單已經送出過或已失效，沒有重複新增；請在下方確認");
			return "redirect:/funding";
		}
		Loan saved;
		try {
			saved = loans.create(form);
		}
		catch (LoanValidationException ex) {
			tokens.restore(session, formToken);
			result.reject("invalid", ex.getMessage());
			model.addAttribute("formToken", formToken);
			return LOAN_FORM;
		}
		catch (RuntimeException ex) {
			tokens.restore(session, formToken);
			throw ex;
		}
		String warning = joinWarnings(loans.paymentWarning(saved.getId()), loans.dueWarning(form));
		redirect.addFlashAttribute("message", "已新增「" + saved.getName() + "」" + (warning == null ? "" : "。" + warning));
		return "redirect:/funding";
	}

	private static String joinWarnings(String first, String second) {
		if (first == null) {
			return second;
		}
		return second == null ? first : first + "。" + second;
	}

	@GetMapping("/funding/{id}/edit")
	public String edit(@PathVariable Long id, Model model) {
		Loan loan = loans.find(id);
		LoanForm form = loans.form(loan.getId());
		model.addAttribute("form", form);
		model.addAttribute("loanId", id);
		model.addAttribute("editMethod", loan.getRepaymentMethod().name());
		// V11 以前的借款（還款方式帶 null）：畫面不送還款方式，照舊的規則檢查，不會要求填扣款金額
		model.addAttribute("legacyEdit", form.repaymentMethod() == null);
		return LOAN_FORM;
	}

	@PostMapping("/funding/{id}/edit")
	public String update(@PathVariable Long id, @ModelAttribute("form") LoanForm submitted, BindingResult result,
			@RequestParam(required = false) Boolean pledge, Model model, RedirectAttributes redirect) {
		return saveEdit(id, submitted, result, pledge, false, model, redirect);
	}

	/** 借款改小、不夠付已經用它買的股票，使用者看過提示按「確定修改」：把差額改算自有再存。 */
	@PostMapping("/funding/{id}/edit/confirm-shortfall")
	public String confirmShortfall(@PathVariable Long id, @ModelAttribute("form") LoanForm submitted,
			BindingResult result, @RequestParam(required = false) Boolean pledge, Model model,
			RedirectAttributes redirect) {
		return saveEdit(id, submitted, result, pledge, true, model, redirect);
	}

	private String saveEdit(Long id, LoanForm submitted, BindingResult result, Boolean pledge, boolean confirmShortfall,
			Model model, RedirectAttributes redirect) {
		Loan current = loans.find(id);
		// 修改表單和新增一樣用「這是股票質押」勾選框決定類型；舊的寫法直接送 type
		LoanForm form = submitted.repaymentMethod() == null ? submitted : withType(submitted,
				Boolean.TRUE.equals(pledge) ? LoanType.PLEDGE : LoanType.CREDIT);
		model.addAttribute("form", form);
		model.addAttribute("loanId", id);
		model.addAttribute("editMethod", (form.repaymentMethod() != null ? form.repaymentMethod()
				: current.getRepaymentMethod()).name());
		model.addAttribute("legacyEdit", form.repaymentMethod() == null);
		if (result.hasErrors()) {
			return LOAN_FORM;
		}
		try {
			LoanEditResult edited = loans.edit(id, form, confirmShortfall);
			String warning = joinWarnings(loans.paymentWarning(edited.loan().getId()), loans.dueWarning(form));
			redirect.addFlashAttribute("message", "已修改「" + edited.loan().getName() + "」"
					+ (warning == null ? "" : "。" + warning));
			if (!edited.kept().isEmpty()) {
				redirect.addFlashAttribute("kept", edited.kept());
				redirect.addFlashAttribute("keptAccountMoved", edited.payAccountChanged());
			}
			return "redirect:/funding";
		}
		catch (LoanShortfallException ex) {
			model.addAttribute("shortfallMessage", ex.getMessage());
			model.addAttribute("cuts", ex.cuts());
			return SHORTFALL_CONFIRM;
		}
		catch (LoanValidationException ex) {
			result.reject("invalid", ex.getMessage());
			return LOAN_FORM;
		}
	}

	// ---------- 利率版本 ----------

	@GetMapping("/funding/{id}/rate")
	public String newRate(@PathVariable Long id, Model model) {
		model.addAttribute("loan", loans.find(id));
		model.addAttribute("form", new RateVersionForm(loans.today(), null));
		return RATE_FORM;
	}

	@PostMapping("/funding/{id}/rate")
	public String addRate(@PathVariable Long id, @ModelAttribute("form") RateVersionForm form, BindingResult result,
			Model model, RedirectAttributes redirect) {
		model.addAttribute("loan", loans.find(id));
		if (result.hasErrors()) {
			return RATE_FORM;
		}
		try {
			loans.addRate(id, form);
		}
		catch (LoanValidationException ex) {
			result.reject("invalid", ex.getMessage());
			return RATE_FORM;
		}
		redirect.addFlashAttribute("message", "已新增利率版本");
		return "redirect:/funding";
	}

	// ---------- 作廢 ----------

	@PostMapping("/funding/{id}/void")
	public String voidLoan(@PathVariable Long id, @RequestParam(required = false) String reason,
			RedirectAttributes redirect) {
		Loan loan = loans.find(id);
		try {
			loans.voidLoan(id, reason);
			redirect.addFlashAttribute("message", "已作廢「" + loan.getName() + "」和它的所有紀錄");
		}
		catch (LoanValidationException ex) {
			redirect.addFlashAttribute("message", ex.getMessage());
		}
		return "redirect:/funding";
	}

	// ---------- 質押的擔保股票與維持率門檻 ----------

	@PostMapping("/funding/{id}/collateral")
	public String setCollateral(@PathVariable Long id, @RequestParam(required = false) String stockCode,
			@RequestParam(required = false) Long shares, RedirectAttributes redirect) {
		Loan loan = loans.find(id);
		try {
			loans.setCollateral(id, stockCode, shares);
			redirect.addFlashAttribute("message", "已更新「" + loan.getName() + "」的擔保股票");
		}
		catch (LoanValidationException ex) {
			redirect.addFlashAttribute("message", ex.getMessage());
		}
		return "redirect:/funding";
	}

	@PostMapping("/funding/{id}/thresholds")
	public String setThresholds(@PathVariable Long id, @RequestParam(required = false) BigDecimal warnPercent,
			@RequestParam(required = false) BigDecimal callPercent, RedirectAttributes redirect) {
		Loan loan = loans.find(id);
		try {
			loans.setThresholds(id, warnPercent, callPercent);
			redirect.addFlashAttribute("message", "已更新「" + loan.getName() + "」的維持率提醒線與追繳線");
		}
		catch (LoanValidationException ex) {
			redirect.addFlashAttribute("message", ex.getMessage());
		}
		return "redirect:/funding";
	}

	// ---------- 還本、再動撥 ----------

	@GetMapping("/funding/{id}/repay")
	public String newRepay(@PathVariable Long id, Model model, HttpSession session) {
		return principalForm(id, false, model, session);
	}

	@GetMapping("/funding/{id}/redraw")
	public String newRedraw(@PathVariable Long id, Model model, HttpSession session) {
		return principalForm(id, true, model, session);
	}

	@PostMapping("/funding/{id}/repay")
	public String repay(@PathVariable Long id, @ModelAttribute("form") PrincipalForm form, BindingResult result,
			@RequestParam(required = false) String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		return savePrincipal(id, false, form, result, formToken, session, model, redirect);
	}

	@PostMapping("/funding/{id}/redraw")
	public String redraw(@PathVariable Long id, @ModelAttribute("form") PrincipalForm form, BindingResult result,
			@RequestParam(required = false) String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		return savePrincipal(id, true, form, result, formToken, session, model, redirect);
	}

	private String principalForm(Long id, boolean redraw, Model model, HttpSession session) {
		Loan loan = loans.find(id);
		model.addAttribute("loan", loan);
		model.addAttribute("redraw", redraw);
		model.addAttribute("form", redraw ? new PrincipalForm(loans.today(), null, loan.getPayAccountId())
				: loans.repayDefaults(id));
		model.addAttribute("formToken", tokens.issue(session));
		return PRINCIPAL_FORM;
	}

	private String savePrincipal(Long id, boolean redraw, PrincipalForm form, BindingResult result, String formToken,
			HttpSession session, Model model, RedirectAttributes redirect) {
		Loan loan = loans.find(id);
		model.addAttribute("loan", loan);
		model.addAttribute("redraw", redraw);
		model.addAttribute("formToken", formToken);
		if (result.hasErrors()) {
			return PRINCIPAL_FORM;
		}
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("message", "這個表單已經送出過或已失效，沒有重複記錄；請在下方確認");
			return "redirect:/funding";
		}
		try {
			if (redraw) {
				loans.redraw(id, form);
			}
			else {
				loans.repay(id, form);
			}
		}
		catch (LoanValidationException ex) {
			tokens.restore(session, formToken);
			result.reject("invalid", ex.getMessage());
			return PRINCIPAL_FORM;
		}
		catch (RuntimeException ex) {
			tokens.restore(session, formToken);
			throw ex;
		}
		redirect.addFlashAttribute("message", (redraw ? "已記錄再動撥：" : "已記錄還本：") + loan.getName());
		return "redirect:/funding";
	}

	private static LoanForm withType(LoanForm f, LoanType type) {
		return new LoanForm(f.name(), type, f.bank(), f.origin(), f.originalAmount(), f.startDate(), f.startPrincipal(),
				f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(), f.remainingPeriods(),
				f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(), f.collateral(),
				f.warnPercent(), f.callPercent());
	}

}
