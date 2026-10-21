package com.sideproject.stockledger.controller;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.List;

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

import com.sideproject.stockledger.dto.CashMovementForm;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.ReconcileForm;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.service.CashAccountService;
import com.sideproject.stockledger.service.CashMovementService;
import com.sideproject.stockledger.service.CashValidationException;
import com.sideproject.stockledger.service.TotalAssetsService;

/**
 * 現金異動的畫面（spec 4.6、4.9）：記一筆現金異動、輸入實際餘額、設定初始現金餘額、全部異動、修改與作廢。
 * 只負責收表單、轉頁；規則與計算都在 Service。故意不加 @Transactional：Service 丟出驗證錯誤時交易已經 rollback，
 * 這裡接住後只是把訊息顯示在表單上。
 */
@Controller
public class CashMovementController {

	private static final String MOVEMENT_FORM = "assets/movement-form";
	private static final String OPENING_FORM = "assets/opening-form";
	private static final String RECONCILE_FORM = "assets/reconcile-form";

	private final CashMovementService movements;
	private final CashAccountService accounts;
	private final TotalAssetsService totalAssets;
	private final FormTokens tokens;

	public CashMovementController(CashMovementService movements, CashAccountService accounts,
			TotalAssetsService totalAssets, FormTokens tokens) {
		this.movements = movements;
		this.accounts = accounts;
		this.totalAssets = totalAssets;
		this.tokens = tokens;
	}

	@ModelAttribute("myAccounts")
	public List<CashAccount> myAccounts() {
		return accounts.listMine();
	}

	@ModelAttribute("movementTypes")
	public CashMovementType[] movementTypes() {
		return new CashMovementType[] { CashMovementType.DEPOSIT, CashMovementType.WITHDRAWAL,
				CashMovementType.LIVING_EXPENSE, CashMovementType.TRANSFER };
	}

	// ---------- 記一筆現金異動 ----------

	@GetMapping("/assets/movements/new")
	public String newMovement(Model model, HttpSession session) {
		model.addAttribute("form", movements.newForm());
		model.addAttribute("formToken", tokens.issue(session));
		return MOVEMENT_FORM;
	}

