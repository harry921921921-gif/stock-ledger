package com.sideproject.stockledger.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** 連續登入失敗鎖定的規則（spec 4.10-5）：純 Java，不需要 Spring。 */
class AppUserLockTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 9, 0);

	private final AppUser user = AppUser.create("alice", "hash", "alice", Role.USER, NOW);

	@Test
	void fourFailuresDoNotLock() {
		failTimes(4);

		assertThat(user.isLockedAt(NOW)).isFalse();
	}

	@Test
	void fifthFailureLocksFor15Minutes() {
		failTimes(5);

		assertThat(user.isLockedAt(NOW)).isTrue();
		assertThat(user.isLockedAt(NOW.plusMinutes(14).plusSeconds(59))).isTrue();
		assertThat(user.isLockedAt(NOW.plusMinutes(15))).isFalse();
	}

	@Test
	void successResetsCounter() {
		failTimes(4);
		user.recordLoginSuccess();
		failTimes(4);

		assertThat(user.isLockedAt(NOW)).isFalse();
	}

	@Test
	void neverFailedIsNotLocked() {
		assertThat(user.isLockedAt(NOW)).isFalse();
	}

	private void failTimes(int times) {
		for (int i = 0; i < times; i++) {
			user.recordLoginFailure(NOW);
		}
	}

}
