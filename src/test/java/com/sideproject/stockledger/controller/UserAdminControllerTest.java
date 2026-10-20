package com.sideproject.stockledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import org.springframework.mock.web.MockHttpSession;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class UserAdminControllerTest {

	private static final String PASSWORD = "password123";

	@Autowired
	private MockMvc mvc;

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
		createIfMissing("boss", Role.ADMIN);
		createIfMissing("amy", Role.USER);
	}

	/** 管理者建立 uc-bob → bob 登入 → 看不到 amy 的現金帳戶（階段 1 目標：兩個帳號互相看不到）。 */
	@Test
	void newUserCanLoginAndSeesNoOtherData() throws Exception {
		Long amyId = users.findByUsername("amy").orElseThrow().getId();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '艾咪的秘密帳戶', 'SETTLEMENT', '國泰證券', NOW())", amyId);

		mvc.perform(post("/settings/users")
				.param("username", "uc-bob")
				.param("displayName", "鮑伯")
				.param("password", "bob-secret-1")
				.param("role", "USER")
				.with(csrf())
				.with(user(userDetailsService.loadUserByUsername("boss"))))
			.andExpect(redirectedUrl("/settings/users"))
			.andExpect(flash().attribute("message", "已建立使用者 uc-bob"));

		MockHttpSession bobSession = (MockHttpSession) mvc.perform(formLogin("/login").user("uc-bob").password("bob-secret-1"))
			.andExpect(authenticated().withUsername("uc-bob"))
			.andReturn().getRequest().getSession();

		mvc.perform(get("/settings/accounts").session(bobSession))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("艾咪的秘密帳戶"))));
	}

	@Test
	@WithUserDetails(value = "boss", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void shortPasswordShowsError() throws Exception {
		mvc.perform(post("/settings/users")
				.param("username", "uc-short")
				.param("displayName", "短密碼")
				.param("password", "1234567")
				.param("role", "USER")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("密碼至少 8 個字")))
			// 送出失敗回到頁面時，不能把剛輸入的密碼印回畫面
			.andExpect(content().string(not(containsString("1234567"))));

		assertThat(users.existsByUsername("uc-short")).isFalse();
	}

	@Test
	@WithUserDetails(value = "boss", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void duplicateUsernameShowsError() throws Exception {
		mvc.perform(post("/settings/users")
				.param("username", "Amy")
				.param("displayName", "另一個艾咪")
				.param("password", PASSWORD)
				.param("role", "USER")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("帳號已存在")));
	}

	@Test
	void resetPasswordThenUserLogsInWithNewPassword() throws Exception {
		createIfMissing("uc-gina", Role.USER);
		Long id = users.findByUsername("uc-gina").orElseThrow().getId();

		mvc.perform(post("/settings/users/{id}/password", id)
				.param("newPassword", "gina-new-pass")
				.with(csrf())
				.with(user(userDetailsService.loadUserByUsername("boss"))))
			.andExpect(redirectedUrl("/settings/users"))
			.andExpect(flash().attribute("message", "已重設 uc-gina 的密碼"));

		mvc.perform(formLogin("/login").user("uc-gina").password("gina-new-pass"))
			.andExpect(authenticated().withUsername("uc-gina"));
	}

	@Test
	@WithUserDetails(value = "boss", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void shortResetPasswordShowsError() throws Exception {
		createIfMissing("uc-hank", Role.USER);
		Long id = users.findByUsername("uc-hank").orElseThrow().getId();

		mvc.perform(post("/settings/users/{id}/password", id)
				.param("newPassword", "short")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("重設 uc-hank 的密碼失敗")))
			.andExpect(content().string(containsString("密碼至少 8 個字")));
	}

	@Test
	@WithUserDetails(value = "boss", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void resetPasswordForUnknownUserIs404() throws Exception {
		mvc.perform(post("/settings/users/{id}/password", 999_999L)
				.param("newPassword", "new-secret-99")
				.with(csrf()))
			.andExpect(status().isNotFound());
	}

	@Test
	@WithUserDetails(value = "boss", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void pageListsUsersWithoutPasswordHash() throws Exception {
		mvc.perform(get("/settings/users"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("amy")))
			.andExpect(content().string(containsString("管理者")))
			.andExpect(content().string(not(containsString("{bcrypt}"))))
			.andExpect(content().string(containsString("src=\"/js/app.js\"")))
			.andExpect(content().string(containsString("autocomplete=\"new-password\"")));
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void nonAdminCannotCreateUser() throws Exception {
		mvc.perform(post("/settings/users")
				.param("username", "uc-hacker")
				.param("displayName", "駭客")
				.param("password", PASSWORD)
				.param("role", "ADMIN")
				.with(csrf()))
			.andExpect(status().isForbidden());

		assertThat(users.existsByUsername("uc-hacker")).isFalse();
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void nonAdminCannotOpenUserPage() throws Exception {
		mvc.perform(get("/settings/users"))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void nonAdminCannotResetPassword() throws Exception {
		AppUser boss = users.findByUsername("boss").orElseThrow();

		mvc.perform(post("/settings/users/{id}/password", boss.getId())
				.param("newPassword", "hacked-pass-1")
				.with(csrf()))
			.andExpect(status().isForbidden());

		assertThat(users.findByUsername("boss").orElseThrow().getPasswordHash()).isEqualTo(boss.getPasswordHash());
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void settingsIndexHidesUserAdminLinkFromNonAdmin() throws Exception {
		mvc.perform(get("/settings"))
			.andExpect(content().string(not(containsString("href=\"/settings/users\""))));
	}

	@Test
	@WithUserDetails(value = "boss", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void settingsIndexShowsUserAdminLinkToAdmin() throws Exception {
		mvc.perform(get("/settings"))
			.andExpect(content().string(containsString("href=\"/settings/users\"")));
	}

	private void createIfMissing(String username, Role role) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode(PASSWORD), username, role, LocalDateTime.now(clock)));
		}
	}

}
