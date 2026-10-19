package com.sideproject.stockledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class CashAccountControllerTest {

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
		jdbc.update("DELETE FROM cash_account");
		createIfMissing("amy");
		createIfMissing("ben");
	}

	/** 用網址直接開別人的帳戶要回 404，不是 403，才不會透露「這個 id 存在」（Review Focus #1）。 */
	@Test
	@WithUserDetails(value = "ben", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void otherUsersAccountReturns404() throws Exception {
		Long amyAccountId = insertAccount("amy", "國泰交割戶");

		mvc.perform(get("/settings/accounts/{id}/edit", amyAccountId))
			.andExpect(status().isNotFound());
	}

	@Test
	@WithUserDetails(value = "ben", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void otherUsersAccountCannotBeUpdated() throws Exception {
		Long amyAccountId = insertAccount("amy", "國泰交割戶");

		mvc.perform(post("/settings/accounts/{id}", amyAccountId)
				.param("name", "被 ben 改掉")
				.param("type", "SETTLEMENT")
				.param("institution", "國泰證券")
				.with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(jdbc.queryForObject("SELECT name FROM cash_account WHERE id = ?", String.class, amyAccountId))
			.isEqualTo("國泰交割戶");
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void createRequiresCsrf() throws Exception {
		mvc.perform(post("/settings/accounts")
				.param("name", "國泰交割戶")
				.param("type", "SETTLEMENT")
				.param("institution", "國泰證券"))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void createRedirectsToList() throws Exception {
		mvc.perform(post("/settings/accounts")
				.param("name", "國泰交割戶")
				.param("type", "SETTLEMENT")
				.param("institution", "國泰證券")
				.with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/settings/accounts"));

		mvc.perform(get("/settings/accounts"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("國泰交割戶")))
			.andExpect(content().string(containsString("證券交割戶")))
			.andExpect(content().string(containsString("國泰證券")));
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void blankNameShowsValidationError() throws Exception {
		mvc.perform(post("/settings/accounts")
				.param("name", "")
				.param("type", "SETTLEMENT")
				.param("institution", "國泰證券")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("請輸入帳戶名稱")));
	}

	/** 只有全形空白的名稱等於沒填（審核發現的問題）。 */
	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void fullWidthBlankNameShowsValidationError() throws Exception {
		mvc.perform(post("/settings/accounts")
				.param("name", "　")
				.param("type", "SETTLEMENT")
				.param("institution", "國泰證券")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("請輸入帳戶名稱")));
	}

	/** 正常操作選不到不存在的類型；手動送出時要顯示中文，不可露出 Java 類別名稱。 */
	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void invalidTypeShowsChineseMessage() throws Exception {
		mvc.perform(post("/settings/accounts")
				.param("name", "國泰交割戶")
				.param("type", "FOO")
				.param("institution", "國泰證券")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("請選擇帳戶類型")))
			.andExpect(content().string(not(containsString("java.lang"))));
	}

	/** 按下儲存後鎖住按鈕的 script（/js/app.js）要載入，按鈕要有「儲存中…」文字。 */
	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formLoadsDoubleSubmitGuard() throws Exception {
		mvc.perform(get("/settings/accounts/new"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("src=\"/js/app.js\"")))
			.andExpect(content().string(containsString("data-busy-text=\"儲存中…\"")));
	}

	/** 其他測試都用 csrf() 自己補 token；這裡確認畫面上的表單真的有帶 token。 */
	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formContainsCsrfToken() throws Exception {
		mvc.perform(get("/settings/accounts/new"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("name=\"_csrf\"")));
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void duplicateNameShowsErrorOnForm() throws Exception {
		insertAccount("amy", "國泰交割戶");

		mvc.perform(post("/settings/accounts")
				.param("name", "國泰交割戶")
				.param("type", "SETTLEMENT")
				.param("institution", "國泰證券")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("帳戶名稱已存在")));
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void cannotInjectUserId() throws Exception {
		Long benId = users.findByUsername("ben").orElseThrow().getId();
		Long amyId = users.findByUsername("amy").orElseThrow().getId();

		mvc.perform(post("/settings/accounts")
				.param("name", "國泰交割戶")
				.param("type", "SETTLEMENT")
				.param("institution", "國泰證券")
				.param("userId", benId.toString())
				.with(csrf()))
			.andExpect(status().is3xxRedirection());

		assertThat(jdbc.queryForObject("SELECT user_id FROM cash_account WHERE name = '國泰交割戶'", Long.class))
			.isEqualTo(amyId);
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void listShowsOnlyMyAccounts() throws Exception {
		insertAccount("amy", "艾咪的交割戶");
		insertAccount("ben", "班的交割戶");

		mvc.perform(get("/settings/accounts"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("艾咪的交割戶")))
			.andExpect(content().string(not(containsString("班的交割戶"))));
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editFormShowsCurrentValuesAndSaves() throws Exception {
		Long id = insertAccount("amy", "國泰交割戶");

		mvc.perform(get("/settings/accounts/{id}/edit", id))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"國泰交割戶\"")));

		mvc.perform(post("/settings/accounts/{id}", id)
				.param("name", "國泰交割戶")
				.param("type", "SETTLEMENT")
				.param("institution", "國泰證券松江分公司")
				.with(csrf()))
			.andExpect(redirectedUrl("/settings/accounts"));

		assertThat(jdbc.queryForObject("SELECT institution FROM cash_account WHERE id = ?", String.class, id))
			.isEqualTo("國泰證券松江分公司");
	}

	private Long insertAccount(String username, String name) {
		Long userId = users.findByUsername(username).orElseThrow().getId();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, ?, 'SETTLEMENT', '國泰證券', NOW())", userId, name);
		return jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ? AND name = ?", Long.class,
				userId, name);
	}

	private void createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
	}

}
