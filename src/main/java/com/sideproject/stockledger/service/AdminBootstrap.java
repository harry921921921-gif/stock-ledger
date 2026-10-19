package com.sideproject.stockledger.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;

/**
 * 系統第一次啟動時建立初始管理者（spec 4.10-2）。
 * 帳密來自 .env 的 ADMIN_USERNAME／ADMIN_PASSWORD；只有在 app_user 表是空的時候才執行，之後改 .env 不會影響已存在的帳號。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	/** 與 Task 7 建立使用者的密碼規則一致。 */
	private static final int MIN_PASSWORD_LENGTH = 8;

	private final AppUserRepository users;
	private final PasswordEncoder encoder;
	private final Clock clock;
	private final String username;
	private final String password;

	public AdminBootstrap(AppUserRepository users, PasswordEncoder encoder, Clock clock,
			@Value("${app.admin.username}") String username, @Value("${app.admin.password}") String password) {
		this.users = users;
		this.encoder = encoder;
		this.clock = clock;
		this.username = username;
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (users.count() > 0) {
			return;
		}
		if (isBlank(username) || isBlank(password)) {
			throw new IllegalStateException("尚未建立任何使用者：請在 .env 設定 ADMIN_USERNAME 與 ADMIN_PASSWORD 後重新啟動");
		}
		if (password.length() < MIN_PASSWORD_LENGTH) {
			throw new IllegalStateException("ADMIN_PASSWORD 至少要 " + MIN_PASSWORD_LENGTH + " 個字元");
		}
		AppUser admin = AppUser.create(username, encoder.encode(password), "管理者", Role.ADMIN, LocalDateTime.now(clock));
		users.save(admin);
		log.info("已建立初始管理者帳號：{}", admin.getUsername());
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

}
