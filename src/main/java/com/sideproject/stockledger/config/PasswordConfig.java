package com.sideproject.stockledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密碼雜湊用 BCrypt（spec 4.10-1）。
 * 用 Delegating 版本：雜湊前面會加上 "{bcrypt}"，將來要換演算法時，舊密碼仍然可以驗證。
 */
@Configuration
public class PasswordConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
