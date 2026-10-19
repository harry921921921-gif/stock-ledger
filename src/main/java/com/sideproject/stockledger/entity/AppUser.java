package com.sideproject.stockledger.entity;

import java.time.LocalDateTime;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 可登入系統的人（spec 6 AppUser）。 */
@Entity
@Table(name = "app_user")
public class AppUser {

	/** spec 4.10-5：連續登入失敗 5 次鎖定 15 分鐘。這是安全規則，不是費率，所以不放 RateSetting。 */
	public static final int MAX_FAILED = 5;

	public static final int LOCK_MINUTES = 15;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String username;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 50)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Role role;

	@Column(nullable = false)
	private boolean disabled;

	@Column(name = "failed_login_count", nullable = false)
	private int failedLoginCount;

	@Column(name = "locked_until")
	private LocalDateTime lockedUntil;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	protected AppUser() {
		// JPA 需要
	}

	public static AppUser create(String username, String passwordHash, String displayName, Role role,
			LocalDateTime now) {
		AppUser user = new AppUser();
		user.username = normalizeUsername(username);
		user.passwordHash = passwordHash;
		user.displayName = displayName;
		user.role = role;
		user.createdAt = now;
		return user;
	}

	/** 帳號比對規則：去掉前後空白、轉小寫。建立帳號和登入都要經過這裡，才不會出現 "Alice" 和 "alice" 兩個帳號。 */
	public static String normalizeUsername(String raw) {
		return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
	}

	/** 密碼錯第 MAX_FAILED 次就鎖 LOCK_MINUTES 分鐘，次數歸零；解鎖後再錯 MAX_FAILED 次才會再鎖。 */
	public void recordLoginFailure(LocalDateTime now) {
		failedLoginCount++;
		if (failedLoginCount >= MAX_FAILED) {
			lockedUntil = now.plusMinutes(LOCK_MINUTES);
			failedLoginCount = 0;
		}
	}

	public void recordLoginSuccess() {
		failedLoginCount = 0;
		lockedUntil = null;
	}

	/** 鎖到 lockedUntil 那一刻為止（不含）。 */
	public boolean isLockedAt(LocalDateTime now) {
		return lockedUntil != null && now.isBefore(lockedUntil);
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Role getRole() {
		return role;
	}

	public boolean isDisabled() {
		return disabled;
	}

	public int getFailedLoginCount() {
		return failedLoginCount;
	}

	public LocalDateTime getLockedUntil() {
		return lockedUntil;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
