package com.sideproject.stockledger.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
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

import com.sideproject.stockledger.dto.AllocationLine;
import com.sideproject.stockledger.dto.DividendForm;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.Trade;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.service.CashAccountService;
import com.sideproject.stockledger.service.LoanService;
import com.sideproject.stockledger.service.StockSettingService;
import com.sideproject.stockledger.service.TradeService;
import com.sideproject.stockledger.service.TradeValidationException;

/**
 * 股票頁（spec 第 3 節第 3 頁）：持股、配息提醒、最近賣出、交易紀錄；新增、修改、作廢交易；配息入帳。
 * 只負責收表單、轉頁；規則與計算都在 TradeService。故意不加 @Transactional（同 CashMovementController）。
 */
@Controller
public class StockController {

	private static final String TRADE_FORM = "stocks/trade-form";
	private static final String DIVIDEND_FORM = "stocks/dividend-form";

	private final TradeService trades;
	private final LoanService loans;
	private final CashAccountService accounts;
	private final FormTokens tokens;
	private final StockSettingService stockSettings;

	public StockController(TradeService trades, LoanService loans, CashAccountService accounts, FormTokens tokens,
			StockSettingService stockSettings) {
		this.stockSettings = stockSettings;
		this.trades = trades;
		this.loans = loans;
		this.accounts = accounts;
		this.tokens = tokens;
	}

	@ModelAttribute("myAccounts")
	public List<CashAccount> myAccounts() {
		return accounts.listMine();
	}

	@ModelAttribute("myLoans")
	public List<Loan> myLoans() {
		return loans.listMine();
	}

	@ModelAttribute("tradeTypes")
	public TradeType[] tradeTypes() {
		return TradeType.values();
	}

	@GetMapping("/stocks")
	public String index(Model model) {
		model.addAttribute("view", trades.view());
		return "stocks/index";
	}

	// ---------- 新增、修改、作廢交易 ----------

	@GetMapping("/stocks/trades/new")
	public String newTrade(@RequestParam(required = false) TradeType type,
			@RequestParam(required = false) String code, Model model, HttpSession session) {
		model.addAttribute("form", new TradeForm(type == null ? TradeType.BUY : type, trades.today(), code, null, null,
				null, null, null, null, emptyAllocations()));
		model.addAttribute("formToken", tokens.issue(session));
		return TRADE_FORM;
	}

