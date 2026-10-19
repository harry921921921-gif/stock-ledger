package com.sideproject.stockledger.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

	/** 階段 1 還沒有總覽頁，登入後先進設定頁。 */
	@GetMapping("/")
	public String home() {
		return "redirect:/settings";
	}

	@GetMapping("/settings")
	public String settings() {
		return "settings/index";
	}

}
