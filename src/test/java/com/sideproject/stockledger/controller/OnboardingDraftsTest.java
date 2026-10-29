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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 引導的草稿：上一步、下一步、先離開都先把表單暫存在 session，回來時填回去（設計文件 2026-10-25-onboarding 4）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class OnboardingDraftsTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private OnboardingDrafts drafts;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private AppUserDetailsService userDetailsService;

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
		jdbc.update("DELETE FROM rate_setting WHERE user_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		if (!users.existsByUsername("dr-amy")) {
			users.save(AppUser.create("dr-amy", encoder.encode("password123"), "dr-amy", Role.USER,
					LocalDateTime.now(clock)));
		}
		if (!users.existsByUsername("dr-ben")) {
			users.save(AppUser.create("dr-ben", encoder.encode("password123"), "dr-ben", Role.USER,
					LocalDateTime.now(clock)));
		}
		jdbc.update("UPDATE app_user SET onboarding_step = 'ACCOUNTS' WHERE username = 'dr-amy'");
	}

	// ---------- OnboardingDrafts 本身 ----------

	@Test
	@WithUserDetails(value = "dr-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void saveLoadAndClearPerFormKey() {
		MockHttpSession session = new MockHttpSession();
		drafts.save(session, "accounts", Map.of("name", new String[] { "玉山" }));
		drafts.save(session, "loans", Map.of("name", new String[] { "土銀信貸" }));

		assertThat(drafts.load(session, "accounts")).hasValueSatisfying(p -> assertThat(p.get("name")).containsExactly("玉山"));
		drafts.clear(session, "accounts");

		assertThat(drafts.load(session, "accounts")).isEmpty();
		assertThat(drafts.load(session, "loans")).isPresent();
		assertThat(drafts.load(new MockHttpSession(), "loans")).isEmpty();
	}

	/** CSRF token 與一次性表單代號不是使用者打的字，不收進草稿；收進去的是複本，之後改原本的陣列不影響草稿。 */
	@Test
	@WithUserDetails(value = "dr-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void draftSkipsTokensAndKeepsCopy() {
		MockHttpSession session = new MockHttpSession();
		String[] name = { "玉山" };
		Map<String, String[]> params = new HashMap<>();
		params.put("name", name);
		params.put("_csrf", new String[] { "abc" });
		params.put("formToken", new String[] { "t1" });
		drafts.save(session, "accounts", params);
		name[0] = "改掉";

		Map<String, String[]> loaded = drafts.load(session, "accounts").orElseThrow();
		assertThat(loaded).containsOnlyKeys("name");
		assertThat(loaded.get("name")).containsExactly("玉山");
	}

	/**
	 * 草稿只給寫的人：登入時 Spring Security 換 session id 但保留屬性，同一個瀏覽器沒登出就換人登入，
	 * 下一個人看不到上一個人打的財務資料。
	 */
	@Test
	void draftBelongsToItsUser() {
		MockHttpSession session = new MockHttpSession();
		try {
			loginAs("dr-amy");
			drafts.save(session, "accounts", Map.of("name", new String[] { "玉山" }));

			loginAs("dr-ben");
			assertThat(drafts.load(session, "accounts")).isEmpty();
			drafts.clear(session, "accounts");

			loginAs("dr-amy");
			assertThat(drafts.load(session, "accounts"))
				.hasValueSatisfying(p -> assertThat(p.get("name")).containsExactly("玉山"));
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}

	/** 草稿不會無限長大：名稱太長的參數不收，每個表單最多收 100 個欄位。 */
	@Test
	@WithUserDetails(value = "dr-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void draftIsCapped() {
		MockHttpSession session = new MockHttpSession();
		Map<String, String[]> params = new LinkedHashMap<>();
		params.put("x".repeat(101), new String[] { "太長" });
		for (int i = 0; i < 150; i++) {
			params.put("f" + i, new String[] { "v" });
		}
		drafts.save(session, "accounts", params);

		Map<String, String[]> loaded = drafts.load(session, "accounts").orElseThrow();
		assertThat(loaded).hasSize(100).containsKey("f0").containsKey("f99").doesNotContainKey("x".repeat(101));
	}

	/** 確認區塊的內容依代號收起來，拿一次就沒了；不收 CSRF 與代號；最多留 20 份，丟最舊的；不會混進一般草稿。 */
	@Test
	@WithUserDetails(value = "dr-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void confirmStashIsTakenOnceAndCapped() {
		MockHttpSession session = new MockHttpSession();
		String target = OnboardingDrafts.target("accounts", null, null);
		drafts.stashForConfirm(session, "t0", target, Map.of("name", new String[] { "玉山" }, "_csrf", new String[] { "x" }));
		for (int i = 1; i <= FormTokens.MAX_TOKENS; i++) {
			drafts.stashForConfirm(session, "t" + i, target, Map.of("name", new String[] { "n" + i }));
		}

		assertThat(drafts.takeConfirmed(session, "t0", target)).isEmpty();
		assertThat(drafts.takeConfirmed(session, "t1", target))
			.hasValueSatisfying(p -> assertThat(p).containsOnlyKeys("name"));
		assertThat(drafts.takeConfirmed(session, "t1", target)).isEmpty();
		assertThat(drafts.takeConfirmed(session, null, target)).isEmpty();
		// 別的存檔目標拿不到（而且拿過就沒了）；同一步的其他按鈕拿得到
		assertThat(drafts.takeConfirmed(session, "t2", OnboardingDrafts.target("accounts", 5L, null))).isEmpty();
		assertThat(drafts.takeConfirmed(session, "t2", target)).isEmpty();
		assertThat(drafts.takeConfirmedForStep(session, "t3", "loans")).isEmpty();
		assertThat(drafts.takeConfirmedForStep(session, "t4", "accounts")).isPresent();
		assertThat(drafts.load(session, "accounts")).isEmpty();
	}

	private void loginAs(String username) {
		UserDetails details = userDetailsService.loadUserByUsername(username);
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	// ---------- POST /welcome/{step}/go ----------

	/** 「上一步」：只存草稿、不驗證、不建立資料，再轉到目的地。 */
	@Test
	@WithUserDetails(value = "dr-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void goSavesDraftAndRedirects() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/welcome/accounts/go").param("to", "welcome").param("name", "玉山").session(session)
			.with(csrf()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/welcome"));

		// MockMvc 請求結束會清掉這個執行緒的登入資訊；草稿依登入者存，讀之前再以同一個人登入
		loginAs("dr-amy");
		try {
			assertThat(drafts.load(session, "accounts").orElseThrow().get("name")).containsExactly("玉山");
		}
		finally {
			SecurityContextHolder.clearContext();
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_account", Integer.class)).isZero();
	}

	/** 「先離開」：一樣存草稿，回總覽；目的地只能是引導裡固定的幾個地方。 */
	@Test
	@WithUserDetails(value = "dr-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void goDestinations() throws Exception {
		mvc.perform(post("/welcome/accounts/go").param("to", "home").with(csrf())).andExpect(redirectedUrl("/"));
		// 帳戶頁要先有證券交割戶才能到借款（Task 4，OnboardingControllerTest.nextNeedsSettlementAccount）
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "SELECT id, '玉山交割戶', 'SETTLEMENT', '玉山證券', NOW() FROM app_user WHERE username = 'dr-amy'");
		mvc.perform(post("/welcome/accounts/go").param("to", "loans").with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));
		mvc.perform(post("/welcome/loans/go").param("to", "stocks").with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"));
		// 沒有 /welcome/loans/new 這一頁，所以不是目的地：回到同一頁
		mvc.perform(post("/welcome/loans/go").param("to", "loans-new").with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));
		mvc.perform(post("/welcome/stocks/go").param("to", "done").with(csrf()))
			.andExpect(redirectedUrl("/welcome/done"));
		// 不認得的目的地（例如外部網址）回到同一頁，不會轉到別的網站
		mvc.perform(post("/welcome/accounts/go").param("to", "https://evil.example").with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts"));
		mvc.perform(post("/welcome/nope/go").param("to", "welcome").with(csrf())).andExpect(status().isNotFound());
	}

	@Test
	@WithUserDetails(value = "dr-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void goNeedsCsrf() throws Exception {
		mvc.perform(post("/welcome/accounts/go").param("to", "welcome")).andExpect(status().isForbidden());
	}

	@Test
	@WithUserDetails(value = "dr-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void doneUserGoIsSentHome() throws Exception {
		jdbc.update("UPDATE app_user SET onboarding_step = 'DONE' WHERE username = 'dr-amy'");

		mvc.perform(post("/welcome/accounts/go").param("to", "loans").with(csrf())).andExpect(redirectedUrl("/"));
	}

	/** Review Focus 2：打到一半按上一步、再回來欄位原樣在；儲存成功後草稿清掉。 */
	@Test
	@WithUserDetails(value = "dr-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void draftSurvivesBackAndClearsAfterSave() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/welcome/accounts/go").param("to", "welcome").param("name", "玉山")
			.param("type", "SETTLEMENT").param("institution", "").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome"));
		mvc.perform(get("/welcome/accounts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"玉山\"")));

		String token = (String) mvc.perform(post("/welcome/accounts/preview").param("name", "玉山")
			.param("type", "SETTLEMENT").param("institution", "玉山證券").session(session).with(csrf()))
			.andExpect(status().isOk())
			.andReturn()
			.getModelAndView()
			.getModel()
			.get("formToken");
		mvc.perform(post("/welcome/accounts/save").param("name", "玉山").param("type", "SETTLEMENT")
			.param("institution", "玉山證券").param("formToken", token).session(session).with(csrf()))
			.andExpect(status().isFound());

		mvc.perform(get("/welcome/accounts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("value=\"玉山\""))));
	}

}
