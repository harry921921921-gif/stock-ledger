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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sideproject.stockledger.dto.NewUserForm;
import com.sideproject.stockledger.dto.ResetPasswordForm;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.service.UserAdminService;
import com.sideproject.stockledger.service.UserValidationException;

/** 設定頁的「使用者管理」（spec 3 第 6 頁、4.10-2）。只有管理者能進來（網址與 Service 兩層都擋）。 */
@Controller
@RequestMapping("/settings/users")
public class UserAdminController {

	private static final String VIEW = "settings/users";

	private final UserAdminService service;

	public UserAdminController(UserAdminService service) {
		this.service = service;
	}

	@ModelAttribute("roles")
	public Role[] roles() {
		return Role.values();
	}

	@GetMapping
	public String page(Model model) {
		model.addAttribute("form", NewUserForm.empty());
		model.addAttribute("users", service.listAll());
		return VIEW;
	}

	@PostMapping
	public String create(@Valid @ModelAttribute("form") NewUserForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		if (!result.hasErrors()) {
			try {
				String username = service.create(form).getUsername();
				redirect.addFlashAttribute("message", "已建立使用者 " + username);
				return "redirect:/settings/users";
			}
			catch (UserValidationException ex) {
				result.reject("invalid", ex.getMessage());
			}
		}
		model.addAttribute("users", service.listAll());
		return VIEW;
	}

	@PostMapping("/{id}/password")
	public String resetPassword(@PathVariable Long id, @Valid @ModelAttribute("resetForm") ResetPasswordForm form,
			BindingResult result, Model model, RedirectAttributes redirect) {
		String username = service.usernameOf(id);
		if (!result.hasErrors()) {
			try {
				service.resetPassword(id, form);
				redirect.addFlashAttribute("message", "已重設 " + username + " 的密碼");
				return "redirect:/settings/users";
			}
			catch (UserValidationException ex) {
				result.reject("invalid", ex.getMessage());
			}
		}
		String reason = result.getAllErrors().get(0).getDefaultMessage();
		model.addAttribute("resetError", "重設 " + username + " 的密碼失敗：" + reason);
		model.addAttribute("form", NewUserForm.empty());
		model.addAttribute("users", service.listAll());
		return VIEW;
	}

}
