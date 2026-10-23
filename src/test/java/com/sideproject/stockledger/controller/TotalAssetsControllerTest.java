package com.sideproject.stockledger.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class TotalAssetsControllerTest {

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

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM source_allocation");
		jdbc.update("DELETE FROM trade_revision");
		jdbc.update("DELETE FROM trade");
		jdbc.update("DELETE FROM pledge_collateral");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		if (!users.existsByUsername("tac-amy")) {
			users.save(AppUser.create("tac-amy", encoder.encode("password123"), "tac-amy", Role.USER,
					LocalDateTime.now(clock)));
		}
	}

	@Test
	@WithUserDetails(value = "tac-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void pageShowsCardsAndSections() throws Exception {
		Long userId = users.findByUsername("tac-amy").orElseThrow().getId();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '國泰交割戶', 'SETTLEMENT', '國泰證券', NOW())", userId);

		mvc.perform(get("/assets"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("總資產")))
			.andExpect(content().string(containsString("本月淨資產變動")))
			.andExpect(content().string(containsString("資產配置")))
			.andExpect(content().string(containsString("最近的現金異動")))
			.andExpect(content().string(containsString("尚未有持股")))
			.andExpect(content().string(containsString("國泰交割戶")))
			.andExpect(content().string(containsString("href=\"/assets/movements/new\"")))
			.andExpect(content().string(containsString("href=\"/assets/movements\"")));
	}

	/** 正的餘額調整要有「+」，負的用「−」（審核 Important）。 */
	@Test
	@WithUserDetails(value = "tac-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void adjustmentsShowSigns() throws Exception {
		Long userId = users.findByUsername("tac-amy").orElseThrow().getId();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '帳戶甲', 'SETTLEMENT', '國泰證券', NOW())", userId);
		Long account = jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ?", Long.class, userId);
		java.time.LocalDate today = java.time.LocalDate.now(clock);
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, voided, created_at) "
				+ "VALUES (?, 'OPENING', ?, ?, 1000, FALSE, NOW())", userId, today.withDayOfMonth(1), account);
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, actual_balance, voided, created_at) "
				+ "VALUES (?, 'RECONCILE', ?, ?, 1200, FALSE, NOW())", userId, today, account);

		mvc.perform(get("/assets"))
			.andExpect(content().string(containsString("+200")));

		jdbc.update("UPDATE cash_movement SET actual_balance = 900 WHERE type = 'RECONCILE'");
		mvc.perform(get("/assets"))
			.andExpect(content().string(containsString("−100")))
			.andExpect(content().string(org.hamcrest.Matchers.not(containsString("-100"))));
	}

	@Test
	@WithUserDetails(value = "tac-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void emptyStateGuidesToCreateAccount() throws Exception {
		mvc.perform(get("/assets"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("href=\"/settings/accounts/new\"")));
	}

	@Test
	@WithUserDetails(value = "tac-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void sidebarAssetsLinkEnabled() throws Exception {
		mvc.perform(get("/assets"))
			.andExpect(content().string(containsString("href=\"/assets\"")));
		mvc.perform(get("/settings"))
			.andExpect(content().string(containsString("href=\"/assets\"")));
	}

	@Test
	void anonymousRedirectedToLogin() throws Exception {
		mvc.perform(get("/assets"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

}
