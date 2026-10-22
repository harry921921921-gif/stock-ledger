package com.sideproject.stockledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import java.time.YearMonth;
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
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;

/** 資金來源頁（spec 第 3 節第 4 頁、設計文件 7）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class FundingControllerTest {

	private static final Pattern TOKEN = Pattern.compile("name=\"formToken\" value=\"([^\"]+)\"");

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

	private LocalDate today;
	private YearMonth thisMonth;
	private Long amyId;
	private Long bank;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		amyId = createIfMissing("fc-amy");
		createIfMissing("fc-ben");
		today = LocalDate.now(clock);
		thisMonth = YearMonth.from(today);
		bank = insertAccount(amyId, "土銀帳戶");
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void createExistingCreditViaFormThenPageShowsIt() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/funding/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.params(creditParams()))
			.andExpect(redirectedUrl("/funding"))
			.andExpect(flash().attribute("message", "已新增「土銀信貸」"));

		mvc.perform(get("/funding"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("總負債")))
			.andExpect(content().string(containsString(">400,000<")))
			.andExpect(content().string(containsString("加權平均利率")))
			.andExpect(content().string(containsString("2.50%")))
			.andExpect(content().string(containsString("土銀信貸")))
			.andExpect(content().string(containsString("7,099")))
			.andExpect(content().string(containsString("剩 60 期")))
			.andExpect(content().string(containsString("href=\"/funding\"")));
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void createErrorsStayOnForm() throws Exception {
		MockHttpSession session = new MockHttpSession();
		var params = creditParams();
		params.set("annualRatePercent", "40");

		mvc.perform(post("/funding/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.params(params))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("年利率要在 0～30% 之間")));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan", Integer.class)).isZero();
	}

	/** 依類型顯示信貸欄位、依方式顯示撥入帳戶（app.js data-show-source）。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formHasConditionalFields() throws Exception {
		mvc.perform(get("/funding/new"))
			.andExpect(content().string(containsString("data-show-source=\"type\" data-show-for=\"CREDIT\"")))
			.andExpect(content().string(containsString("data-show-source=\"type\" data-show-for=\"PLEDGE\"")))
			.andExpect(content().string(containsString("data-show-source=\"origin\" data-show-for=\"NEW\"")))
			.andExpect(content().string(containsString("<label for=\"annualRatePercent\">年利率（%）</label>")));
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void addRateAndRepayViaForm() throws Exception {
		Long loan = createPledge();
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/funding/{id}/rate", loan).with(csrf()).param("effectiveDate", today.toString())
				.param("annualRatePercent", "3.2"))
			.andExpect(redirectedUrl("/funding"));
		mvc.perform(post("/funding/{id}/repay", loan).session(session).with(csrf())
				.param("formToken", token(mvc.perform(get("/funding/{id}/repay", loan).session(session)).andReturn()
					.getResponse().getContentAsString()))
				.param("date", today.toString()).param("amount", "100000").param("accountId", bank.toString()))
			.andExpect(redirectedUrl("/funding"));

		mvc.perform(get("/funding"))
			.andExpect(content().string(containsString(">300,000<")))
			.andExpect(content().string(containsString("3.20%")));
	}

	/** 作廢借款：卡片有確認框；作廢後不再出現在頁面上。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void voidLoanViaForm() throws Exception {
		Long loan = createPledge();
		mvc.perform(get("/funding"))
			.andExpect(content().string(containsString("data-confirm=\"確定要作廢這筆借款嗎？")));

		mvc.perform(post("/funding/{id}/void", loan).with(csrf()).param("reason", "打錯"))
			.andExpect(redirectedUrl("/funding"))
			.andExpect(flash().attribute("message", "已作廢「玉山質押」和它的所有紀錄"));

		mvc.perform(get("/funding")).andExpect(content().string(containsString("還沒有借款")));
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void movementsByLoan() throws Exception {
		Long loan = createPledge();
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, note, voided, "
				+ "created_at, loan_id) VALUES (?, 'LOAN_PRINCIPAL', ?, ?, 1000, '玉山質押', FALSE, NOW(), ?)", amyId,
				today, bank, loan);

		mvc.perform(get("/assets/movements").param("loan", loan.toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("「玉山質押」的紀錄")))
			.andExpect(content().string(containsString("借款還本 · 玉山質押")))
			.andExpect(content().string(containsString("回資金來源")));
	}

	/** 修改借款的紀錄：只有日期與金額。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editLoanMovementViaForm() throws Exception {
		Long loan = createPledge();
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, note, voided, "
				+ "created_at, loan_id) VALUES (?, 'LOAN_PRINCIPAL', ?, ?, 1000, '玉山質押', FALSE, NOW(), ?)", amyId,
				today, bank, loan);
		Long id = jdbc.queryForObject("SELECT id FROM cash_movement WHERE loan_id = ?", Long.class, loan);

		mvc.perform(get("/assets/movements/{id}/edit", id))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("修改借款紀錄")))
			.andExpect(content().string(containsString("value=\"1000\"")));
		mvc.perform(post("/assets/movements/{id}/edit-loan", id).with(csrf()).param("date", today.toString())
				.param("amount", "1200"))
			.andExpect(status().is3xxRedirection());

		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE id = ?", BigDecimal.class, id))
			.isEqualByComparingTo("1200");
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void otherUsersLoanIs404() throws Exception {
		Long benId = users.findByUsername("fc-ben").orElseThrow().getId();
		Long benBank = insertAccount(benId, "班的帳戶");
		jdbc.update("INSERT INTO loan (user_id, name, loan_type, bank, original_amount, origin, start_date, "
				+ "start_principal, pay_account_id, pay_day, posting_from, created_at) VALUES (?, '班的質押', 'PLEDGE', "
				+ "'銀行', 1000, 'EXISTING', ?, 1000, ?, 25, ?, NOW())", benId, today, benBank, thisMonth.atDay(1));
		Long loan = jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);

		mvc.perform(get("/funding/{id}/edit", loan)).andExpect(status().isNotFound());
		mvc.perform(post("/funding/{id}/rate", loan).with(csrf()).param("effectiveDate", today.toString())
				.param("annualRatePercent", "1")).andExpect(status().isNotFound());
		mvc.perform(get("/funding/{id}/repay", loan)).andExpect(status().isNotFound());
		mvc.perform(get("/assets/movements").param("loan", loan.toString())).andExpect(status().isNotFound());
		mvc.perform(get("/funding")).andExpect(content().string(not(containsString("班的質押"))));
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void duplicateTokenDoesNotCreateTwice() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = openNewForm(session);
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/funding/new").session(session).with(csrf()).param("formToken", token)
					.params(creditParams()))
				.andExpect(redirectedUrl("/funding"));
		}

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan", Integer.class)).isEqualTo(1);
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void emptyPageAndSidebarLink() throws Exception {
		mvc.perform(get("/funding"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("還沒有借款")))
			.andExpect(content().string(containsString("href=\"/funding/new\"")));
		mvc.perform(get("/assets"))
			.andExpect(content().string(containsString("href=\"/funding\"")));
	}

	private Long createPledge() throws Exception {
		MockHttpSession session = new MockHttpSession();
		var params = creditParams();
		params.set("name", "玉山質押");
		params.set("type", "PLEDGE");
		params.set("annualRatePercent", "3");
		params.set("payDay", "25");
		params.remove("remainingPeriods");
		params.remove("firstPeriod");
		mvc.perform(post("/funding/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.params(params)).andExpect(redirectedUrl("/funding"));
		return jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
	}

	private org.springframework.util.LinkedMultiValueMap<String, String> creditParams() {
		var p = new org.springframework.util.LinkedMultiValueMap<String, String>();
		p.add("name", "土銀信貸");
		p.add("type", "CREDIT");
		p.add("bank", "土地銀行");
		p.add("origin", "EXISTING");
		p.add("originalAmount", "400000");
		p.add("startDate", today.toString());
		p.add("startPrincipal", "400000");
		p.add("annualRatePercent", "2.5");
		p.add("payAccountId", bank.toString());
		p.add("payDay", "5");
		p.add("remainingPeriods", "60");
		p.add("firstPeriod", thisMonth.plusMonths(1).toString());
		return p;
	}

	private String openNewForm(MockHttpSession session) throws Exception {
		return token(mvc.perform(get("/funding/new").session(session)).andReturn().getResponse().getContentAsString());
	}

	private static String token(String html) {
		Matcher m = TOKEN.matcher(html);
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private Long insertAccount(Long user, String name) {
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, ?, 'BANK_DEMAND', '台銀', NOW())", user, name);
		return jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ? AND name = ?", Long.class, user, name);
	}

	private Long createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
		return users.findByUsername(username).orElseThrow().getId();
	}

}
