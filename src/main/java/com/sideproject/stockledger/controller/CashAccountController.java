package com.sideproject.stockledger.controller;

import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.service.CashAccountService;
import com.sideproject.stockledger.service.CashMovementService;

/** 設定頁的「現金帳戶」（spec 3 第 6 頁）。只負責收表單、轉頁；規則都在 Service。不提供刪除。 */
@Controller
@RequestMapping("/settings/accounts")
public class CashAccountController {

	private static final String FORM_VIEW = "settings/account-form";

	private final CashAccountService service;
	private final CashMovementService movements;

	public CashAccountController(CashAccountService service, CashMovementService movements) {
		this.service = service;
		this.movements = movements;
	}

	@ModelAttribute("types")
	public CashAccountType[] types() {
		return CashAccountType.values();
	}

	@GetMapping
	public String list(Model model) {
		model.addAttribute("accounts", service.listMine());
		return "settings/accounts";
	}

	@GetMapping("/new")
	public String newForm(Model model) {
		model.addAttribute("form", CashAccountForm.empty());
		return FORM_VIEW;
	}

	@PostMapping
	public String create(@Valid @ModelAttribute("form") CashAccountForm form, BindingResult result) {
		if (result.hasErrors()) {
			return FORM_VIEW;
		}
		try {
			service.create(form);
		}
		catch (IllegalArgumentException ex) {
			result.rejectValue("name", "duplicate", ex.getMessage());
			return FORM_VIEW;
		}
		return "redirect:/settings/accounts";
	}

	@GetMapping("/{id}/edit")
	public String editForm(@PathVariable Long id, Model model) {
		model.addAttribute("form", CashAccountForm.from(service.getMine(id)));
		model.addAttribute("accountId", id);
		model.addAttribute("opening", movements.findOpening(id).orElse(null));
		return FORM_VIEW;
	}

	@PostMapping("/{id}")
	public String update(@PathVariable Long id, @Valid @ModelAttribute("form") CashAccountForm form,
			BindingResult result, Model model) {
		model.addAttribute("accountId", id);
		model.addAttribute("opening", movements.findOpening(id).orElse(null));
		if (result.hasErrors()) {
			return FORM_VIEW;
		}
		try {
			service.update(id, form);
		}
		catch (IllegalArgumentException ex) {
			result.rejectValue("name", "duplicate", ex.getMessage());
			return FORM_VIEW;
		}
		return "redirect:/settings/accounts";
	}

}
