package com.sideproject.stockledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RateSettingControllerTest {

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

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM rate_setting WHERE user_id IS NOT NULL");
		if (!users.existsByUsername("amy")) {
			users.save(AppUser.create("amy", encoder.encode("password123"), "amy", Role.USER,
					LocalDateTime.now(clock)));
		}
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void pageShowsLegalAndPersonalSections() throws Exception {
		// 自己放一筆一年後的版本，不依賴 seed 裡的未來日期（2028 年後 seed 就沒有未來版本了）
		jdbc.update("INSERT INTO rate_setting (user_id, kind, value, effective_date, created_at) VALUES (?, 'MIN_FEE_ODD_LOT', 2, ?, NOW())",
				users.findByUsername("amy").orElseThrow().getId(), java.time.LocalDate.now(clock).plusYears(1));
		mvc.perform(get("/settings/rates"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("法定費率")))
			.andExpect(content().string(containsString("個人費率")))
			.andExpect(content().string(containsString("0.1425%")))
			.andExpect(content().string(containsString("未設定")))
			.andExpect(content().string(containsString("尚未生效")));
	}

	/** 新增表單只能選個人費率；法定費率不出現在下拉選單。 */
	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formOffersOnlyPersonalKinds() throws Exception {
		mvc.perform(get("/settings/rates"))
			.andExpect(content().string(containsString("<option value=\"BROKERAGE_DISCOUNT\"")))
			.andExpect(content().string(not(containsString("<option value=\"SECURITIES_TAX_STOCK\""))));
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void addPersonalVersionRedirects() throws Exception {
		mvc.perform(post("/settings/rates")
				.param("kind", "BROKERAGE_DISCOUNT")
				.param("value", "28")
				.param("effectiveDate", "2026-10-01")
				.with(csrf()))
			.andExpect(redirectedUrl("/settings/rates"));

		mvc.perform(get("/settings/rates"))
			.andExpect(content().string(containsString("28%")))
			.andExpect(content().string(containsString("個人")));
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void legalKindPostRejected() throws Exception {
		mvc.perform(post("/settings/rates")
				.param("kind", "SECURITIES_TAX_STOCK")
				.param("value", "0.1")
				.param("effectiveDate", "2026-10-01")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("法定費率不能修改")));

		assertThat(personalRowCount()).isZero();
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void outOfRangeDiscountShowsError() throws Exception {
		mvc.perform(post("/settings/rates")
				.param("kind", "BROKERAGE_DISCOUNT")
				.param("value", "150")
				.param("effectiveDate", "2026-10-01")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("手續費折扣要大於 0、最多 100")));

		assertThat(personalRowCount()).isZero();
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void malformedValueShowsChineseError() throws Exception {
		mvc.perform(post("/settings/rates")
				.param("kind", "BROKERAGE_DISCOUNT")
				.param("value", "abc")
				.param("effectiveDate", "2026-10-01")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("請輸入數字")))
			.andExpect(content().string(not(containsString("java."))));

		assertThat(personalRowCount()).isZero();
	}

	/** 只要有一個欄位格式錯誤，Spring 就不會建出 record，其他欄位的檢查要等那個錯誤改好才會出現，所以分開測。 */
	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void missingDateShowsChineseError() throws Exception {
		mvc.perform(post("/settings/rates")
				.param("kind", "BROKERAGE_DISCOUNT")
				.param("value", "28")
				.param("effectiveDate", "")
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("請選擇生效日期")));

		assertThat(personalRowCount()).isZero();
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void postRequiresCsrf() throws Exception {
		mvc.perform(post("/settings/rates")
				.param("kind", "BROKERAGE_DISCOUNT")
				.param("value", "28")
				.param("effectiveDate", "2026-10-01"))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void settingsIndexLinksToRates() throws Exception {
		mvc.perform(get("/settings"))
			.andExpect(content().string(containsString("href=\"/settings/rates\"")));
	}

	@Test
	@WithUserDetails(value = "amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formLoadsDoubleSubmitGuard() throws Exception {
		mvc.perform(get("/settings/rates"))
			.andExpect(content().string(containsString("src=\"/js/app.js\"")))
			.andExpect(content().string(containsString("data-busy-text=\"儲存中…\"")));
	}

	private int personalRowCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM rate_setting WHERE user_id IS NOT NULL", Integer.class);
	}

}
