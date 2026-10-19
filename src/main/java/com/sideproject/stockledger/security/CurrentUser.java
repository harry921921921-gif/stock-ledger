package com.sideproject.stockledger.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Service 取得「目前登入者」的唯一入口（spec 4.10-3）。
 * user_id 只能從這裡來，不接受畫面或參數傳進來的值，所以 Service 方法的參數不會有 userId。
 */
@Component
public class CurrentUser {

	public Long id() {
		return details().getId();
	}

	public boolean isAdmin() {
		return details().isAdmin();
	}

	private AppUserDetails details() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails details)) {
			throw new IllegalStateException("目前沒有登入的使用者");
		}
		return details;
	}

}
