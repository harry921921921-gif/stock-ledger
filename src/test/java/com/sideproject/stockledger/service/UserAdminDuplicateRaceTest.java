package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.NewUserForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/**
 * 連點「建立」時，兩個請求可能同時通過「帳號有沒有重複」的檢查，第二個才被資料庫唯一限制擋下。
 * 讓重複檢查假裝回答「沒有重複」，直接撞上唯一限制；預期顯示「帳號已存在」而不是 500 錯誤頁。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class UserAdminDuplicateRaceTest {

	@Autowired
	private UserAdminService service;

	@MockitoSpyBean
	private AppUserRepository users;

	@Autowired
	private AppUserDetailsService userDetailsService;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private Clock clock;

	@BeforeEach
	void loginAsAdmin() {
		if (!users.existsByUsername("boss")) {
			users.save(AppUser.create("boss", encoder.encode("password123"), "boss", Role.ADMIN,
					LocalDateTime.now(clock)));
		}
		UserDetails details = userDetailsService.loadUserByUsername("boss");
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void createRaceShowsDuplicateMessage() {
		service.create(new NewUserForm("race-ivy", "艾薇", "password123", Role.USER));
		doReturn(false).when(users).existsByUsername(any());

		assertThatThrownBy(() -> service.create(new NewUserForm("race-ivy", "艾薇 2", "password123", Role.USER)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("帳號已存在");
	}

}
