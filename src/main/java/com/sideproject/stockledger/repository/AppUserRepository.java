package com.sideproject.stockledger.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sideproject.stockledger.entity.AppUser;

/** 參數都必須是已經用 {@link AppUser#normalizeUsername(String)} 正規化過的帳號。 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByUsername(String normalizedUsername);

	boolean existsByUsername(String normalizedUsername);

}
