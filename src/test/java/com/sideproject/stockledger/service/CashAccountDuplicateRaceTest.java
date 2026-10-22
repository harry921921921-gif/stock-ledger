package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.repository.CashAccountRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/**
 * 連點兩次「儲存」時，兩個請求可能同時通過「名稱有沒有重複」的檢查，第二個才在資料庫被唯一限制擋下。
 * 這種時間巧合很難穩定重現，所以讓重複檢查假裝回答「沒有重複」，直接撞上資料庫的唯一限制。
 * 預期：顯示「帳戶名稱已存在」，而不是 500 錯誤頁。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CashAccountDuplicateRaceTest {

	@Autowired
	private CashAccountService service;

	@MockitoSpyBean
	private CashAccountRepository accounts;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private AppUserDetailsService userDetailsService;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Clock clock;

	@BeforeEach
	void setUp() {
		// 先刪參照帳戶的現金異動，否則外鍵會擋下
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		if (!users.existsByUsername("amy")) {
			users.save(AppUser.create("amy", encoder.encode("password123"), "amy", Role.USER,
					LocalDateTime.now(clock)));
		}
		UserDetails details = userDetailsService.loadUserByUsername("amy");
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void createRaceShowsDuplicateMessage() {
		service.create(form("國泰交割戶"));
		doReturn(false).when(accounts).existsByUserIdAndName(any(), any());

		assertThatThrownBy(() -> service.create(form("國泰交割戶")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("帳戶名稱已存在");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_account", Integer.class)).isEqualTo(1);
	}

	@Test
	void updateRaceShowsDuplicateMessage() {
		service.create(form("國泰交割戶"));
		Long otherId = service.create(form("薪資帳戶")).getId();
		doReturn(false).when(accounts).existsByUserIdAndNameAndIdNot(any(), any(), any());

		assertThatThrownBy(() -> service.update(otherId, form("國泰交割戶")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("帳戶名稱已存在");
		assertThat(jdbc.queryForObject("SELECT name FROM cash_account WHERE id = ?", String.class, otherId))
			.isEqualTo("薪資帳戶");
	}

	private static CashAccountForm form(String name) {
		return new CashAccountForm(name, CashAccountType.SETTLEMENT, "國泰證券");
	}

}
