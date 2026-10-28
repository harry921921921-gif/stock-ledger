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

	@Autowired
	private com.sideproject.stockledger.service.TradeService trades;

	@Autowired
	private com.sideproject.stockledger.security.AppUserDetailsService userDetailsService;

	private LocalDate today;
	private YearMonth thisMonth;
	private Long amyId;
	private Long bank;

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

	/** 依還款方式顯示需要的欄位（app.js data-show-source 支援 radio；DR-048 取代依類型、依新撥款切換）。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formHasConditionalFields() throws Exception {
		mvc.perform(get("/funding/new"))
			.andExpect(content().string(containsString("data-show-source=\"repaymentMethod\" data-show-for=\"INTEREST_ONLY\"")))
			.andExpect(content().string(containsString("data-show-source=\"repaymentMethod\" data-show-for=\"BULLET INTEREST_ONLY\"")))
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

	/** 質押卡片：維持率門檻預設 150%／130%，可以改；擔保股票的錯誤用訊息顯示。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void pledgeThresholdsAndCollateralViaForm() throws Exception {
		Long loan = createPledge();
		mvc.perform(get("/funding"))
			.andExpect(content().string(containsString("擔保股票與維持率")))
			.andExpect(content().string(containsString("150% ／ 130%")))
			.andExpect(content().string(containsString("還沒拿去投資")));

		mvc.perform(post("/funding/{id}/thresholds", loan).with(csrf()).param("warnPercent", "160")
				.param("callPercent", "140"))
			.andExpect(redirectedUrl("/funding"))
			.andExpect(flash().attribute("message", "已更新「玉山質押」的維持率提醒線與追繳線"));
		mvc.perform(post("/funding/{id}/collateral", loan).with(csrf()).param("stockCode", "9999")
				.param("shares", "1000"))
			.andExpect(flash().attribute("message", "找不到這個上市股票代號：9999"));

		mvc.perform(get("/funding")).andExpect(content().string(containsString("160% ／ 140%")));
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
		jdbc.update("INSERT INTO loan (user_id, name, loan_type, repayment_method, bank, original_amount, origin, start_date, "
				+ "start_principal, pay_account_id, pay_day, posting_from, created_at) VALUES (?, '班的質押', 'PLEDGE', "
				+ "'INTEREST_ONLY', '銀行', 1000, 'EXISTING', ?, 1000, ?, 25, ?, NOW())", benId, today, benBank, thisMonth.atDay(1));
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

	// ---------- 新增借款依還款方式（spec 4.8a、DR-048、樣稿 loan-form.html） ----------

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formShowsRepaymentMethods() throws Exception {
		String html = mvc.perform(get("/funding/new")).andReturn().getResponse().getContentAsString();

		assertThat(html.split("name=\"repaymentMethod\"", -1)).hasSize(4);
		assertThat(html).contains("name=\"pledge\"")
			.contains("data-show-source=\"repaymentMethod\" data-show-for=\"MONTHLY_FIXED\"")
			.contains("data-show-source=\"pledge\" data-show-for=\"true\"")
			.contains("往來帳戶");
	}

	/** 土銀信貸照合約與銀行扣款填：存好後卡片顯示每月扣款 5,133。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void createMonthlyViaForm() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/funding/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.params(monthlyParams("2.15")))
			.andExpect(redirectedUrl("/funding"))
			.andExpect(flash().attribute("message", "已新增「土銀信貸」"));

		mvc.perform(get("/funding"))
			.andExpect(content().string(containsString("每月固定還款")))
			.andExpect(content().string(containsString("5,133")));
	}

	/** 利率填成 2.45%：照樣存，但提醒和合約算的差 54 元。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void paymentWarningFlash() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/funding/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.params(monthlyParams("2.45")))
			.andExpect(redirectedUrl("/funding"))
			.andExpect(flash().attribute("message", containsString("差 54")));
	}

	/** 到期一次還本金＋利息：還本表單帶入本金與應計利息。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void bulletRepayFormPrefilled() throws Exception {
		MockHttpSession session = new MockHttpSession();
		var p = new org.springframework.util.LinkedMultiValueMap<String, String>();
		p.add("name", "玉山質押");
		p.add("bank", "玉山銀行");
		p.add("repaymentMethod", "BULLET");
		p.add("pledge", "true");
		p.add("origin", "NEW");
		p.add("startDate", today.minusMonths(3).toString());
		p.add("startPrincipal", "100000");
		p.add("annualRatePercent", "4");
		p.add("payAccountId", bank.toString());
		p.add("dueDate", today.plusMonths(9).toString());
		mvc.perform(post("/funding/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.params(p))
			.andExpect(redirectedUrl("/funding"));
		Long loan = jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
		assertThat(jdbc.queryForObject("SELECT CONCAT(loan_type, ':', repayment_method) FROM loan WHERE id = ?",
				String.class, loan)).isEqualTo("PLEDGE:BULLET");

		mvc.perform(get("/funding/{id}/repay", loan))
			.andExpect(content().string(containsString("name=\"interest\"")))
			.andExpect(content().string(containsString("value=\"100000\"")));
	}

	// ---------- 修改借款：每個欄位都能改（spec 4.8a-10、DR-051） ----------

	private Long createBulletLoan(String fee) throws Exception {
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/funding/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.params(bulletEditParams(today.minusMonths(2).toString(), "100000", fee)))
			.andExpect(redirectedUrl("/funding"));
		return jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
	}

	/** 修改表單送出的欄位（和新增一樣）：到期一次還本金＋利息的股票質押。 */
	private org.springframework.util.LinkedMultiValueMap<String, String> bulletEditParams(String startDate,
			String principal, String fee) {
		var p = new org.springframework.util.LinkedMultiValueMap<String, String>();
		p.add("name", "玉山質押");
		p.add("bank", "玉山銀行");
		p.add("repaymentMethod", "BULLET");
		p.add("pledge", "true");
		p.add("origin", "NEW");
		p.add("startDate", startDate);
		p.add("startPrincipal", principal);
		p.add("annualRatePercent", "4");
		p.add("disburseAccountId", bank.toString());
		p.add("payAccountId", bank.toString());
		p.add("dueDate", today.plusMonths(9).toString());
		if (fee != null) {
			p.add("fee", fee);
		}
		return p;
	}

	private BigDecimal disbursementAmount(Long loan) {
		return jdbc.queryForObject("SELECT amount FROM cash_movement WHERE loan_id = ? AND type = 'LOAN_DISBURSEMENT' "
				+ "AND voided = FALSE", BigDecimal.class, loan);
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editCanChangeStartDateAndAmount() throws Exception {
		Long loan = createBulletLoan(null);
		String newStart = today.minusMonths(1).toString();

		mvc.perform(post("/funding/{id}/edit", loan).with(csrf()).params(bulletEditParams(newStart, "80000", null)))
			.andExpect(redirectedUrl("/funding"))
			.andExpect(flash().attribute("message", containsString("已修改「玉山質押」")));

		assertThat(disbursementAmount(loan)).isEqualByComparingTo("80000");
		assertThat(jdbc.queryForObject("SELECT start_date FROM loan WHERE id = ?", java.sql.Date.class, loan).toLocalDate())
			.isEqualTo(today.minusMonths(1));
	}

	/** 只改名稱：開辦費和撥入帳戶都要保留（表單一定要把 fee、disburseAccountId 送回來）。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editingOnlyTheNameKeepsFeeAndDisbursementAccount() throws Exception {
		Long loan = createBulletLoan("3000");
		String html = mvc.perform(get("/funding/{id}/edit", loan)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("name=\"fee\"").contains("value=\"3000\"").contains("name=\"disburseAccountId\"")
			.contains("name=\"repaymentMethod\"").contains("name=\"pledge\"");

		var params = bulletEditParams(today.minusMonths(2).toString(), "100000", "3000");
		params.set("name", "玉山質押（改名）");
		mvc.perform(post("/funding/{id}/edit", loan).with(csrf()).params(params)).andExpect(redirectedUrl("/funding"));

		assertThat(jdbc.queryForObject("SELECT name FROM loan WHERE id = ?", String.class, loan)).isEqualTo("玉山質押（改名）");
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE loan_id = ? AND type = 'LOAN_FEE' "
				+ "AND voided = FALSE", BigDecimal.class, loan)).isEqualByComparingTo("3000");
		assertThat(jdbc.queryForObject("SELECT account_id FROM cash_movement WHERE loan_id = ? "
				+ "AND type = 'LOAN_DISBURSEMENT' AND voided = FALSE", Long.class, loan)).isEqualTo(bank);
	}

	private void buyWithLoan(Long loan, LocalDate date, String code, long shares, String price, String amount) {
		jdbc.update("INSERT IGNORE INTO stock (code, name, kind, dividends_per_year, updated_at) "
				+ "VALUES (?, '元大台灣50', 'ETF', 2, NOW())", code);
		// MockMvc 每個請求結束都會清掉登入資訊，直接呼叫 Service 前要重新登入
		var details = userDetailsService.loadUserByUsername("fc-amy");
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
				org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(details, null,
						details.getAuthorities()));
		trades.create(new com.sideproject.stockledger.dto.TradeForm(com.sideproject.stockledger.entity.TradeType.BUY,
				date, code, bank, shares, new BigDecimal(price), null, null, null,
				java.util.List.of(new com.sideproject.stockledger.dto.AllocationLine(loan, new BigDecimal(amount)))));
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void shortfallShowsConfirmPage() throws Exception {
		Long loan = createBulletLoan(null);
		buyWithLoan(loan, today.minusMonths(1), "0050", 1000, "73.5", "73500");

		mvc.perform(post("/funding/{id}/edit", loan).with(csrf())
				.params(bulletEditParams(today.minusMonths(2).toString(), "50000", null)))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("funding/shortfall-confirm"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model().attributeExists("cuts"))
			.andExpect(content().string(containsString("差 23,500 元")))
			.andExpect(content().string(containsString("0050")))
			.andExpect(content().string(containsString("/funding/" + loan + "/edit/confirm-shortfall")))
			.andExpect(content().string(containsString("name=\"startPrincipal\"")));

		assertThat(disbursementAmount(loan)).isEqualByComparingTo("100000");
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void confirmShortfallSaves() throws Exception {
		Long loan = createBulletLoan(null);
		buyWithLoan(loan, today.minusMonths(1), "0050", 1000, "73.5", "73500");

		mvc.perform(post("/funding/{id}/edit/confirm-shortfall", loan).with(csrf())
				.params(bulletEditParams(today.minusMonths(2).toString(), "50000", null)))
			.andExpect(redirectedUrl("/funding"));

		assertThat(disbursementAmount(loan)).isEqualByComparingTo("50000");
		assertThat(jdbc.queryForObject("SELECT amount FROM source_allocation WHERE loan_id = ?", BigDecimal.class, loan))
			.isEqualByComparingTo("50000");
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editOthersLoanIsNotFound() throws Exception {
		Long benId = users.findByUsername("fc-ben").orElseThrow().getId();
		Long benBank = insertAccount(benId, "班的帳戶");
		jdbc.update("INSERT INTO loan (user_id, name, loan_type, repayment_method, bank, original_amount, origin, start_date, "
				+ "start_principal, pay_account_id, pay_day, posting_from, created_at) VALUES (?, '班的質押', 'PLEDGE', "
				+ "'INTEREST_ONLY', '銀行', 1000, 'EXISTING', ?, 1000, ?, 25, ?, NOW())", benId, today, benBank, thisMonth.atDay(1));
		Long loan = jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);

		mvc.perform(post("/funding/{id}/edit", loan).with(csrf()).params(bulletEditParams(today.toString(), "1000", null)))
			.andExpect(status().isNotFound());
		mvc.perform(post("/funding/{id}/edit/confirm-shortfall", loan).with(csrf())
				.params(bulletEditParams(today.toString(), "1000", null))).andExpect(status().isNotFound());
	}

	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void keptPeriodsAreListedAfterEdit() throws Exception {
		var kept = java.util.List.of(new com.sideproject.stockledger.dto.LoanEditResult.KeptPeriod(1L,
				YearMonth.of(2026, 8), com.sideproject.stockledger.entity.CashMovementType.LOAN_INTEREST, new BigDecimal("700.0000")));

		mvc.perform(get("/funding").flashAttr("kept", kept))
			.andExpect(content().string(containsString("2026-08 借款利息 700 元")))
			.andExpect(content().string(containsString("這幾期你改過，沒有自動重算，請確認。")))
			.andExpect(content().string(not(containsString("它們也留在原本的帳戶"))));
	}

	/** 這次修改換了扣款帳戶：才提醒手改過的期數還留在原本的帳戶（是否換帳戶由 Service 判斷，畫面不算）。 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void keptPeriodsMentionOldAccountWhenPayAccountChanged() throws Exception {
		var kept = java.util.List.of(new com.sideproject.stockledger.dto.LoanEditResult.KeptPeriod(1L,
				YearMonth.of(2026, 8), com.sideproject.stockledger.entity.CashMovementType.LOAN_INTEREST, new BigDecimal("700.0000")));

		mvc.perform(get("/funding").flashAttr("kept", kept).flashAttr("keptAccountMoved", true))
			.andExpect(content().string(containsString("這幾期你改過，沒有自動重算，請確認；它們也留在原本的帳戶。")));
	}

	private org.springframework.util.LinkedMultiValueMap<String, String> monthlyParams(String ratePercent) {
		var p = new org.springframework.util.LinkedMultiValueMap<String, String>();
		p.add("name", "土銀信貸");
		p.add("bank", "土地銀行");
		p.add("repaymentMethod", "MONTHLY_FIXED");
		p.add("origin", "NEW");
		p.add("startDate", "2026-02-01");
		p.add("startPrincipal", "400000");
		p.add("annualRatePercent", ratePercent);
		p.add("payAccountId", bank.toString());
		p.add("monthlyPayment", "5133");
		p.add("payDay", "1");
		p.add("remainingPeriods", "84");
		return p;
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

	/**
	 * V11 以前建立的每月固定還款（沒有扣款金額版本）：修改畫面不帶還款方式（照舊的規則），送回畫面上的值、只改名稱，
	 * 每期紀錄、修改紀錄、利率版本一筆都不動（DR-018 照公式算的寫法繼續用）。
	 */
	@Test
	@WithUserDetails(value = "fc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void legacyMonthlyRenameViaEditPageTouchesNothing() throws Exception {
		MockHttpSession session = new MockHttpSession();
		LocalDate start = today.minusMonths(3).withDayOfMonth(1);
		var create = creditParams();
		create.set("startDate", start.toString());
		create.set("firstPeriod", YearMonth.from(start).plusMonths(1).toString());
		mvc.perform(post("/funding/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.params(create))
			.andExpect(redirectedUrl("/funding"));
		Long loan = jdbc.queryForObject("SELECT id FROM loan WHERE user_id = ?", Long.class, amyId);
		java.util.List<String> movementsBefore = loanRows(loan);
		int revisionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement_revision", Integer.class);
		java.util.List<String> ratesBefore = rateRows(loan);
		assertThat(movementsBefore).isNotEmpty();

		String html = mvc.perform(get("/funding/{id}/edit", loan)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		// 畫面送出的值（有 JavaScript 時藏起來的欄位不送）：還款方式只有可以送出的那個選項才帶
		var params = new org.springframework.util.LinkedMultiValueMap<String, String>();
		params.add("name", "土銀信貸（改名）");
		params.add("bank", "土地銀行");
		params.add("origin", "EXISTING");
		params.add("originalAmount", "400000");
		params.add("startDate", start.toString());
		params.add("startPrincipal", "400000");
		params.add("annualRatePercent", "2.5");
		params.add("payAccountId", bank.toString());
		params.add("payDay", "5");
		params.add("remainingPeriods", "60");
		params.add("firstPeriod", YearMonth.from(start).plusMonths(1).toString());
		params.add("fee", "");
		assertThat(html).contains("value=\"400000\"").contains("value=\"2.5\"").contains("value=\"60\"")
			.contains("value=\"" + YearMonth.from(start).plusMonths(1) + "\"");
		Matcher method = Pattern.compile("<input type=\"radio\" name=\"repaymentMethod\" value=\"(\\w+)\" checked=\"checked\">")
			.matcher(html);
		if (method.find()) {
			params.add("repaymentMethod", method.group(1));
		}
		if (html.contains("name=\"type\" value=\"CREDIT\"")) {
			params.add("type", "CREDIT");
		}

		mvc.perform(post("/funding/{id}/edit", loan).with(csrf()).params(params))
			.andExpect(redirectedUrl("/funding"));

		assertThat(jdbc.queryForObject("SELECT name FROM loan WHERE id = ?", String.class, loan)).isEqualTo("土銀信貸（改名）");
		assertThat(loanRows(loan)).isEqualTo(movementsBefore);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement_revision", Integer.class))
			.isEqualTo(revisionsBefore);
		assertThat(rateRows(loan)).isEqualTo(ratesBefore);
		assertThat(html).contains("還款方式：每月固定還款");
	}

	private java.util.List<String> loanRows(Long loan) {
		return jdbc.queryForList("SELECT CONCAT(id, '|', type, '|', movement_date, '|', account_id, '|', amount, '|', "
				+ "voided) FROM cash_movement WHERE loan_id = ? ORDER BY id", String.class, loan);
	}

	private java.util.List<String> rateRows(Long loan) {
		return jdbc.queryForList("SELECT CONCAT(id, '|', kind, '|', `value`, '|', effective_date) FROM rate_setting "
				+ "WHERE loan_id = ? ORDER BY id", String.class, loan);
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
