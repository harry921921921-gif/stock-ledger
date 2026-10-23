package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 年配息次數是共用資料，只有管理者可以設定（2026-10-23 使用者決定）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class StockSettingServiceTest {

	@Autowired
	private StockSettingService service;

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
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM source_allocation");
		jdbc.update("DELETE FROM trade_revision");
		jdbc.update("DELETE FROM trade");
		jdbc.update("DELETE FROM pledge_collateral");
		jdbc.update("DELETE FROM stock_price");
		jdbc.update("DELETE FROM dividend");
		jdbc.update("DELETE FROM stock");
		jdbc.update("INSERT INTO stock (code, name, kind, updated_at) VALUES ('0056', '元大高股息', 'ETF', NOW())");
		createIfMissing("ss-admin", Role.ADMIN);
		createIfMissing("ss-user", Role.USER);
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void adminSetsAndClears() {
		loginAs("ss-admin");

		service.setDividendsPerYear("0056", 4);
		assertThat(perYear()).isEqualTo(4);

		service.setDividendsPerYear("0056", null);
		assertThat(perYear()).isNull();
	}

	@Test
	void rules() {
		loginAs("ss-admin");
		assertThatThrownBy(() -> service.setDividendsPerYear("0056", 13)).hasMessage("年配息次數要在 1～12 之間");
		assertThatThrownBy(() -> service.setDividendsPerYear("9999", 4)).hasMessage("找不到這個上市股票代號：9999");

		loginAs("ss-user");
		assertThatThrownBy(() -> service.setDividendsPerYear("0056", 4)).isInstanceOf(AccessDeniedException.class);
	}

	private Integer perYear() {
		return jdbc.queryForObject("SELECT dividends_per_year FROM stock WHERE code = '0056'", Integer.class);
	}

	private void loginAs(String username) {
		UserDetails details = userDetailsService.loadUserByUsername(username);
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	private void createIfMissing(String username, Role role) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, role,
					LocalDateTime.now(clock)));
		}
	}

}
