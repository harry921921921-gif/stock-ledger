package com.sideproject.stockledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
class AppUserRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 9, 0);

	@Autowired
	private AppUserRepository repo;

	@Test
	void savesAndFindsByUsername() {
		repo.save(AppUser.create("alice", "{bcrypt}x", "Alice", Role.USER, NOW));

		assertThat(repo.findByUsername("alice")).isPresent();
	}

	@Test
	void usernameIsNormalized() {
		assertThat(AppUser.normalizeUsername("  Alice ")).isEqualTo("alice");
	}

	@Test
	void createStoresNormalizedUsername() {
		AppUser saved = repo.saveAndFlush(AppUser.create(" Alice ", "h", "Alice", Role.USER, NOW));

		assertThat(repo.findByUsername("alice")).get().extracting(AppUser::getId).isEqualTo(saved.getId());
	}

	@Test
	void accentedUsernameIsDistinct() {
		repo.saveAndFlush(AppUser.create("josé", "h", "A", Role.USER, NOW));

		assertThat(repo.findByUsername("jose")).isEmpty();
	}

	@Test
	void duplicateUsernameViolatesUniqueConstraint() {
		repo.saveAndFlush(AppUser.create("alice", "h", "A", Role.USER, NOW));

		assertThatThrownBy(() -> repo.saveAndFlush(AppUser.create("alice", "h", "B", Role.USER, NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
