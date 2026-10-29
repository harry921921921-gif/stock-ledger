package com.sideproject.stockledger.controller;

import java.time.YearMonth;

import jakarta.servlet.http.HttpSession;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.sideproject.stockledger.service.OnboardingService;
import com.sideproject.stockledger.service.OverviewService;

@Controller
public class HomeController {

	private final OverviewService overview;
	private final OnboardingService onboarding;

	public HomeController(OverviewService overview, OnboardingService onboarding) {
		this.onboarding = onboarding;
		this.overview = overview;
	}

	/** 登入後的第一頁：總覽（spec 第 3 節第 1 頁），可以用 ?month=2026-09 切換淨資產變動的月份。 */
	@GetMapping("/")
	public String home(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
			HttpSession session, Model model) {
		if (onboarding.shouldRedirectHome(session)) {
			return "redirect:" + onboarding.urlOf(onboarding.currentStep());
		}
		model.addAttribute("view", overview.view(month));
		return "overview/index";
	}

	@GetMapping("/settings")
	public String settings() {
		return "settings/index";
	}

}
