package com.sideproject.stockledger.config;

import java.util.Map;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.util.Assert;

import com.sideproject.stockledger.security.AppUserDetailsService;

@Configuration
public class SecurityConfig {

	/** spec 4.10-5：「記住我」有效 30 天。 */
	static final int REMEMBER_ME_SECONDS = 30 * 24 * 3600;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, AppUserDetailsService userDetailsService,
			@Value("${app.remember-me.key}") String rememberMeKey) throws Exception {
		Assert.hasText(rememberMeKey, "請在 .env 設定 REMEMBER_ME_KEY（一長串隨機字元）");

		http
			.authorizeHttpRequests(auth -> auth
				// 錯誤頁要公開：不然逾時後送出登入表單會被導回登入頁，而且登入完會停在 /error
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers("/login", "/css/**", "/error").permitAll()
				.requestMatchers("/settings/users/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			.formLogin(form -> form
				.loginPage("/login")
				.defaultSuccessUrl("/", false)
				// 鎖定中顯示「請 15 分鐘後再試」；其他失敗一律「帳號或密碼錯誤」，不透露帳號存不存在
				.failureHandler(loginFailureHandler())
				.permitAll())
			// 雜湊 token：cookie 內含帳號、到期時間與簽章；改密碼後舊 cookie 自動失效。
			// 不用 persistent_logins 表，因為它沒有流水號主鍵、預設 schema 還用 TIMESTAMP（違反 spec 7.2）。
			.rememberMe(remember -> remember
				.key(rememberMeKey)
				.tokenValiditySeconds(REMEMBER_ME_SECONDS)
				.userDetailsService(userDetailsService))
			.logout(logout -> logout
				.logoutSuccessUrl("/login?logout"));
		return http.build();
	}

	private static AuthenticationFailureHandler loginFailureHandler() {
		ExceptionMappingAuthenticationFailureHandler handler = new ExceptionMappingAuthenticationFailureHandler();
		handler.setDefaultFailureUrl("/login?error");
		handler.setExceptionMappings(Map.of(LockedException.class.getName(), "/login?locked"));
		return handler;
	}

}
