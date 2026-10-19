package com.sideproject.stockledger.security;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.repository.AppUserRepository;

/**
 * 記錄登入成功與失敗，達到次數就鎖定（spec 4.10-5）。
 * Spring Security 驗證完會發出事件，這裡監聽事件，不必改寫 Spring 的登入流程。
 * 鎖定中的帳號在檢查密碼前就被擋下（LockedException），不會發出密碼錯誤事件，所以鎖定期間不會累加次數。
 * 用 REQUIRES_NEW：就算之後在別的交易裡呼叫登入驗證，登入失敗拋例外時也不會把次數一起 rollback。
 */
@Component
public class LoginAttemptListener {

	private final AppUserRepository users;
	private final Clock clock;

	public LoginAttemptListener(AppUserRepository users, Clock clock) {
		this.users = users;
		this.clock = clock;
	}

	@EventListener
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
		// 帳號不存在時 Spring 也會發這個事件（不透露帳號存不存在），這時什麼都不做
		users.findByUsername(AppUser.normalizeUsername(event.getAuthentication().getName()))
			.ifPresent(user -> user.recordLoginFailure(LocalDateTime.now(clock)));
	}

	@EventListener
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onSuccess(AuthenticationSuccessEvent event) {
		users.findByUsername(AppUser.normalizeUsername(event.getAuthentication().getName()))
			.ifPresent(AppUser::recordLoginSuccess);
	}

}
