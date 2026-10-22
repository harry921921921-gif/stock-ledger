package com.sideproject.stockledger.controller;

import java.util.List;

import jakarta.servlet.http.HttpSession;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sideproject.stockledger.dto.FillForm;
import com.sideproject.stockledger.dto.RecurringItemForm;
import com.sideproject.stockledger.entity.AmountMode;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.RecurringCycle;
import com.sideproject.stockledger.entity.RecurringDirection;
import com.sideproject.stockledger.entity.RecurringItem;
import com.sideproject.stockledger.service.CashAccountService;
import com.sideproject.stockledger.service.RecurringItemService;
import com.sideproject.stockledger.service.RecurringValidationException;

/**
 * 固定收支頁（spec 第 3 節第 5 頁、設計文件 4.4）：項目清單、新增、修改（含結束月份）、每月自己填的金額。
 * 只負責收表單、轉頁；規則與計算都在 RecurringItemService。故意不加 @Transactional（同 CashMovementController）。
 */
@Controller
public class RecurringController {

	private static final String ITEM_FORM = "recurring/item-form";

	private final RecurringItemService items;
	private final CashAccountService accounts;
	private final FormTokens tokens;
	private final MessageSource messages;

	public RecurringController(RecurringItemService items, CashAccountService accounts, FormTokens tokens,
			MessageSource messages) {
		this.items = items;
		this.accounts = accounts;
		this.tokens = tokens;
		this.messages = messages;
	}

	@ModelAttribute("myAccounts")
	public List<CashAccount> myAccounts() {
		return accounts.listMine();
	}

	@ModelAttribute("directions")
	public RecurringDirection[] directions() {
		return RecurringDirection.values();
	}

	@ModelAttribute("amountModes")
	public AmountMode[] amountModes() {
		return AmountMode.values();
	}

	@ModelAttribute("cycles")
	public RecurringCycle[] cycles() {
		return RecurringCycle.values();
	}

	// ---------- 固定收支頁 ----------

	@GetMapping("/recurring")
	public String index(Model model, HttpSession session) {
		model.addAttribute("view", items.pageView());
		// 頁面上每個「填金額」表單共用一個代號：填完一筆就轉頁，回來會拿到新的
		model.addAttribute("formToken", tokens.issue(session));
		return "recurring/index";
	}

	// ---------- 新增 ----------

	@GetMapping("/recurring/new")
	public String newItem(Model model, HttpSession session) {
		model.addAttribute("form", items.newForm());
		model.addAttribute("formToken", tokens.issue(session));
		return ITEM_FORM;
	}

	@PostMapping("/recurring/new")
	public String create(@ModelAttribute("form") RecurringItemForm form, BindingResult result,
			@RequestParam(required = false) String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			model.addAttribute("formToken", formToken);
			return ITEM_FORM;
		}
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("message", "這個表單已經送出過或已失效，沒有重複新增；請在下方確認");
			return "redirect:/recurring";
		}
		RecurringItemService.Created created;
		try {
			created = items.create(form);
		}
		catch (RecurringValidationException ex) {
			tokens.restore(session, formToken);
			result.reject("invalid", ex.getMessage());
			model.addAttribute("formToken", formToken);
			return ITEM_FORM;
		}
		catch (RuntimeException ex) {
			// 其他錯誤也代表沒有存到：把代號還回去，重送才不會被當成重複
			tokens.restore(session, formToken);
			throw ex;
		}
		String backfill = created.backfilled() > 0 ? "，補記了 " + created.backfilled() + " 筆過去的入帳" : "";
		redirect.addFlashAttribute("message", "已新增「" + created.item().getName() + "」" + backfill);
		return "redirect:/recurring";
	}

	// ---------- 修改（含設定結束月份） ----------

	@GetMapping("/recurring/{id}/edit")
	public String edit(@PathVariable Long id, Model model) {
		model.addAttribute("form", items.form(id));
		model.addAttribute("itemId", id);
		return ITEM_FORM;
	}

	@PostMapping("/recurring/{id}/edit")
	public String update(@PathVariable Long id, @ModelAttribute("form") RecurringItemForm form, BindingResult result,
			Model model, RedirectAttributes redirect) {
		// 先確認是自己的項目（別人的 → 404），表單有錯也一樣
		items.find(id);
		model.addAttribute("itemId", id);
		if (result.hasErrors()) {
			return ITEM_FORM;
		}
		try {
			RecurringItem saved = items.edit(id, form);
			redirect.addFlashAttribute("message", "已修改「" + saved.getName() + "」");
			return "redirect:/recurring";
		}
		catch (RecurringValidationException ex) {
			result.reject("invalid", ex.getMessage());
			return ITEM_FORM;
		}
	}

	// ---------- 每月自己填 ----------

	@PostMapping("/recurring/{id}/fill")
	public String fill(@PathVariable Long id, @ModelAttribute("fillForm") FillForm form, BindingResult result,
			@RequestParam(required = false) String formToken, HttpSession session, RedirectAttributes redirect) {
		RecurringItem item = items.find(id);
		if (result.hasErrors()) {
			// 格式錯誤（例如金額打了文字）：用 messages.properties 的中文訊息
			redirect.addFlashAttribute("error",
					messages.getMessage(result.getAllErrors().get(0), LocaleContextHolder.getLocale()));
			return "redirect:/recurring";
		}
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("message", "這個表單已經送出過或已失效，沒有重複記錄；請在下方確認");
			return "redirect:/recurring";
		}
		try {
			items.fill(id, form);
		}
		catch (RecurringValidationException ex) {
			tokens.restore(session, formToken);
			redirect.addFlashAttribute("error", ex.getMessage());
			return "redirect:/recurring";
		}
		catch (RuntimeException ex) {
			tokens.restore(session, formToken);
			throw ex;
		}
		redirect.addFlashAttribute("message", "已記錄 " + form.period() + " " + item.getName());
		return "redirect:/recurring";
	}

}
