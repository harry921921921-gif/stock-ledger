package com.sideproject.stockledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.service.LoanService;

/** 股票頁（spec 第 3 節第 3 頁、設計文件 2026-10-23-stocks 8）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class StockControllerTest {

	private static final Pattern TOKEN = Pattern.compile("name=\"formToken\" value=\"([^\"]+)\"");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private LoanService loans;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Clock clock;

	private LocalDate today;
	private Long amyId;
	private Long benId;
	private Long settlement;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM source_allocation");
		jdbc.update("DELETE FROM trade_revision");
		jdbc.update("DELETE FROM trade");
		jdbc.update("DELETE FROM pledge_collateral");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM rate_setting WHERE user_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		jdbc.update("DELETE FROM stock_price");
		jdbc.update("DELETE FROM dividend");
		jdbc.update("DELETE FROM stock");
		amyId = createIfMissing("sc-amy");
		benId = createIfMissing("sc-ben");
		if (!users.existsByUsername("sc-admin")) {
			users.save(AppUser.create("sc-admin", encoder.encode("password123"), "sc-admin", Role.ADMIN,
					LocalDateTime.now(clock)));
		}
		today = LocalDate.now(clock);
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '玉山交割戶', 'SETTLEMENT', '玉山證券', NOW())", amyId);
		settlement = jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ?", Long.class, amyId);
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0056', '元大高股息', "
				+ "'ETF', 4, NOW())");
	}

	/** 側欄的股票可以點；沒有交易時有說明。 */
	@Test
	@WithUserDetails(value = "sc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void emptyPage() throws Exception {
		mvc.perform(get("/stocks"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<a class=\"nav-item active\" href=\"/stocks\">股票</a>")))
			.andExpect(content().string(containsString("還沒有持股")))
			.andExpect(content().string(containsString("還沒有股價")));
	}

	/** 用表單買進並選資金來源：自動記交割戶扣款，股票頁列出持股與資金來源比例。 */
	@Test
	@WithUserDetails(value = "sc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void buyViaFormWithAllocation() throws Exception {
		Long pledge = loans.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING,
				new BigDecimal("400000"), today.minusDays(1), new BigDecimal("400000"), new BigDecimal("3"), null,
				settlement, 25, null, null, null)).getId();
		MockHttpSession session = new MockHttpSession();
		String form = mvc.perform(get("/stocks/trades/new").session(session)).andReturn().getResponse()
			.getContentAsString();
		assertThat(form).contains("name=\"allocations[0].amount\"").contains("玉山質押");

		mvc.perform(post("/stocks/trades/new").session(session).with(csrf()).param("formToken", token(form))
				.param("type", "BUY").param("date", today.toString()).param("stockCode", "0056")
				.param("shares", "1000").param("price", "58.75").param("accountId", settlement.toString())
				.param("allocations[0].loanId", pledge.toString()).param("allocations[0].amount", "50000"))
			.andExpect(redirectedUrl("/stocks"))
			.andExpect(flash().attribute("message", "已新增買進：0056"));

		assertThat(jdbc.queryForObject("SELECT amount FROM source_allocation", BigDecimal.class))
			.isEqualByComparingTo("50000");
		mvc.perform(get("/stocks"))
			.andExpect(content().string(containsString("0056 元大高股息")))
			.andExpect(content().string(containsString("58,833")))
			.andExpect(content().string(containsString("自有 15%、玉山質押 85%")));
		mvc.perform(get("/funding"))
			.andExpect(content().string(containsString("買進紀錄")))
			.andExpect(content().string(containsString(">58.75<")))
			.andExpect(content().string(containsString(">50,000<")));
	}

	/** 輸入錯誤留在表單並顯示原因；一次性代號還給使用者，修正後可以再送。 */
	@Test
	@WithUserDetails(value = "sc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void errorsStayOnForm() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = token(mvc.perform(get("/stocks/trades/new").session(session)).andReturn().getResponse()
			.getContentAsString());

		mvc.perform(post("/stocks/trades/new").session(session).with(csrf()).param("formToken", token)
				.param("type", "BUY").param("date", today.toString()).param("stockCode", "9999")
				.param("shares", "1000").param("price", "10").param("accountId", settlement.toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("找不到這個上市股票代號：9999")));
		mvc.perform(post("/stocks/trades/new").session(session).with(csrf()).param("formToken", token)
				.param("type", "BUY").param("date", today.toString()).param("stockCode", "0056")
				.param("shares", "1000").param("price", "10").param("accountId", settlement.toString()))
			.andExpect(redirectedUrl("/stocks"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade", Integer.class)).isEqualTo(1);
	}

	/** 修改（寫修改紀錄）、作廢；全部異動頁點交易自動記的那筆，會轉到交易的修改頁。 */
	@Test
	@WithUserDetails(value = "sc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editAndVoid() throws Exception {
		Long trade = buy();
		Long movement = jdbc.queryForObject("SELECT id FROM cash_movement WHERE trade_id = ?", Long.class, trade);
		mvc.perform(get("/assets/movements/{id}/edit", movement))
			.andExpect(redirectedUrl("/stocks/trades/" + trade + "/edit"));
		mvc.perform(get("/stocks/trades/{id}/edit", trade))
			.andExpect(content().string(containsString("修改交易")))
			.andExpect(content().string(containsString("value=\"58.75\"")));

		mvc.perform(post("/stocks/trades/{id}/edit", trade).with(csrf()).param("type", "BUY")
				.param("date", today.toString()).param("stockCode", "0056").param("shares", "1000")
				.param("price", "58.70").param("accountId", settlement.toString()).param("reason", "價格打錯"))
			.andExpect(redirectedUrl("/stocks"));
		assertThat(jdbc.queryForObject("SELECT amount FROM trade WHERE id = ?", BigDecimal.class, trade))
			.isEqualByComparingTo("58783");
		mvc.perform(get("/stocks")).andExpect(content().string(containsString("已修改")));

		mvc.perform(post("/stocks/trades/{id}/void", trade).with(csrf()).param("reason", "記錯"))
			.andExpect(flash().attribute("message", "已作廢這筆交易（自動記的現金異動也一起作廢）"));
		mvc.perform(get("/stocks")).andExpect(content().string(containsString("已作廢")));
	}

	/** 配息入帳：從提醒點進來會帶入金額；存好後算進累計配息。 */
	@Test
	@WithUserDetails(value = "sc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void recordDividendViaForm() throws Exception {
		buy();
		MockHttpSession session = new MockHttpSession();
		String form = mvc.perform(get("/stocks/dividends/new").session(session).param("code", "0056")
				.param("amount", "1720").param("nhi", "0")).andReturn().getResponse().getContentAsString();
		assertThat(form).contains("value=\"1720\"").contains("value=\"0056\"");

		mvc.perform(post("/stocks/dividends/new").session(session).with(csrf()).param("formToken", token(form))
				.param("stockCode", "0056").param("date", today.toString())
				.param("accountId", settlement.toString()).param("amount", "1720").param("nhi", "0"))
			.andExpect(redirectedUrl("/stocks"));

		mvc.perform(get("/stocks")).andExpect(content().string(containsString(">1,720<")));
	}

	/** 年配息次數是共用資料：一般使用者看不到設定表單，直接送也會被擋（403）。 */
	@Test
	@WithUserDetails(value = "sc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void dividendsPerYearIsAdminOnly() throws Exception {
		buy();
		mvc.perform(get("/stocks"))
			.andExpect(content().string(org.hamcrest.Matchers.not(containsString("dividends-per-year"))));
		mvc.perform(post("/stocks/0056/dividends-per-year").with(csrf()).param("times", "1"))
			.andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT dividends_per_year FROM stock WHERE code = '0056'", Integer.class))
			.isEqualTo(4);
	}

	/** 管理者：設定一年配息 12 次（setUp 是 4 次）。 */
	@Test
	@WithUserDetails(value = "sc-admin", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void adminSetsDividendsPerYear() throws Exception {
		mvc.perform(post("/stocks/0056/dividends-per-year").with(csrf()).param("times", "12"))
			.andExpect(redirectedUrl("/stocks"))
			.andExpect(flash().attribute("message", "已設定 0056 一年配息 12 次"));
		assertThat(jdbc.queryForObject("SELECT dividends_per_year FROM stock WHERE code = '0056'", Integer.class))
			.isEqualTo( 12);
	}

	/** 別人的交易：看不到也改不到（404）。 */
	@Test
	@WithUserDetails(value = "sc-ben", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void othersTradesAreNotFound() throws Exception {
		jdbc.update("INSERT INTO trade (user_id, type, trade_date, stock_id, account_id, shares, price, fee, amount, "
				+ "voided, created_at) SELECT ?, 'BUY', ?, id, ?, 1000, 10, 20, 10020, FALSE, NOW() FROM stock",
				amyId, today, settlement);
		Long trade = jdbc.queryForObject("SELECT id FROM trade", Long.class);

		mvc.perform(get("/stocks/trades/{id}/edit", trade)).andExpect(status().isNotFound());
		mvc.perform(post("/stocks/trades/{id}/void", trade).with(csrf())).andExpect(status().isNotFound());
		assertThat(benId).isNotEqualTo(amyId);
	}

	private Long buy() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = token(mvc.perform(get("/stocks/trades/new").session(session)).andReturn().getResponse()
			.getContentAsString());
		mvc.perform(post("/stocks/trades/new").session(session).with(csrf()).param("formToken", token)
				.param("type", "BUY").param("date", today.toString()).param("stockCode", "0056")
				.param("shares", "1000").param("price", "58.75").param("accountId", settlement.toString()))
			.andExpect(redirectedUrl("/stocks"));
		return jdbc.queryForObject("SELECT MAX(id) FROM trade", Long.class);
	}

	private static String token(String html) {
		Matcher m = TOKEN.matcher(html);
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private Long createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
		return users.findByUsername(username).orElseThrow().getId();
	}

}
