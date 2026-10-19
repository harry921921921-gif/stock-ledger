package com.sideproject.stockledger.security;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.repository.AppUserRepository;

/** 登入時依帳號載入使用者；帳號先正規化，所以 " Alice " 和 "alice" 是同一個人。 */
@Service
public class AppUserDetailsService implements UserDetailsService {

	private final AppUserRepository users;
	private final Clock clock;

	public AppUserDetailsService(AppUserRepository users, Clock clock) {
		this.users = users;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String username) {
		AppUser user = users.findByUsername(AppUser.normalizeUsername(username))
			.orElseThrow(() -> new UsernameNotFoundException("找不到帳號"));
		return new AppUserDetails(user, LocalDateTime.now(clock));
	}

}
