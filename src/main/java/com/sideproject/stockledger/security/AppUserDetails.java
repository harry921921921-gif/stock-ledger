package com.sideproject.stockledger.security;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;

/**
 * Spring Security 看到的登入者。只複製需要的欄位，不把 JPA Entity 放進 session。
 */
public class AppUserDetails implements UserDetails {

	private final Long id;
	private final String username;
	private final String passwordHash;
	private final String displayName;
	private final boolean admin;
	private final boolean enabled;
	private final boolean accountNonLocked;

	public AppUserDetails(AppUser user, LocalDateTime now) {
		this.id = user.getId();
		this.username = user.getUsername();
		this.passwordHash = user.getPasswordHash();
		this.displayName = user.getDisplayName();
		this.admin = user.getRole() == Role.ADMIN;
		this.enabled = !user.isDisabled();
		this.accountNonLocked = !user.isLockedAt(now);
	}

	public Long getId() {
		return id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public boolean isAdmin() {
		return admin;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority(admin ? "ROLE_ADMIN" : "ROLE_USER"));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public boolean isAccountNonLocked() {
		return accountNonLocked;
	}

}
