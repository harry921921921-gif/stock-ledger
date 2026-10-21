package com.sideproject.stockledger.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.sideproject.stockledger.service.TotalAssetsService;

/** 總資產頁（spec 第 3 節第 2 頁）。數字都由 Service 算好，這裡只負責轉頁。 */
@Controller
public class TotalAssetsController {

	private final TotalAssetsService service;

	public TotalAssetsController(TotalAssetsService service) {
		this.service = service;
	}

	@GetMapping("/assets")
	public String page(Model model) {
		model.addAttribute("view", service.view());
		return "assets/index";
	}

}
