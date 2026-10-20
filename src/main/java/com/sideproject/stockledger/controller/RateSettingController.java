package com.sideproject.stockledger.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.sideproject.stockledger.dto.RateSettingForm;
import com.sideproject.stockledger.entity.RateKind;
import com.sideproject.stockledger.service.RateSettingService;
import com.sideproject.stockledger.service.RateValidationException;

/** 設定頁的「費率」（spec 3 第 6 頁、4.5）。法定費率唯讀；個人費率只能新增版本，不能修改或刪除舊版本。 */
@Controller
@RequestMapping("/settings/rates")
public class RateSettingController {

	private static final String VIEW = "settings/rates";

	private final RateSettingService service;

	public RateSettingController(RateSettingService service) {
		this.service = service;
	}

	@ModelAttribute("personalKinds")
	public List<RateKind> personalKinds() {
		return RateKind.personalKinds();
	}

	@GetMapping
	public String page(Model model) {
		model.addAttribute("form", service.emptyForm());
		model.addAttribute("rows", service.currentTable());
		return VIEW;
	}

	@PostMapping
	public String add(@Valid @ModelAttribute("form") RateSettingForm form, BindingResult result, Model model) {
		if (!result.hasErrors()) {
			try {
				service.addPersonalVersion(form);
				return "redirect:/settings/rates";
			}
			catch (RateValidationException ex) {
				result.reject("invalid", ex.getMessage());
			}
		}
		model.addAttribute("rows", service.currentTable());
		return VIEW;
	}

}
