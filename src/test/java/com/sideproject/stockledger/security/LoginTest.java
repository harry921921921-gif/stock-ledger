package com.sideproject.stockledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
class LoginTest {

	private static final String PASSWORD = "password123";

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
	void createUsers() {
		createIfMissing("alice");
		createIfMissing("dave");
		jdbc.update("UPDATE app_user SET disabled = TRUE WHERE username = 'dave'");
		createIfMissing("bob", "鮑伯");
	}

	private void createIfMissing(String username) {
		createIfMissing(username, username);
	}

	private void createIfMissing(String username, String displayName) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode(PASSWORD), displayName, Role.USER,
					LocalDateTime.now(clock)));
		}
	}

	@Test
	void anonymousIsRedirectedToLogin() throws Exception {
		mvc.perform(get("/settings"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void loginWithCorrectPassword() throws Exception {
		mvc.perform(formLogin("/login").user("alice").password(PASSWORD))
			.andExpect(authenticated().withUsername("alice"));
	}

	@Test
	void loginIsCaseAndSpaceInsensitive() throws Exception {
		mvc.perform(formLogin("/login").user(" Alice ").password(PASSWORD))
			.andExpect(authenticated().withUsername("alice"));
	}

	@Test
	void wrongPasswordFails() throws Exception {
		mvc.perform(formLogin("/login").user("alice").password("wrong-password"))
			.andExpect(unauthenticated())
			.andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void disabledUserCannotLogin() throws Exception {
		mvc.perform(formLogin("/login").user("dave").password(PASSWORD))
			.andExpect(unauthenticated());
	}

	@Test
	void rememberMeCookieIssuedFor30Days() throws Exception {
		mvc.perform(post("/login")
				.param("username", "alice")
				.param("password", PASSWORD)
				.param("remember-me", "on")
				.with(csrf()))
			.andExpect(authenticated())
			.andExpect(cookie().maxAge("remember-me", 30 * 24 * 3600));
	}

	@Test
	@WithUserDetails(value = "alice", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void normalUserCannotOpenUserAdmin() throws Exception {
		mvc.perform(get("/settings/users"))
			.andExpect(status().isForbidden());
	}

	@Test
	void loginPageIsPublic() throws Exception {
		mvc.perform(get("/login"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("帳號")))
			.andExpect(content().string(containsString("密碼")))
			.andExpect(content().string(containsString("記住我")));
	}

	@Test
	@WithUserDetails(value = "bob", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void settingsPageShowsCurrentUserAndNav() throws Exception {
		mvc.perform(get("/settings"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("總覽")))
			.andExpect(content().string(containsString("資金來源")))
			.andExpect(content().string(containsString("登出")))
			.andExpect(content().string(containsString("鮑伯")))
			.andExpect(content().string(containsString(">bob<")));
	}

	@Test
	void rememberMeCookieLogsInAutomatically() throws Exception {
		Cookie rememberMe = loginWithRememberMe("alice");

		mvc.perform(get("/settings").cookie(rememberMe))
			.andExpect(status().isOk());
	}

	@Test
	void rememberMeCookieRejectedAfterUserDisabled() throws Exception {
		createIfMissing("erin");
		jdbc.update("UPDATE app_user SET disabled = FALSE WHERE username = 'erin'");
		Cookie rememberMe = loginWithRememberMe("erin");
		jdbc.update("UPDATE app_user SET disabled = TRUE WHERE username = 'erin'");

		mvc.perform(get("/settings").cookie(rememberMe))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	/** 錯誤頁不能要求登入，否則逾時後送出登入表單，登入完會被導到 /error（審核發現的問題）。 */
	@Test
	void errorPageIsPublicAndInChinese() throws Exception {
		mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
				.accept(MediaType.TEXT_HTML))
			.andExpect(status().isNotFound())
			.andExpect(content().string(containsString("找不到這個頁面")));
	}

	private Cookie loginWithRememberMe(String username) throws Exception {
		Cookie cookie = mvc.perform(post("/login")
				.param("username", username)
				.param("password", PASSWORD)
				.param("remember-me", "on")
				.with(csrf()))
			.andExpect(authenticated())
			.andReturn().getResponse().getCookie("remember-me");
		assertThat(cookie).isNotNull();
		return cookie;
	}

}