	@PostMapping("/stocks/trades/new")
	public String create(@ModelAttribute("form") TradeForm form, BindingResult result,
			@RequestParam(required = false) String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			model.addAttribute("formToken", formToken);
			return TRADE_FORM;
		}
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("message", "這個表單已經送出過或已失效，沒有重複新增；請在下方確認");
			return "redirect:/stocks";
		}
		Trade saved;
		try {
			saved = trades.create(form);
		}
		catch (TradeValidationException ex) {
			tokens.restore(session, formToken);
			result.reject("invalid", ex.getMessage());
			model.addAttribute("formToken", formToken);
			return TRADE_FORM;
		}
		catch (RuntimeException ex) {
			tokens.restore(session, formToken);
			throw ex;
		}
		redirect.addFlashAttribute("message", "已新增" + saved.getType().label() + "：" + form.stockCode());
		return "redirect:/stocks";
	}

	@GetMapping("/stocks/trades/{id}/edit")
	public String edit(@PathVariable Long id, Model model) {
		TradeForm form = trades.form(id);
		model.addAttribute("form", withAllLoans(form));
		model.addAttribute("tradeId", id);
		return TRADE_FORM;
	}

	@PostMapping("/stocks/trades/{id}/edit")
	public String update(@PathVariable Long id, @ModelAttribute("form") TradeForm form, BindingResult result,
			@RequestParam(required = false) String reason, Model model, RedirectAttributes redirect) {
		trades.find(id);
		model.addAttribute("tradeId", id);
		if (result.hasErrors()) {
			return TRADE_FORM;
		}
		try {
			trades.edit(id, form, reason);
		}
		catch (TradeValidationException ex) {
			result.reject("invalid", ex.getMessage());
			return TRADE_FORM;
		}
		redirect.addFlashAttribute("message", "已修改交易");
		return "redirect:/stocks";
	}

	@PostMapping("/stocks/trades/{id}/void")
	public String voidTrade(@PathVariable Long id, @RequestParam(required = false) String reason,
			RedirectAttributes redirect) {
		trades.find(id);
		try {
			trades.voidTrade(id, reason);
			redirect.addFlashAttribute("message", "已作廢這筆交易（自動記的現金異動也一起作廢）");
		}
		catch (TradeValidationException ex) {
			redirect.addFlashAttribute("message", ex.getMessage());
		}
		return "redirect:/stocks";
	}

	// ---------- 年配息次數（共用資料，只有管理者；StockSettingService 方法層也擋） ----------

	@PostMapping("/stocks/{code}/dividends-per-year")
	public String setDividendsPerYear(@PathVariable String code, @RequestParam(required = false) Integer times,
			RedirectAttributes redirect) {
		try {
			stockSettings.setDividendsPerYear(code, times);
			redirect.addFlashAttribute("message",
					times == null ? "已清除 " + code + " 的年配息次數" : "已設定 " + code + " 一年配息 " + times + " 次");
		}
		catch (TradeValidationException ex) {
			redirect.addFlashAttribute("message", ex.getMessage());
		}
		return "redirect:/stocks";
	}

	// ---------- 配息入帳 ----------

	/** 從配息提醒點進來時，帶入股票代號、預估實際入帳與二代健保。 */
	@GetMapping("/stocks/dividends/new")
	public String newDividend(@RequestParam(required = false) String code,
			@RequestParam(required = false) BigDecimal amount, @RequestParam(required = false) BigDecimal nhi,
			Model model, HttpSession session) {
		model.addAttribute("form", new DividendForm(trades.today(), null, code, amount, nhi));
		model.addAttribute("formToken", tokens.issue(session));
		return DIVIDEND_FORM;
	}

	@PostMapping("/stocks/dividends/new")
	public String recordDividend(@ModelAttribute("form") DividendForm form, BindingResult result,
			@RequestParam(required = false) String formToken, HttpSession session, Model model,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			model.addAttribute("formToken", formToken);
			return DIVIDEND_FORM;
		}
		if (!tokens.consume(session, formToken)) {
			redirect.addFlashAttribute("message", "這個表單已經送出過或已失效，沒有重複新增；請在下方確認");
			return "redirect:/stocks";
		}
		try {
			trades.recordDividend(form);
		}
		catch (TradeValidationException ex) {
			tokens.restore(session, formToken);
			result.reject("invalid", ex.getMessage());
			model.addAttribute("formToken", formToken);
			return DIVIDEND_FORM;
		}
		catch (RuntimeException ex) {
			tokens.restore(session, formToken);
			throw ex;
		}
		redirect.addFlashAttribute("message", "已記入配息：" + form.stockCode());
		return "redirect:/stocks";
	}

	/** 每筆借款一列（金額空白），表單上逐列填「這筆借款出了多少」。 */
	private List<AllocationLine> emptyAllocations() {
		return loans.listMine().stream().map(l -> new AllocationLine(l.getId(), null)).toList();
	}

	/** 修改時：已經填過的借款帶入金額，其餘借款空白，順序跟借款清單一樣。 */
	private TradeForm withAllLoans(TradeForm form) {
		List<AllocationLine> lines = new ArrayList<>();
		for (Loan loan : loans.listMine()) {
			BigDecimal amount = form.allocations().stream()
				.filter(a -> a.loanId().equals(loan.getId()))
				.map(AllocationLine::amount)
				.findFirst()
				.orElse(null);
			lines.add(new AllocationLine(loan.getId(), amount));
		}
		return new TradeForm(form.type(), form.date(), form.stockCode(), form.accountId(), form.shares(),
				form.price(), form.fee(), form.tax(), form.amount(), lines);
	}

}
