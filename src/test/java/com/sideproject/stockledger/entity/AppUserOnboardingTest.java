package com.sideproject.stockledger.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class AppUserOnboardingTest {

	private AppUser newUser() {
		return AppUser.create("alice", "hash", "Alice", Role.USER, LocalDateTime.of(2026, 10, 28, 9, 0));
	}

	@Test
	void newUserStartsAtWelcome() {
		assertThat(newUser().getOnboardingStep()).isEqualTo(OnboardingStep.WELCOME);
	}

	@Test
	void advanceOnlyMovesForward() {
		AppUser user = newUser();

		user.advanceOnboarding(OnboardingStep.LOANS);
		user.advanceOnboarding(OnboardingStep.ACCOUNTS);

		assertThat(user.getOnboardingStep()).isEqualTo(OnboardingStep.LOANS);
	}

	@Test
	void advanceMovesToLaterStep() {
		AppUser user = newUser();

		user.advanceOnboarding(OnboardingStep.STOCKS);

		assertThat(user.getOnboardingStep()).isEqualTo(OnboardingStep.STOCKS);
	}

	@Test
	void finishSetsDone() {
		AppUser user = newUser();

		user.finishOnboarding();

		assertThat(user.getOnboardingStep()).isEqualTo(OnboardingStep.DONE);
	}

	@Test
	void isAfterComparesOrder() {
		assertThat(OnboardingStep.LOANS.isAfter(OnboardingStep.ACCOUNTS)).isTrue();
		assertThat(OnboardingStep.ACCOUNTS.isAfter(OnboardingStep.ACCOUNTS)).isFalse();
		assertThat(OnboardingStep.WELCOME.isAfter(OnboardingStep.DONE)).isFalse();
	}

	/** 每一步的網址（提醒連結、登入轉址都用這個）；擔保那一步由 controller 再轉到第一筆需要的質押。 */
	@Test
	void urlOfEachStep() {
		assertThat(OnboardingStep.WELCOME.url()).isEqualTo("/welcome");
		assertThat(OnboardingStep.ACCOUNTS.url()).isEqualTo("/welcome/accounts");
		assertThat(OnboardingStep.LOANS.url()).isEqualTo("/welcome/loans");
		assertThat(OnboardingStep.STOCKS.url()).isEqualTo("/welcome/stocks");
		assertThat(OnboardingStep.COLLATERAL.url()).isEqualTo("/welcome/collateral");
		assertThat(OnboardingStep.DONE.url()).isEqualTo("/");
	}

}
