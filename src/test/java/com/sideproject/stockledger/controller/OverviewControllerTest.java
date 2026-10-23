package com.sideproject.stockledger.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;

/** 總覽頁（spec 第 3 節第 1 頁）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class OverviewControllerTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Clock clock;

	private Long amyId;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM source_allocation");
		jdbc.update("DELETE FROM trade_revision");
		jdbc.update("DELETE FROM trade");
		jdbc.update("DELETE FROM pledge_collateral");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		amyId = createIfMissing("ov-amy");
		today = LocalDate.now(clock);
	}

	/** 登入後的首頁是總覽：淨資產 ＝ 現金（沒有股票、借款）；本月存入算進淨資產變動；有資金移動區塊。 */
	@Test
	@WithUserDetails(value = "ov-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void overviewShowsNetWorthAndMonthChange() throws Exception {
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '薪資帳戶', 'BANK_DEMAND', '台銀', NOW())", amyId);
		Long account = jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ?", Long.class, amyId);
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, voided, created_at) "
				+ "VALUES (?, 'OPENING', ?, ?, 100000, FALSE, NOW())", amyId, today.withDayOfMonth(1), account);
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, voided, created_at) "
				+ "VALUES (?, 'DEPOSIT', ?, ?, 5000, FALSE, NOW())", amyId, today, account);

		mvc.perform(get("/"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<a class=\"nav-item active\" href=\"/\">總覽</a>")))
			.andExpect(content().string(containsString(">105,000<")))
			.andExpect(content().string(containsString(">+5,000<")))
			.andExpect(content().string(containsString("淨資產變動拆解")))
			.andExpect(content().string(containsString("資金移動")));
	}

	/** 切換到上個月；未來的月份當作本月。 */
	@Test
	@WithUserDetails(value = "ov-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void switchMonths() throws Exception {
		YearMonth last = YearMonth.from(today).minusMonths(1);
		mvc.perform(get("/").param("month", last.toString()))
			.andExpect(content().string(containsString(last + " 淨資產變動")))
			.andExpect(content().string(containsString("下個月 →")));
		mvc.perform(get("/").param("month", YearMonth.from(today).plusMonths(3).toString()))
			.andExpect(content().string(containsString(YearMonth.from(today) + " 淨資產變動")));
	}

	private Long createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
		return users.findByUsername(username).orElseThrow().getId();
	}

}
