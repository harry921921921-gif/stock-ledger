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

/** 固定收支頁（spec 第 3 節第 5 頁、設計文件 4.3、4.4）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RecurringControllerTest {

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
		amyId = createIfMissing("rc-amy");
		createIfMissing("rc-ben");
		today = LocalDate.now(clock);
		thisMonth = YearMonth.from(today);
		bank = insertAccount(amyId, "薪資帳戶");
	}

	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void pageShowsCardsTablesAndUnfilled() throws Exception {
		insertItem(amyId, bank, "薪水", "INCOME", "FIXED", "62000", "MONTHLY", null, 5, thisMonth.plusMonths(1));
		insertItem(amyId, bank, "壽險", "EXPENSE", "FIXED", "48000", "YEARLY", 3, 15, thisMonth.plusMonths(1));
		insertItem(amyId, bank, "生活費", "EXPENSE", "MANUAL", null, "MONTHLY", null, 31, thisMonth);

		mvc.perform(get("/recurring"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("每月固定收入")))
			.andExpect(content().string(containsString(">62,000<")))
			.andExpect(content().string(containsString("每月固定支出")))
			.andExpect(content().string(containsString(">4,000<")))
			.andExpect(content().string(containsString("每年 3 月 15 號")))
			.andExpect(content().string(containsString("每月自己填")))
			.andExpect(content().string(containsString("還沒填")))
			.andExpect(content().string(containsString("action=\"/recurring/")))
			.andExpect(content().string(containsString("href=\"/recurring\"")))
			.andExpect(content().string(containsString("href=\"/recurring/new\"")));
	}

	/** 沒有任何「每月自己填」的項目時，不能說「本月都填好了」。 */
	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void emptyPageSaysNoManualItems() throws Exception {
		mvc.perform(get("/recurring"))
			.andExpect(content().string(containsString("沒有每月自己填的項目")))
			.andExpect(content().string(not(containsString("本月都填好了"))));
	}

	/** 開始月份選過去：存檔後告訴使用者補記了幾筆。 */
	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void createViaFormWithBackfillMessage() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/recurring/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.param("name", "孝親費").param("direction", "EXPENSE").param("amountMode", "FIXED").param("amount", "8000")
				.param("cycle", "MONTHLY").param("dayOfMonth", "1").param("accountId", bank.toString())
				.param("startMonth", thisMonth.minusMonths(1).toString()))
			.andExpect(redirectedUrl("/recurring"))
			.andExpect(flash().attribute("message", "已新增「孝親費」，補記了 2 筆過去的入帳"));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement", Integer.class)).isEqualTo(2);
	}

	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void createErrorsStayOnForm() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/recurring/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.param("name", "").param("direction", "EXPENSE").param("amountMode", "FIXED").param("amount", "8000")
				.param("cycle", "MONTHLY").param("dayOfMonth", "abc").param("accountId", bank.toString())
				.param("startMonth", thisMonth.toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("請輸入 1～31 的數字")));
		mvc.perform(post("/recurring/new").session(session).with(csrf()).param("formToken", openNewForm(session))
				.param("name", "").param("direction", "EXPENSE").param("amountMode", "FIXED").param("amount", "8000")
				.param("cycle", "MONTHLY").param("dayOfMonth", "5").param("accountId", bank.toString())
				.param("startMonth", thisMonth.toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("請輸入名稱")));
	}

	/** 金額只有固定金額要填；月份只有每半年、每年要選（app.js 依 data-show-for 切換）。標籤不加括號說明。 */
	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void formHasConditionalFields() throws Exception {
		mvc.perform(get("/recurring/new"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("data-show-source=\"amountMode\" data-show-for=\"FIXED\"")))
			.andExpect(content().string(containsString("data-show-source=\"cycle\" data-show-for=\"SEMIANNUAL YEARLY\"")))
			.andExpect(content().string(containsString("<label for=\"amount\">金額</label>")))
			.andExpect(content().string(containsString("會補記已到期的入帳")))
			.andExpect(content().string(not(containsString("金額（"))));
	}

	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editViaForm() throws Exception {
		Long item = insertItem(amyId, bank, "孝親費", "EXPENSE", "FIXED", "8000", "MONTHLY", null, 28,
				thisMonth.plusMonths(1));

		mvc.perform(get("/recurring/{id}/edit", item))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"8000\"")));
		mvc.perform(post("/recurring/{id}/edit", item).with(csrf()).param("name", "孝親費").param("direction", "EXPENSE")
				.param("amountMode", "FIXED").param("amount", "9000").param("cycle", "MONTHLY").param("dayOfMonth", "28")
				.param("accountId", bank.toString()).param("startMonth", thisMonth.plusMonths(1).toString())
				.param("endMonth", thisMonth.plusMonths(6).toString()))
			.andExpect(redirectedUrl("/recurring"));

		assertThat(jdbc.queryForMap("SELECT amount, end_month FROM recurring_item WHERE id = ?", item))
			.satisfies(r -> assertThat(r.get("amount").toString()).isEqualTo("9000.0000"))
			.satisfies(r -> assertThat(r.get("end_month").toString()).isEqualTo(thisMonth.plusMonths(6).atDay(1).toString()));
	}

	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void fillViaFormThenReminderGone() throws Exception {
		Long living = insertItem(amyId, bank, "生活費", "EXPENSE", "MANUAL", null, "MONTHLY", null, 1,
				thisMonth.minusMonths(1));
		mvc.perform(get("/assets"))
			.andExpect(content().string(containsString("生活費還沒填")));
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/recurring/{id}/fill", living).session(session).with(csrf())
				.param("formToken", openPage(session)).param("period", thisMonth.minusMonths(1).toString())
				.param("amount", "27000"))
			.andExpect(redirectedUrl("/recurring"))
			.andExpect(flash().attribute("message", "已記錄 " + thisMonth.minusMonths(1) + " 生活費"));
		mvc.perform(post("/recurring/{id}/fill", living).session(session).with(csrf())
				.param("formToken", openPage(session)).param("period", thisMonth.toString()).param("amount", "26000"))
			.andExpect(redirectedUrl("/recurring"));

		mvc.perform(get("/assets"))
			.andExpect(content().string(not(containsString("生活費還沒填"))))
			.andExpect(content().string(containsString(">26,000<")));
	}

	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void fillErrorsShownAsMessage() throws Exception {
		Long living = insertItem(amyId, bank, "生活費", "EXPENSE", "MANUAL", null, "MONTHLY", null, 1, thisMonth);
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/recurring/{id}/fill", living).session(session).with(csrf())
				.param("formToken", openPage(session)).param("period", thisMonth.toString()).param("amount", "abc"))
			.andExpect(redirectedUrl("/recurring"))
			.andExpect(flash().attribute("error", "請輸入整數金額"));
		mvc.perform(post("/recurring/{id}/fill", living).session(session).with(csrf())
				.param("formToken", openPage(session)).param("period", thisMonth.toString()).param("amount", "0"))
			.andExpect(flash().attribute("error", "金額要大於 0"));
	}

	/** 總資產頁：入帳日過了還沒填的期數要提醒，連到固定收支頁（設計文件 4.3）。 */
	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void assetsPageShowsOverdueReminder() throws Exception {
		insertItem(amyId, bank, "生活費", "EXPENSE", "MANUAL", null, "MONTHLY", null, 31, thisMonth.minusMonths(1));

		mvc.perform(get("/assets"))
			.andExpect(content().string(containsString(thisMonth.minusMonths(1) + " 生活費還沒填")))
			.andExpect(content().string(containsString("href=\"/recurring\"")));
	}

	/** 依項目看異動：只列這個項目的，標題寫項目名稱。 */
	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void movementsByItem() throws Exception {
		Long item = insertItem(amyId, bank, "孝親費", "EXPENSE", "FIXED", "8000", "MONTHLY", null, 1, thisMonth);
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, note, voided, "
				+ "created_at, recurring_item_id, period) VALUES (?, 'WITHDRAWAL', ?, ?, 8000, '孝親費', FALSE, NOW(), ?, ?)",
				amyId, thisMonth.atDay(1), bank, item, thisMonth.atDay(1));
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, note, voided, "
				+ "created_at) VALUES (?, 'DEPOSIT', ?, ?, 500, '紅包', FALSE, NOW())", amyId, today, bank);

		mvc.perform(get("/assets/movements").param("item", item.toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("「孝親費」的紀錄")))
			.andExpect(content().string(containsString("支出 · 孝親費")))
			.andExpect(content().string(not(containsString("紅包"))));
	}

	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void otherUsersItemIs404() throws Exception {
		Long benId = users.findByUsername("rc-ben").orElseThrow().getId();
		Long benBank = insertAccount(benId, "班的帳戶");
		Long item = insertItem(benId, benBank, "生活費", "EXPENSE", "MANUAL", null, "MONTHLY", null, 1, thisMonth);
		MockHttpSession session = new MockHttpSession();

		mvc.perform(get("/recurring/{id}/edit", item)).andExpect(status().isNotFound());
		mvc.perform(post("/recurring/{id}/edit", item).with(csrf()).param("name", "x"))
			.andExpect(status().isNotFound());
		mvc.perform(post("/recurring/{id}/fill", item).session(session).with(csrf())
				.param("formToken", "x").param("period", thisMonth.toString()).param("amount", "1"))
			.andExpect(status().isNotFound());
		mvc.perform(get("/assets/movements").param("item", item.toString())).andExpect(status().isNotFound());
		mvc.perform(get("/recurring")).andExpect(content().string(not(containsString("/recurring/" + item + "/"))));
	}

	@Test
	@WithUserDetails(value = "rc-amy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void duplicateTokenDoesNotCreateTwice() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = openNewForm(session);
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/recurring/new").session(session).with(csrf()).param("formToken", token)
					.param("name", "健身房").param("direction", "EXPENSE").param("amountMode", "FIXED")
					.param("amount", "1500").param("cycle", "MONTHLY").param("dayOfMonth", "28")
					.param("accountId", bank.toString()).param("startMonth", thisMonth.plusMonths(1).toString()))
				.andExpect(redirectedUrl("/recurring"));
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recurring_item", Integer.class)).isEqualTo(1);

		Long living = insertItem(amyId, bank, "生活費", "EXPENSE", "MANUAL", null, "MONTHLY", null, 1, thisMonth);
		String fillToken = openPage(session);
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/recurring/{id}/fill", living).session(session).with(csrf()).param("formToken", fillToken)
					.param("period", thisMonth.toString()).param("amount", "27000"))
				.andExpect(redirectedUrl("/recurring"));
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement WHERE recurring_item_id = ?", Integer.class,
				living)).isEqualTo(1);
	}

	private String openNewForm(MockHttpSession session) throws Exception {
		return token(mvc.perform(get("/recurring/new").session(session)).andReturn().getResponse().getContentAsString());
	}

	private String openPage(MockHttpSession session) throws Exception {
		return token(mvc.perform(get("/recurring").session(session)).andReturn().getResponse().getContentAsString());
	}

	private static String token(String html) {
		Matcher m = TOKEN.matcher(html);
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private Long insertItem(Long user, Long account, String name, String direction, String mode, String amount,
			String cycle, Integer anchor, int day, YearMonth start) {
		jdbc.update("INSERT INTO recurring_item (user_id, name, direction, amount_mode, amount, cycle, anchor_month, "
				+ "day_of_month, account_id, start_month, posting_from, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
				user, name, direction, mode, amount == null ? null : new java.math.BigDecimal(amount), cycle, anchor, day,
				account, start.atDay(1), start.atDay(1));
		return jdbc.queryForObject("SELECT MAX(id) FROM recurring_item", Long.class);
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