	@PostMapping("/assets/movements/new")
	public String create(@Valid @ModelAttribute("form") CashMovementForm form, BindingResult result,
			@RequestParam(required = false) String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			// 代號還沒用掉，改正後同一個表單可以再送
			model.addAttribute("formToken", formToken);
			return MOVEMENT_FORM;
		}
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("message", "這個表單已經送出過或已失效，沒有重複記錄；請在下方確認");
			return "redirect:/assets";
		}
		try {
			movements.record(form);
		}
		catch (CashValidationException ex) {
			tokens.restore(session, formToken);
			result.reject("invalid", ex.getMessage());
			model.addAttribute("formToken", formToken);
			return MOVEMENT_FORM;
		}
		catch (RuntimeException ex) {
			// 其他錯誤（找不到帳戶、資料庫暫時出錯）也代表沒有存到：把代號還回去，使用者重送才不會被當成重複
			tokens.restore(session, formToken);
			throw ex;
		}
		redirect.addFlashAttribute("message", "已記錄" + form.type().label());
		return "redirect:/assets";
	}

	// ---------- 全部異動 ----------

	@GetMapping("/assets/movements")
	public String list(@RequestParam(required = false) String month, Model model) {
		YearMonth selected = parseMonth(month);
		model.addAttribute("month", selected);
		model.addAttribute("prevMonth", selected.minusMonths(1));
		model.addAttribute("nextMonth", selected.plusMonths(1));
		model.addAttribute("rows", totalAssets.movements(selected));
		return "assets/movements";
	}

	// ---------- 修改與作廢 ----------

	@GetMapping("/assets/movements/{id}/edit")
	public String edit(@PathVariable Long id, Model model, RedirectAttributes redirect) {
		CashMovement m = movements.find(id);
		if (m.isVoided()) {
			redirect.addFlashAttribute("message", "這筆已經作廢，不能修改");
			return redirectToMonth(m);
		}
		model.addAttribute("movementId", id);
		return switch (m.getType()) {
			case OPENING -> {
				model.addAttribute("form", movements.openingForm(id));
				model.addAttribute("account", accounts.getMine(m.getAccountId()));
				yield OPENING_FORM;
			}
			case RECONCILE -> {
				model.addAttribute("form", movements.reconcileForm(id));
				addReconcileContext(model, m.getAccountId());
				yield RECONCILE_FORM;
			}
			case DEPOSIT, WITHDRAWAL, LIVING_EXPENSE, TRANSFER -> {
				model.addAttribute("form", movements.movementForm(id));
				yield MOVEMENT_FORM;
			}
		};
	}

	@PostMapping("/assets/movements/{id}/edit")
	public String update(@PathVariable Long id, @Valid @ModelAttribute("form") CashMovementForm form,
			BindingResult result, @RequestParam(required = false) String reason, Model model,
			RedirectAttributes redirect) {
		// 先確認是自己的異動（別人的 → 404），表單有錯也一樣
		movements.find(id);
		model.addAttribute("movementId", id);
		model.addAttribute("reason", reason);
		if (result.hasErrors()) {
			return MOVEMENT_FORM;
		}
		try {
			CashMovement saved = movements.edit(id, form, reason);
			redirect.addFlashAttribute("message", "已修改");
			return redirectToMonth(saved);
		}
		catch (CashValidationException ex) {
			result.reject("invalid", ex.getMessage());
			return MOVEMENT_FORM;
		}
	}

	@PostMapping("/assets/movements/{id}/edit-opening")
	public String updateOpening(@PathVariable Long id, @Valid @ModelAttribute("form") OpeningForm form,
			BindingResult result, @RequestParam(required = false) String reason, Model model,
			RedirectAttributes redirect) {
		CashMovement m = movements.find(id);
		model.addAttribute("movementId", id);
		model.addAttribute("reason", reason);
		model.addAttribute("account", accounts.getMine(m.getAccountId()));
		if (result.hasErrors()) {
			return OPENING_FORM;
		}
		try {
			CashMovement saved = movements.editOpening(id, form, reason);
			redirect.addFlashAttribute("message", "已修改初始現金餘額");
			return redirectToMonth(saved);
		}
		catch (CashValidationException ex) {
			result.reject("invalid", ex.getMessage());
			return OPENING_FORM;
		}
	}

	@PostMapping("/assets/movements/{id}/edit-reconcile")
	public String updateReconcile(@PathVariable Long id, @Valid @ModelAttribute("form") ReconcileForm form,
			BindingResult result, @RequestParam(required = false) String reason, Model model,
			RedirectAttributes redirect) {
		CashMovement m = movements.find(id);
		model.addAttribute("movementId", id);
		model.addAttribute("reason", reason);
		addReconcileContext(model, m.getAccountId());
		if (result.hasErrors()) {
			return RECONCILE_FORM;
		}
		try {
			CashMovement saved = movements.editReconcile(id, form, reason);
			redirect.addFlashAttribute("message", "已修改實際餘額");
			return redirectToMonth(saved);
		}
		catch (CashValidationException ex) {
			result.reject("invalid", ex.getMessage());
			return RECONCILE_FORM;
		}
	}

	@PostMapping("/assets/movements/{id}/void")
	public String voidMovement(@PathVariable Long id, @RequestParam(required = false) String reason,
			RedirectAttributes redirect) {
		CashMovement m = movements.find(id);
		try {
			movements.voidMovement(id, reason);
			redirect.addFlashAttribute("message", "已作廢");
		}
		catch (CashValidationException ex) {
			redirect.addFlashAttribute("message", ex.getMessage());
		}
		return redirectToMonth(m);
	}

	// ---------- 輸入實際餘額 ----------

	@GetMapping("/assets/accounts/{accountId}/reconcile")
	public String newReconcile(@PathVariable Long accountId, Model model) {
		addReconcileContext(model, accountId);
		model.addAttribute("form", new ReconcileForm(movements.today(), null));
		return RECONCILE_FORM;
	}

	@PostMapping("/assets/accounts/{accountId}/reconcile")
	public String reconcile(@PathVariable Long accountId, @Valid @ModelAttribute("form") ReconcileForm form,
			BindingResult result, Model model, RedirectAttributes redirect) {
		addReconcileContext(model, accountId);
		if (result.hasErrors()) {
			return RECONCILE_FORM;
		}
		try {
			movements.reconcile(accountId, form);
		}
		catch (CashValidationException ex) {
			result.reject("invalid", ex.getMessage());
			return RECONCILE_FORM;
		}
		redirect.addFlashAttribute("message", "已輸入實際餘額");
		return "redirect:/assets";
	}

	// ---------- 設定初始現金餘額 ----------

	@GetMapping("/assets/accounts/{accountId}/opening")
	public String newOpening(@PathVariable Long accountId, Model model) {
		CashAccount account = accounts.getMine(accountId);
		// 已經有初始現金餘額：直接到修改畫面
		var existing = movements.findOpening(account.getId());
		if (existing.isPresent()) {
			return "redirect:/assets/movements/" + existing.get().getId() + "/edit";
		}
		model.addAttribute("account", account);
		model.addAttribute("form", new OpeningForm(movements.today(), null));
		return OPENING_FORM;
	}

	@PostMapping("/assets/accounts/{accountId}/opening")
	public String setOpening(@PathVariable Long accountId, @Valid @ModelAttribute("form") OpeningForm form,
			BindingResult result, Model model, RedirectAttributes redirect) {
		model.addAttribute("account", accounts.getMine(accountId));
		if (result.hasErrors()) {
			return OPENING_FORM;
		}
		try {
			movements.setOpening(accountId, form);
		}
		catch (CashValidationException ex) {
			result.reject("invalid", ex.getMessage());
			return OPENING_FORM;
		}
		redirect.addFlashAttribute("message", "已設定初始現金餘額");
		return "redirect:/assets";
	}

	private void addReconcileContext(Model model, Long accountId) {
		model.addAttribute("account", accounts.getMine(accountId));
		model.addAttribute("systemBalance", totalAssets.systemBalance(accountId));
	}

	/** 網址上的月份格式不對（例如手動改成 abc）或超出 2000～9999 年時，顯示本月。 */
	private YearMonth parseMonth(String month) {
		if (month != null) {
			try {
				YearMonth parsed = YearMonth.parse(month);
				if (parsed.getYear() >= 2000 && parsed.getYear() <= 9999) {
					return parsed;
				}
			}
			catch (DateTimeException ex) {
				// 落到本月
			}
		}
		return YearMonth.from(movements.today());
	}

	private static String redirectToMonth(CashMovement m) {
		return "redirect:/assets/movements?month=" + YearMonth.from(m.getMovementDate());
	}

}
