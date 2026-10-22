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

/** 現金異動的畫面：新增、對帳、初始現金餘額、全部異動、修改／作廢（spec 4.6、4.9）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CashMovementControllerTest {

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
	private Long bank;
	private Long settlement;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		createIfMissing("mc-amy");
		createIfMissing("mc-ben");
		today = LocalDate.now(clock);
		bank = insertAccount("mc-amy", "薪資帳戶");
		settlement = insertAccount("mc-amy", "交割戶");
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void recordDepositViaForm() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = openNewForm(session);

		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", token)
				.param("type", "DEPOSIT")
				.param("date", today.toString())
				.param("toAccountId", bank.toString())
				.param("amount", "62000")
				.param("note", "薪資入帳"))
			.andExpect(redirectedUrl("/assets"));

		mvc.perform(get("/assets"))
			.andExpect(content().string(containsString("薪資入帳")))
			.andExpect(content().string(containsString("62,000")));
	}

	/** 連點兩次：同一個表單代號只會新增一筆（Task 6 防重複送出）。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void duplicateTokenDoesNotCreateTwice() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = openNewForm(session);

		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/assets/movements/new").session(session).with(csrf())
					.param("formToken", token)
					.param("type", "DEPOSIT")
					.param("date", today.toString())
					.param("toAccountId", bank.toString())
					.param("amount", "100"))
				.andExpect(redirectedUrl("/assets"));
		}

		assertThat(movementCount()).isEqualTo(1);
	}

	/** 驗證失敗時代號不能被用掉，否則改正後再送會被當成重複而沒存到。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void tokenSurvivesValidationError() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = openNewForm(session);

		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", token).param("type", "DEPOSIT").param("date", today.toString())
				.param("toAccountId", bank.toString()).param("amount", "100.5"))
			.andExpect(status().isOk());
		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", token).param("type", "DEPOSIT").param("date", today.toString())
				.param("toAccountId", bank.toString()).param("amount", "100"))
			.andExpect(redirectedUrl("/assets"));

		assertThat(movementCount()).isEqualTo(1);
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void validationErrorsShownInChinese() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = openNewForm(session);

		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", token).param("type", "DEPOSIT").param("date", today.toString())
				.param("toAccountId", bank.toString()).param("amount", "abc"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("請輸入整數金額")))
			.andExpect(content().string(not(containsString("java."))));
		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", token).param("type", "DEPOSIT").param("date", today.toString())
				.param("toAccountId", bank.toString()).param("amount", "100.5"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("金額只能是整數元")));
		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", token).param("type", "DEPOSIT").param("date", "")
				.param("toAccountId", bank.toString()).param("amount", "100"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("請選擇日期")));

		assertThat(movementCount()).isZero();
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void reconcileFormShowsSystemBalanceAndSaves() throws Exception {
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, voided, created_at) "
				+ "VALUES (?, 'OPENING', ?, ?, 12345, FALSE, NOW())", userId("mc-amy"), today.minusDays(3), bank);

		mvc.perform(get("/assets/accounts/{id}/reconcile", bank))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("薪資帳戶")))
			.andExpect(content().string(containsString("12,345")));

		mvc.perform(post("/assets/accounts/{id}/reconcile", bank).with(csrf())
				.param("date", today.toString()).param("actualBalance", "12000"))
			.andExpect(redirectedUrl("/assets"));

		assertThat(jdbc.queryForObject("SELECT actual_balance FROM cash_movement WHERE type = 'RECONCILE'", Long.class))
			.isEqualTo(12000L);
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void openingFormSaves() throws Exception {
		mvc.perform(get("/assets/accounts/{id}/opening", bank))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("初始現金餘額")));

		mvc.perform(post("/assets/accounts/{id}/opening", bank).with(csrf())
				.param("date", today.minusDays(1).toString()).param("amount", "50000"))
			.andExpect(redirectedUrl("/assets"));

		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE type = 'OPENING'", Long.class))
			.isEqualTo(50000L);
	}

	@Test
	@WithUserDetails(value = "mc-ben", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void otherUsersAccountAndMovementAre404() throws Exception {
		Long amyMovement = insertDeposit(bank, today, 100);

		mvc.perform(get("/assets/accounts/{id}/reconcile", bank)).andExpect(status().isNotFound());
		mvc.perform(get("/assets/accounts/{id}/opening", bank)).andExpect(status().isNotFound());
		mvc.perform(get("/assets/movements/{id}/edit", amyMovement)).andExpect(status().isNotFound());
		mvc.perform(post("/assets/movements/{id}/void", amyMovement).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(post("/assets/accounts/{id}/reconcile", bank).with(csrf())
				.param("date", today.toString()).param("actualBalance", "1"))
			.andExpect(status().isNotFound());

		assertThat(jdbc.queryForObject("SELECT voided FROM cash_movement WHERE id = ?", Boolean.class, amyMovement))
			.isFalse();
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void movementsPageFiltersByMonth() throws Exception {
		LocalDate lastMonthDay = today.withDayOfMonth(1).minusDays(1);
		insertDeposit(bank, today, 111);
		insertDeposit(bank, lastMonthDay, 222);

		mvc.perform(get("/assets/movements").param("month", YearMonth.from(lastMonthDay).toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(">+222<")))
			.andExpect(content().string(not(containsString(">+111<"))));
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void invalidMonthParamFallsBackToCurrent() throws Exception {
		insertDeposit(bank, today, 333);

		mvc.perform(get("/assets/movements").param("month", "abc"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(">+333<")));
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editThenVoidShowsMarks() throws Exception {
		Long id = insertDeposit(bank, today, 100);

		mvc.perform(get("/assets/movements/{id}/edit", id))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("修改原因")));
		mvc.perform(post("/assets/movements/{id}/edit", id).with(csrf())
				.param("type", "DEPOSIT").param("date", today.toString()).param("toAccountId", bank.toString())
				.param("amount", "150").param("reason", "金額打錯"))
			.andExpect(redirectedUrl("/assets/movements?month=" + YearMonth.from(today)));
		mvc.perform(get("/assets/movements"))
			.andExpect(content().string(containsString("曾修改")));

		mvc.perform(post("/assets/movements/{id}/void", id).with(csrf()).param("reason", "重複"))
			.andExpect(redirectedUrl("/assets/movements?month=" + YearMonth.from(today)));
		mvc.perform(get("/assets/movements"))
			.andExpect(content().string(containsString("已作廢")));
		mvc.perform(get("/assets"))
			.andExpect(content().string(not(containsString(">+150<"))));
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editReconcileAndOpeningUseTheirOwnForms() throws Exception {
		Long opening = insertMovement("OPENING", bank, today.minusDays(5), "1000", null);
		Long reconcile = insertMovement("RECONCILE", bank, today, null, "900");

		mvc.perform(get("/assets/movements/{id}/edit", opening))
			.andExpect(content().string(containsString("/assets/movements/" + opening + "/edit-opening")));
		mvc.perform(post("/assets/movements/{id}/edit-opening", opening).with(csrf())
				.param("date", today.minusDays(5).toString()).param("amount", "1100"))
			.andExpect(status().is3xxRedirection());
		mvc.perform(get("/assets/movements/{id}/edit", reconcile))
			.andExpect(content().string(containsString("/assets/movements/" + reconcile + "/edit-reconcile")));
		mvc.perform(post("/assets/movements/{id}/edit-reconcile", reconcile).with(csrf())
				.param("date", today.toString()).param("actualBalance", "950"))
			.andExpect(status().is3xxRedirection());

		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE id = ?", Long.class, opening)).isEqualTo(1100L);
		assertThat(jdbc.queryForObject("SELECT actual_balance FROM cash_movement WHERE id = ?", Long.class, reconcile))
			.isEqualTo(950L);
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formsRequireCsrfAndLoadGuard() throws Exception {
		mvc.perform(post("/assets/movements/new")
				.param("type", "DEPOSIT").param("date", today.toString()).param("toAccountId", bank.toString())
				.param("amount", "100"))
			.andExpect(status().isForbidden());
		for (String url : new String[] { "/assets/movements/new", "/assets/accounts/" + bank + "/reconcile",
				"/assets/accounts/" + bank + "/opening" }) {
			mvc.perform(get(url))
				.andExpect(content().string(containsString("src=\"/js/app.js\"")))
				.andExpect(content().string(containsString("data-busy-text")));
		}
	}

	/** 修改帳戶的畫面顯示初始現金餘額的狀態與連結（spec 4.6-3）。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void accountEditPageLinksToOpening() throws Exception {
		mvc.perform(get("/settings/accounts/{id}/edit", bank))
			.andExpect(content().string(containsString("/assets/accounts/" + bank + "/opening")));
	}

	/** 修改畫面的金額要是「62000」，不是資料庫的「62000.0000」（審核 Important 1）。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editFormsShowWholeDollars() throws Exception {
		Long deposit = insertDeposit(bank, today, 62000);
		Long opening = insertMovement("OPENING", settlement, today.minusDays(5), "1000", null);
		Long reconcile = insertMovement("RECONCILE", bank, today, null, "900");

		mvc.perform(get("/assets/movements/{id}/edit", deposit))
			.andExpect(content().string(containsString("value=\"62000\"")))
			.andExpect(content().string(not(containsString("62000.0000"))));
		mvc.perform(get("/assets/movements/{id}/edit", opening))
			.andExpect(content().string(containsString("value=\"1000\"")));
		mvc.perform(get("/assets/movements/{id}/edit", reconcile))
			.andExpect(content().string(containsString("value=\"900\"")));
	}

	/** 驗證以外的錯誤（例如用了別人的帳戶 → 404）也不能用掉代號，否則重送會被當成重複而沒存到（審核 Important 2）。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void tokenRestoredAfterUnexpectedFailure() throws Exception {
		Long benAccount = insertAccount("mc-ben", "班的帳戶");
		MockHttpSession session = new MockHttpSession();
		String token = openNewForm(session);

		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", token).param("type", "DEPOSIT").param("date", today.toString())
				.param("toAccountId", benAccount.toString()).param("amount", "100"))
			.andExpect(status().isNotFound());
		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", token).param("type", "DEPOSIT").param("date", today.toString())
				.param("toAccountId", bank.toString()).param("amount", "100"))
			.andExpect(redirectedUrl("/assets"));

		assertThat(movementCount()).isEqualTo(1);
	}

	@Test
	@WithUserDetails(value = "mc-ben", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void otherUsersEditPostsAre404EvenWithInvalidInput() throws Exception {
		Long deposit = insertDeposit(bank, today, 100);
		Long opening = insertMovement("OPENING", settlement, today.minusDays(5), "1000", null);
		Long reconcile = insertMovement("RECONCILE", bank, today, null, "900");

		mvc.perform(post("/assets/movements/{id}/edit", deposit).with(csrf()).param("amount", ""))
			.andExpect(status().isNotFound());
		mvc.perform(post("/assets/movements/{id}/edit-opening", opening).with(csrf()).param("amount", ""))
			.andExpect(status().isNotFound());
		mvc.perform(post("/assets/movements/{id}/edit-reconcile", reconcile).with(csrf()).param("actualBalance", ""))
			.andExpect(status().isNotFound());
		mvc.perform(post("/assets/accounts/{id}/opening", bank).with(csrf())
				.param("date", today.toString()).param("amount", "1"))
			.andExpect(status().isNotFound());
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void hugeMonthParamFallsBackToCurrent() throws Exception {
		mvc.perform(get("/assets/movements").param("month", "+999999999-12"))
			.andExpect(status().isOk());
	}

	/** 存入選了轉出帳戶：提醒，而不是默默忽略（沒有 JavaScript 時兩個帳戶欄位都會顯示）。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void depositWithSourceAccountRejected() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = openNewForm(session);

		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", token).param("type", "DEPOSIT").param("date", today.toString())
				.param("fromAccountId", settlement.toString()).param("toAccountId", bank.toString()).param("amount", "100"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("存入不用選轉出帳戶")));
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void transferViaFormAndEmptySourceForDeposit() throws Exception {
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", openNewForm(session)).param("type", "TRANSFER").param("date", today.toString())
				.param("fromAccountId", bank.toString()).param("toAccountId", settlement.toString()).param("amount", "300"))
			.andExpect(redirectedUrl("/assets"));
		mvc.perform(post("/assets/movements/new").session(session).with(csrf())
				.param("formToken", openNewForm(session)).param("type", "DEPOSIT").param("date", today.toString())
				.param("fromAccountId", "").param("toAccountId", bank.toString()).param("amount", "100"))
			.andExpect(redirectedUrl("/assets"));

		assertThat(movementCount()).isEqualTo(2);
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void voidedEditRedirectsAndOpeningRedirectsToEdit() throws Exception {
		Long deposit = insertDeposit(bank, today, 100);
		jdbc.update("UPDATE cash_movement SET voided = TRUE WHERE id = ?", deposit);
		Long opening = insertMovement("OPENING", settlement, today.minusDays(5), "1000", null);

		mvc.perform(get("/assets/movements/{id}/edit", deposit))
			.andExpect(redirectedUrl("/assets/movements?month=" + YearMonth.from(today)));
		mvc.perform(post("/assets/movements/{id}/void", deposit).with(csrf()))
			.andExpect(redirectedUrl("/assets/movements?month=" + YearMonth.from(today)));
		mvc.perform(get("/assets/accounts/{id}/opening", settlement))
			.andExpect(redirectedUrl("/assets/movements/" + opening + "/edit"));
	}

	/** 類型只有存入、支出、帳戶互轉；生活費類型已取消（DR-035）。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void onlyDepositWithdrawalAndTransferOffered() throws Exception {
		mvc.perform(get("/assets/movements/new"))
			.andExpect(content().string(containsString("value=\"DEPOSIT\"")))
			.andExpect(content().string(containsString("<option value=\"WITHDRAWAL\">支出</option>")))
			.andExpect(content().string(containsString("<option value=\"TRANSFER\">帳戶互轉</option>")))
			.andExpect(content().string(not(containsString("LIVING_EXPENSE"))))
			.andExpect(content().string(not(containsString("領出"))));
	}

	/**
	 * 欄位：日期、轉出帳戶、轉入帳戶、金額、備註，標籤不加括號說明（DR-035）。
	 * 帳戶欄位依類型由 app.js 顯示或隱藏（data-show-for）；沒有 JavaScript 時兩個都顯示，所以都有「請選擇」。
	 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formHasFromAndToAccountFields() throws Exception {
		mvc.perform(get("/assets/movements/new"))
			.andExpect(content().string(containsString("<label for=\"fromAccountId\">轉出帳戶</label>")))
			.andExpect(content().string(containsString("<label for=\"toAccountId\">轉入帳戶</label>")))
			.andExpect(content().string(containsString("<label for=\"amount\">金額</label>")))
			.andExpect(content().string(containsString("<label for=\"note\">備註</label>")))
			.andExpect(content().string(containsString("data-show-for=\"WITHDRAWAL TRANSFER\"")))
			.andExpect(content().string(containsString("data-show-for=\"DEPOSIT TRANSFER\"")))
			.andExpect(content().string(containsString("<option value=\"\">請選擇</option>")))
			.andExpect(content().string(not(containsString("說明"))));
	}

	/** 備註搜尋（spec 4.6-9）：有搜尋字時列出所有月份的結果，不顯示月份切換。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void searchShowsResultsWithoutMonthNav() throws Exception {
		jdbc.update("UPDATE cash_movement SET note = '年繳保險' WHERE id = ?",
				insertDeposit(bank, today.minusMonths(2), 100));

		mvc.perform(get("/assets/movements").param("q", "保險"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("存入 · 年繳保險")))
			.andExpect(content().string(containsString("清除搜尋")))
			.andExpect(content().string(containsString("name=\"q\"")))
			.andExpect(content().string(not(containsString("切換月份"))));
		mvc.perform(get("/assets/movements").param("q", "找不到"))
			.andExpect(content().string(containsString("沒有符合的異動")));
	}

	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void blankOrTooLongSearch() throws Exception {
		mvc.perform(get("/assets/movements").param("q", "   "))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("切換月份")));
		mvc.perform(get("/assets/movements").param("q", "字".repeat(51)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("搜尋字最多 50 個字")));
	}

	/** 修改存入時，帳戶帶在「轉入帳戶」（資料表存在 account_id）。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editDepositPrefillsToAccount() throws Exception {
		Long id = insertDeposit(bank, today, 100);

		String html = mvc.perform(get("/assets/movements/{id}/edit", id))
			.andReturn().getResponse().getContentAsString();
		String toSelect = html.substring(html.indexOf("id=\"toAccountId\""), html.indexOf("</select>",
				html.indexOf("id=\"toAccountId\"")));
		assertThat(toSelect).contains("value=\"" + bank + "\" selected=\"selected\"");
	}

	/** 作廢前跳出確認框（使用者決定）：表單帶 data-confirm，由 app.js 顯示。 */
	@Test
	@WithUserDetails(value = "mc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void voidFormAsksForConfirmation() throws Exception {
		insertDeposit(bank, today, 100);

		mvc.perform(get("/assets/movements"))
			.andExpect(content().string(containsString("data-confirm=")));
	}

	private String openNewForm(MockHttpSession session) throws Exception {
		String html = mvc.perform(get("/assets/movements/new").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		Matcher m = TOKEN.matcher(html);
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private Long insertDeposit(Long account, LocalDate date, long amount) {
		return insertMovement("DEPOSIT", account, date, String.valueOf(amount), null);
	}

	private Long insertMovement(String type, Long account, LocalDate date, String amount, String actual) {
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, actual_balance, voided, "
				+ "created_at) VALUES (?, ?, ?, ?, ?, ?, FALSE, NOW())", userId("mc-amy"), type, date, account,
				amount == null ? null : new java.math.BigDecimal(amount), actual == null ? null : new java.math.BigDecimal(actual));
		return jdbc.queryForObject("SELECT MAX(id) FROM cash_movement", Long.class);
	}

	private int movementCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement", Integer.class);
	}

	private Long insertAccount(String username, String name) {
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, ?, 'BANK_DEMAND', '國泰世華', NOW())", userId(username), name);
		return jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ? AND name = ?", Long.class,
				userId(username), name);
	}

	private Long userId(String username) {
		return users.findByUsername(username).orElseThrow().getId();
	}

	private void createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
	}

}
