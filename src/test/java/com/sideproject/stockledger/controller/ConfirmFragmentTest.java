package com.sideproject.stockledger.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.ActiveProfiles;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.ConfirmRow;

/** 確認區塊與引導按鈕片段的文字（設計文件 2026-10-25-onboarding 3.2；樣稿 onboarding.html）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ConfirmFragmentTest {

	@Autowired
	private ITemplateEngine engine;

	@Test
	void confirmListsRowsWarningsAndFocusesBack() {
		String html = render("onboarding/confirm", "confirm", Map.of("what", "借款",
				"rows", List.of(new ConfirmRow("名稱", "土銀信貸"), new ConfirmRow("開辦費、手續費", "")),
				"warnings", List.of("到期日 2026-01-01 已經過了。"), "errors", List.of(),
				"saveUrl", "/welcome/loans/save", "backUrl", "/welcome/loans/go?to=loans-new", "token", "t-1"));

		assertThat(html).contains("確認要儲存這筆借款嗎？", "請檢查下面的內容是否正確，沒問題再按「確認儲存」。", "<dt>名稱</dt>",
				"土銀信貸", "<dd class=\"empty\">（沒填）</dd>", "到期日 2026-01-01 已經過了。", "確認儲存",
				"formaction=\"/welcome/loans/save\"", "name=\"formToken\"", "value=\"t-1\"");
		// 焦點在「回去修改」，不在「確認儲存」
		assertThat(html).containsPattern("formaction=\"/welcome/loans/go\\?to=loans-new\"[^>]*autofocus[^>]*>回去修改");
		assertThat(html).doesNotContainPattern("autofocus[^>]*>確認儲存");
	}

	@Test
	void errorsShowOnlyBackButton() {
		String html = render("onboarding/confirm", "confirm", Map.of("what", "帳戶", "rows", List.of(),
				"warnings", List.of(), "errors", List.of("請輸入券商或銀行"), "saveUrl", "/welcome/accounts/save",
				"backUrl", "/welcome/accounts/go?to=accounts", "token", "t-1"));

		assertThat(html).contains("還有欄位沒填好，不能儲存", "請輸入券商或銀行", "回去修改")
			.doesNotContain("確認儲存", "確認要儲存這筆");
	}

	@Test
	void stepButtonsPostTheStepFormToGo() {
		String back = render("onboarding/layout", "back", Map.of("goUrl", "/welcome/accounts/go", "to", "welcome"));
		String next = render("onboarding/layout", "next",
				Map.of("goUrl", "/welcome/accounts/go", "to", "loans", "label", "下一步：借款 →", "disabled", true));
		String leave = render("onboarding/layout", "leave", Map.of("goUrl", "/welcome/accounts/go"));

		assertThat(back).contains("form=\"step-form\"", "formaction=\"/welcome/accounts/go?to=welcome\"", "← 上一步");
		assertThat(next).contains("formaction=\"/welcome/accounts/go?to=loans\"", "disabled", "下一步：借款 →");
		assertThat(leave).contains("formaction=\"/welcome/accounts/go?to=home\"", "先離開，下次再繼續");
	}

	private String render(String template, String fragment, Map<String, Object> variables) {
		MockServletContext servletContext = new MockServletContext();
		JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(servletContext);
		MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
		WebContext context = new WebContext(app.buildExchange(request, new MockHttpServletResponse()), Locale.TAIWAN,
				new HashMap<>(variables));
		return engine.process(template, Set.of(fragment), context);
	}

}
