package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
class AdminBootstrapTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneId.of("Asia/Taipei"));

	private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

	@Autowired
	private AppUserRepository users;

	private AdminBootstrap bootstrap(String username, String password) {
		return new AdminBootstrap(users, encoder, CLOCK, username, password);
	}

	@Test
	void createsAdminWhenNoUsers() throws Exception {
		bootstrap("Admin", "secret-password").run(new DefaultApplicationArguments());

		AppUser admin = users.findByUsername("admin").orElseThrow();
		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(admin.getDisplayName()).isEqualTo("管理者");
		assertThat(encoder.matches("secret-password", admin.getPasswordHash())).isTrue();
	}

	@Test
	void doesNothingWhenUsersExist() throws Exception {
		users.save(AppUser.create("alice", "h", "Alice", Role.USER, LocalDateTime.now(CLOCK)));

		bootstrap("admin", "secret-password").run(new DefaultApplicationArguments());

		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void failsWhenPasswordShorterThan8() {
		assertThatThrownBy(() -> bootstrap("admin", "1234567").run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("8");
		assertThat(users.count()).isZero();
	}

	@Test
	void failsWhenNoUsersAndNoCredentials() {
		assertThatThrownBy(() -> bootstrap("", "").run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ADMIN_USERNAME");
	}

}
