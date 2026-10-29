package com.sideproject.stockledger.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.service.CashAccountService;
import com.sideproject.stockledger.service.LoanService;
import com.sideproject.stockledger.service.TradeService;

/** 總覽、股票、資金來源頁標題下方的提醒有真的畫出來（設計文件 2026-10-25-onboarding 3.3）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class NoticeRenderTest {

	private static final String ONBOARDING = "第一次使用引導還有沒完成的步驟";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Clock clock;

	@Autowired
	private CashAccountService accounts;

	@Autowired
	private LoanService loans;

	@Autowired
	private TradeService trades;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM source_allocation");
		jdbc.update("DELETE FROM trade_revision");
		jdbc.update("DELETE FROM trade");
		jdbc.update("DELETE FROM pledge_collateral");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM rate_setting WHERE user_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		jdbc.update("DELETE FROM stock_price");
		jdbc.update("DELETE FROM dividend");
		jdbc.update("DELETE FROM stock");
		if (!users.existsByUsername("nr-dee")) {
			users.save(AppUser.create("nr-dee", encoder.encode("password123"), "nr-dee", Role.USER,
					LocalDateTime.now(clock)));
		}
		jdbc.update("UPDATE app_user SET onboarding_step = 'LOANS' WHERE username = 'nr-dee'");
	}

	@Test
	@WithUserDetails(value = "nr-dee", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void stocksPageShowsOnboardingNotice() throws Exception {
		mvc.perform(get("/stocks"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ONBOARDING)))
			.andExpect(content().string(containsString("href=\"/welcome/loans\"")));
	}

	@Test
	@WithUserDetails(value = "nr-dee", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void overviewShowsOnboardingNoticeAfterLeaving() throws Exception {
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/welcome/leave").session(session).with(csrf()));

		mvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ONBOARDING)))
			.andExpect(content().string(containsString("href=\"/welcome/loans\"")));
	}

	@Test
	@WithUserDetails(value = "nr-dee", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void fundingPageShowsBothNoticesAndCardAnchor() throws Exception {
		Loan pledge = pledge();

		mvc.perform(get("/funding"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(ONBOARDING)))
			.andExpect(content().string(containsString("href=\"/welcome/loans\"")))
			.andExpect(content().string(containsString("「玉山質押」還沒設定擔保股票，維持率無法計算")))
			.andExpect(content().string(containsString("href=\"/funding#loan-" + pledge.getId() + "\"")))
			.andExpect(content().string(containsString("id=\"loan-" + pledge.getId() + "\"")));
	}

	@Test
	@WithUserDetails(value = "nr-dee", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void doneUserWithCollateralSeesNoNotices() throws Exception {
		Loan pledge = pledge();
		LocalDate today = LocalDate.now(clock);
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0056', '元大高股息', "
				+ "'ETF', 4, NOW())");
		trades.create(new TradeForm(TradeType.OPENING, today.minusDays(10), "0056", null, 1000L,
				new BigDecimal("30"), null, null, null, List.of()));
		loans.setCollateral(pledge.getId(), "0056", 1000L);
		jdbc.update("UPDATE app_user SET onboarding_step = 'DONE' WHERE username = 'nr-dee'");

		for (String url : List.of("/", "/stocks", "/funding")) {
			mvc.perform(get(url))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString(ONBOARDING))))
				.andExpect(content().string(not(containsString("還沒設定擔保股票"))));
		}
	}

	private Loan pledge() {
		LocalDate today = LocalDate.now(clock);
		Long settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		return loans.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING,
				new BigDecimal("400000"), today.minusDays(1), new BigDecimal("400000"), new BigDecimal("3"), null,
				settlement, 25, null, null, null));
	}

}
