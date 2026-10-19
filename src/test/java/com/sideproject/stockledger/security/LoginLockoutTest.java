package com.sideproject.stockledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;

/** spec 4.10-5：連續登入失敗 5 次鎖定 15 分鐘。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfig.class, LoginLockoutTest.ClockTestConfig.class })
class LoginLockoutTest {

	private static final String PASSWORD = "password123";

	private static final Instant START = Instant.parse("2026-10-19T01:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private MutableClock clock;

	@BeforeEach
	void resetClock() {
		clock.setInstant(START);
	}

	@Test
	void correctPasswordRejectedWhileLocked() throws Exception {
		createUser("locked1");
		failFiveTimes("locked1");

		mvc.perform(formLogin("/login").user("locked1").password(PASSWORD))
			.andExpect(unauthenticated())
			.andExpect(redirectedUrl("/login?locked"));
	}

	/** 鎖定期間不論密碼對錯都顯示同一句鎖定訊息（見 ledger Task 3 Ruling）。 */
	@Test
	void wrongPasswordWhileLockedShowsLockedMessage() throws Exception {
		createUser("locked2");
		failFiveTimes("locked2");

		mvc.perform(formLogin("/login").user("locked2").password("wrong-password"))
			.andExpect(unauthenticated())
			.andExpect(redirectedUrl("/login?locked"));
	}

	@Test
	void canLoginAfterLockExpires() throws Exception {
		createUser("locked3");
		failFiveTimes("locked3");
		clock.advance(Duration.ofMinutes(15));

		mvc.perform(formLogin("/login").user("locked3").password(PASSWORD))
			.andExpect(authenticated().withUsername("locked3"));

		AppUser user = users.findByUsername("locked3").orElseThrow();
		assertThat(user.getFailedLoginCount()).isZero();
		assertThat(user.getLockedUntil()).isNull();
	}

	@Test
	void fourFailuresStillAllowLogin() throws Exception {
		createUser("locked4");
		for (int i = 0; i < 4; i++) {
			failOnce("locked4");
		}

		mvc.perform(formLogin("/login").user("locked4").password(PASSWORD))
			.andExpect(authenticated().withUsername("locked4"));

		// 登入成功後次數從 4 歸零（鎖定時次數本來就會歸零，所以要在這裡驗證）
		assertThat(users.findByUsername("locked4").orElseThrow().getFailedLoginCount()).isZero();
	}

	@Test
	void unknownUsernameDoesNotThrow() throws Exception {
		for (int i = 0; i < 6; i++) {
			mvc.perform(formLogin("/login").user("nobody").password("wrong-password"))
				.andExpect(unauthenticated())
				.andExpect(redirectedUrl("/login?error"));
		}
	}

	private void createUser(String username) {
		users.save(AppUser.create(username, encoder.encode(PASSWORD), username, Role.USER,
				LocalDateTime.now(clock)));
	}

	private void failFiveTimes(String username) throws Exception {
		for (int i = 0; i < 5; i++) {
			failOnce(username);
		}
	}

	private void failOnce(String username) throws Exception {
		mvc.perform(formLogin("/login").user(username).password("wrong-password"))
			.andExpect(unauthenticated())
			.andExpect(redirectedUrl("/login?error"));
	}

	/** 可以調整時間的 Clock，用來測試「15 分鐘後解鎖」而不用真的等。 */
	static class MutableClock extends Clock {

		private final ZoneId zone = ZoneId.of("Asia/Taipei");

		private volatile Instant instant = START;

		void setInstant(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			this.instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			MutableClock source = this;
			return new Clock() {
				@Override
				public ZoneId getZone() {
					return zone;
				}

				@Override
				public Clock withZone(ZoneId other) {
					return source.withZone(other);
				}

				@Override
				public Instant instant() {
					return source.instant();
				}
			};
		}

		@Override
		public Instant instant() {
			return instant;
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockTestConfig {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}

	}

}
