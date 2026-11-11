package com.sideproject.stockledger.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import java.util.List;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.sideproject.stockledger.FakeTwseConfig;
import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.AllocationLine;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.CashMovementForm;
import com.sideproject.stockledger.dto.ConfirmRow;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.StockSourceInput;
import com.sideproject.stockledger.dto.StockSourceTable;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;
import com.sideproject.stockledger.service.CashMovementService;
import com.sideproject.stockledger.service.OnboardingService;
import com.sideproject.stockledger.service.TradeService;

/** 第一次使用引導的轉址與歡迎、完成頁（設計文件 2026-10-25-onboarding 3、5）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfig.class, FakeTwseConfig.class })
class OnboardingControllerTest {

	private static final String ACCOUNT_HINT = "至少要儲存一個證券交割戶，才能往下走。";

	private static final String EDIT_NOTE = "正在修改已儲存的資料；改好按「儲存修改」。剛才打到一半的內容會保留，改完自動放回來。";

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
	private OnboardingService onboardingService;

	@Autowired
	private CashMovementService cashMovements;

	@Autowired
	private TradeService trades;

	@Autowired
	private AppUserDetailsService userDetails;

	@Autowired
	private FakeTwseConfig.FakeTwseClient twse;

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
		if (!users.existsByUsername("ob-cy")) {
			users.save(AppUser.create("ob-cy", encoder.encode("password123"), "ob-cy", Role.USER,
					LocalDateTime.now(clock)));
		}
		setStep("WELCOME");
	}

	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void newUserIsRedirectedToWelcome() throws Exception {
		mvc.perform(get("/"))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/welcome"));
	}

	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void doneUserIsSentHome() throws Exception {
		setStep("DONE");

		mvc.perform(get("/"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("淨資產")));
		mvc.perform(get("/welcome")).andExpect(redirectedUrl("/"));
		mvc.perform(get("/welcome/done")).andExpect(redirectedUrl("/"));
		mvc.perform(get("/welcome/accounts")).andExpect(redirectedUrl("/"));
		mvc.perform(post("/welcome/accounts/preview").param("name", "玉山").with(csrf())).andExpect(redirectedUrl("/"));
		mvc.perform(get("/welcome/loans")).andExpect(redirectedUrl("/"));
		mvc.perform(post("/welcome/loans/preview").param("name", "玉山").with(csrf())).andExpect(redirectedUrl("/"));
		mvc.perform(post("/welcome/loans/none").with(csrf())).andExpect(redirectedUrl("/"));
		mvc.perform(get("/welcome/stocks")).andExpect(redirectedUrl("/"));
		mvc.perform(post("/welcome/stocks/preview").param("stockCode", "0050").with(csrf()))
			.andExpect(redirectedUrl("/"));
		mvc.perform(post("/welcome/stocks/go").param("to", "next").with(csrf())).andExpect(redirectedUrl("/"));
		mvc.perform(post("/welcome/stocks/sources/add").with(csrf())).andExpect(redirectedUrl("/"));
		mvc.perform(get("/welcome/collateral")).andExpect(redirectedUrl("/"));
		mvc.perform(get("/welcome/collateral/1")).andExpect(redirectedUrl("/"));
		mvc.perform(post("/welcome/collateral/1/preview").with(csrf())).andExpect(redirectedUrl("/"));
		mvc.perform(post("/welcome/collateral/1/go").param("to", "next").with(csrf())).andExpect(redirectedUrl("/"));
	}

	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void welcomePageShowsStepperAndChoices() throws Exception {
		mvc.perform(get("/welcome"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("歡迎，我們一步一步把資料放進來")))
			.andExpect(content().string(containsString("我自己來，不用引導")))
			.andExpect(content().string(containsString("class=\"stepper\"")))
			.andExpect(content().string(containsString("tabindex=\"-1\" autofocus")));
	}

	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void skipFinishes() throws Exception {
		mvc.perform(post("/welcome/skip").with(csrf()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/"));

		assertThat(step()).isEqualTo("DONE");
	}

	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void skipNeedsCsrf() throws Exception {
		mvc.perform(post("/welcome/skip")).andExpect(status().isForbidden());

		assertThat(step()).isEqualTo("WELCOME");
	}

	/**
	 * 「先離開」回總覽，進度保留：同一個 session 裡 GET / 看到總覽（頂端有引導提醒）；
	 * 新的 session（重新登入）GET / 再回到同一步。
	 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void leaveKeepsStep() throws Exception {
		setStep("LOANS");
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/welcome/leave").session(session).with(csrf()))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/"));

		assertThat(step()).isEqualTo("LOANS");
		mvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("第一次使用引導還有沒完成的步驟")));
		mvc.perform(get("/")).andExpect(redirectedUrl("/welcome/loans"));
	}

	/** 登出再登入後，從走得最遠的那一步接著做（進度存在資料庫，不在 session）。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void resumesAtFurthestStepAfterRelogin() throws Exception {
		setStep("STOCKS");

		mvc.perform(get("/")).andExpect(redirectedUrl("/welcome/stocks"));
	}

	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void donePageShowsSummaryAndFinishGoesHome() throws Exception {
		mvc.perform(get("/welcome/done"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("完成了")))
			.andExpect(content().string(containsString("到總覽看看")));

		mvc.perform(post("/welcome/finish").with(csrf()))
			.andExpect(redirectedUrl("/"));
		assertThat(step()).isEqualTo("DONE");
	}

	// ---------- 帳戶這一步（Task 4） ----------

	/** 進帳戶頁就記進度；畫面照樣稿：表單、儲存按鈕、先離開／下一步，沒有交割戶時下一步不能按並顯示提示。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void accountsPageShowsFormAndReachesStep() throws Exception {
		mvc.perform(get("/welcome/accounts"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("你的錢放在哪些帳戶？")))
			.andExpect(content().string(containsString("儲存這個帳戶")))
			.andExpect(content().string(containsString("先離開，下次再繼續")))
			.andExpect(content().string(containsString("下一步：借款 →")))
			.andExpect(content().string(containsString(ACCOUNT_HINT)))
			.andExpect(content().string(containsString("填存摺或 App 上現在看到的餘額就好。")))
			.andExpect(content().string(containsString("value=\"" + LocalDate.now(clock) + "\"")))
			.andExpect(content().string(not(containsString("已儲存的帳戶"))));

		assertThat(step()).isEqualTo("ACCOUNTS");
	}

	/** 預覽：列出所有欄位（沒填的是空字串，畫面顯示「（沒填）」），還沒存進資料庫。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void previewShowsAllFields() throws Exception {
		MvcResult result = mvc.perform(post("/welcome/accounts/preview").param("name", "玉山交割戶")
			.param("type", "SETTLEMENT").param("institution", "玉山證券").param("amount", "")
			.param("date", LocalDate.now(clock).toString()).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("確認要儲存這筆帳戶嗎？")))
			.andExpect(content().string(containsString("（沒填）")))
			.andReturn();

		assertThat(rows(result)).contains(new ConfirmRow("名稱", "玉山交割戶"), new ConfirmRow("現在的餘額", ""));
		assertThat(accountCount()).isZero();
	}

	/** 必填沒填：標題換成「還有欄位沒填好，不能儲存」，訊息是 CashAccountForm 的；只有「回去修改」。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void previewWithMissingFieldShowsError() throws Exception {
		MvcResult result = mvc.perform(post("/welcome/accounts/preview").param("name", "玉山交割戶")
			.param("type", "SETTLEMENT").param("institution", "").param("amount", "abc").with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("還有欄位沒填好，不能儲存")))
			.andExpect(content().string(containsString("請輸入券商或銀行")))
			.andExpect(content().string(not(containsString("確認儲存"))))
			// 打的字都還在，包含格式錯的金額
			.andExpect(content().string(containsString("value=\"玉山交割戶\"")))
			.andExpect(content().string(containsString("value=\"abc\"")))
			.andReturn();

		assertThat(errors(result)).containsExactly("請輸入券商或銀行", "請輸入整數金額");
		assertThat(accountCount()).isZero();
	}

	/** 「確認儲存」按兩次（同一個一次性代號）只存一筆。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void saveTwiceCreatesOneAccount() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = previewToken(session, "/welcome/accounts/preview", "玉山交割戶", "50000");

		for (int i = 0; i < 2; i++) {
			mvc.perform(accountPost("/welcome/accounts/save", "玉山交割戶", "50000").param("formToken", token)
				.session(session))
				.andExpect(redirectedUrl("/welcome/accounts"));
		}

		assertThat(accountCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement WHERE type = 'OPENING'", Integer.class))
			.isEqualTo(1);
		mvc.perform(get("/welcome/accounts").session(session))
			.andExpect(content().string(containsString("已儲存的帳戶")))
			.andExpect(content().string(containsString("玉山交割戶（證券交割戶）")))
			.andExpect(content().string(not(containsString(ACCOUNT_HINT))));
	}

	/** 存檔時才發現的錯誤（例如另一個分頁剛存了同名帳戶）：不存，回到錯誤畫面，欄位不鎖、打的字都在。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void saveErrorShowsErrorsAndKeepsTyping() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = previewToken(session, "/welcome/accounts/preview", "玉山交割戶", "");
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "SELECT id, '玉山交割戶', 'SETTLEMENT', '玉山證券', NOW() FROM app_user WHERE username = 'ob-cy'");

		mvc.perform(post("/welcome/accounts/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("還有欄位沒填好，不能儲存")))
			.andExpect(content().string(containsString("帳戶名稱已存在")))
			.andExpect(content().string(containsString("value=\"玉山交割戶\"")))
			.andExpect(content().string(not(containsString("<fieldset class=\"step-fields\" disabled"))));

		assertThat(accountCount()).isEqualTo(1);
	}

	/** 確認過什麼就存什麼：預覽時是 A，「確認儲存」時表單送來 B，存進去的還是 A。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void saveStoresWhatWasConfirmed() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = previewToken(session, "/welcome/accounts/preview", "玉山交割戶", "50000");

		mvc.perform(accountPost("/welcome/accounts/save", "偷改的名稱", "99999").param("formToken", token)
			.session(session))
			.andExpect(redirectedUrl("/welcome/accounts"));

		assertThat(jdbc.queryForList("SELECT name FROM cash_account", String.class)).containsExactly("玉山交割戶");
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement", BigDecimal.class))
			.isEqualByComparingTo("50000");
	}

	/**
	 * 確認中欄位鎖住；這時按「回去修改」（鎖住的欄位不會送出，只送代號），表單放回確認時的內容，什麼都沒少。
	 * 收起來的內容用過就沒了：再拿同一個代號按「確認儲存」不會存，提示表單已經送出過或已失效。
	 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void confirmLocksFieldsAndBackRestoresConfirmedValues() throws Exception {
		MockHttpSession session = new MockHttpSession();
		MvcResult preview = mvc.perform(accountPost("/welcome/accounts/preview", "玉山交割戶", "50000").session(session))
			.andExpect(content().string(containsString("<fieldset class=\"step-fields\" disabled")))
			.andReturn();
		String token = (String) preview.getModelAndView().getModel().get("formToken");

		mvc.perform(post("/welcome/accounts/go").param("to", "accounts").param("formToken", token).session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts"));
		mvc.perform(get("/welcome/accounts").session(session))
			.andExpect(content().string(containsString("value=\"玉山交割戶\"")))
			.andExpect(content().string(containsString("value=\"50000\"")))
			.andExpect(content().string(not(containsString("<fieldset class=\"step-fields\" disabled"))));

		// 回去修改時代號跟著收起來的內容一起用掉（final review 4），所以是「已經送出過或已失效」
		mvc.perform(post("/welcome/accounts/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts"))
			.andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		assertThat(accountCount()).isZero();
		mvc.perform(get("/welcome/accounts").session(session)) // 上一個轉址的 flash 存在 session
			.andExpect(content().string(containsString(ALREADY_SUBMITTED)));
	}

	/** 修改的預覽和存檔檢查一樣：初始現金餘額日期不能晚於帳戶裡已經有的異動。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editPreviewChecksEarlierMovements() throws Exception {
		LocalDate today = LocalDate.now(clock);
		Long id = onboardingService.addAccount(new CashAccountForm("玉山", CashAccountType.SETTLEMENT, "玉山證券"),
				new OpeningForm(today.minusDays(10), new BigDecimal("1000"))).getId();
		cashMovements.record(new CashMovementForm(CashMovementType.DEPOSIT, today.minusDays(5), null, id,
				new BigDecimal("100"), null));

		MvcResult result = mvc.perform(post("/welcome/accounts/" + id + "/preview").param("name", "玉山")
			.param("type", "SETTLEMENT").param("institution", "玉山證券").param("amount", "1000")
			.param("date", today.minusDays(2).toString()).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("還有欄位沒填好，不能儲存")))
			.andReturn();

		assertThat(errors(result)).singleElement().asString().contains("已經有異動");
	}

	/** 直接打開修改網址（書籤、上一頁）：表單上打到一半的新帳戶一樣先收起來，取消修改後放回來。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editByUrlKeepsNewEntryDraft() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long id = saveAccount(session, "玉山", "");
		mvc.perform(post("/welcome/accounts/go").param("to", "welcome").param("name", "台銀打到一半").session(session)
			.with(csrf()));

		mvc.perform(get("/welcome/accounts/" + id + "/edit").session(session))
			.andExpect(content().string(containsString("value=\"玉山\"")));
		mvc.perform(post("/welcome/accounts/cancel-edit").session(session).with(csrf()));

		mvc.perform(get("/welcome/accounts").session(session))
			.andExpect(content().string(containsString("value=\"台銀打到一半\"")));
	}

	/**
	 * 修改已儲存的帳戶：打到一半的新帳戶先收起來；修改畫面有「儲存修改」「取消修改」和提示；
	 * 存好後帳戶改了，剛才打到一半的內容放回表單。
	 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editSavedAccountKeepsNewEntryDraft() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long id = saveAccount(session, "玉山", "50000");

		mvc.perform(post("/welcome/accounts/" + id + "/edit").param("name", "台銀打到一半").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts/" + id + "/edit"));
		mvc.perform(get("/welcome/accounts/" + id + "/edit").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"玉山\"")))
			.andExpect(content().string(containsString("value=\"50000\"")))
			.andExpect(content().string(containsString("儲存修改")))
			.andExpect(content().string(containsString("取消修改")))
			.andExpect(content().string(containsString(EDIT_NOTE)))
			.andExpect(content().string(not(containsString("台銀打到一半"))));

		String token = previewToken(session, "/welcome/accounts/" + id + "/preview", "玉山交割戶", "60000");
		mvc.perform(accountPost("/welcome/accounts/" + id + "/save", "玉山交割戶", "60000").param("formToken", token)
			.session(session))
			.andExpect(redirectedUrl("/welcome/accounts"));

		assertThat(jdbc.queryForObject("SELECT name FROM cash_account WHERE id = ?", String.class, id))
			.isEqualTo("玉山交割戶");
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE account_id = ?", BigDecimal.class, id))
			.isEqualByComparingTo("60000");
		assertThat(jdbc.queryForObject("SELECT reason FROM cash_movement_revision LIMIT 1", String.class))
			.isEqualTo("第一次使用引導修改");
		mvc.perform(get("/welcome/accounts").session(session))
			.andExpect(content().string(containsString("value=\"台銀打到一半\"")))
			.andExpect(content().string(not(containsString("儲存修改"))));
	}

	/** 修改中按「下一步」再回來：還在修改那一筆，改到一半的字也在；「取消修改」放回新帳戶的草稿。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editModeSurvivesNavigationAndCancelRestoresDraft() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long id = saveAccount(session, "玉山", "");
		mvc.perform(post("/welcome/accounts/" + id + "/edit").param("name", "台銀打到一半").session(session).with(csrf()));

		mvc.perform(post("/welcome/accounts/go").param("to", "loans").param("editing", id.toString())
			.param("name", "玉山改到一半").param("type", "SETTLEMENT").param("institution", "玉山證券")
			.session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));
		mvc.perform(get("/welcome/accounts").session(session)).andExpect(redirectedUrl("/welcome/accounts/" + id + "/edit"));
		mvc.perform(get("/welcome/accounts/" + id + "/edit").session(session))
			.andExpect(content().string(containsString("value=\"玉山改到一半\"")));

		mvc.perform(post("/welcome/accounts/cancel-edit").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts"));
		mvc.perform(get("/welcome/accounts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"台銀打到一半\"")));
		assertThat(jdbc.queryForObject("SELECT name FROM cash_account WHERE id = ?", String.class, id)).isEqualTo("玉山");
	}

	/** 別人的帳戶不能在引導裡修改。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void cannotEditOthersAccount() throws Exception {
		if (!users.existsByUsername("ob-dan")) {
			users.save(AppUser.create("ob-dan", encoder.encode("password123"), "ob-dan", Role.USER,
					LocalDateTime.now(clock)));
		}
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "SELECT id, '別人的戶', 'SETTLEMENT', '元大證券', NOW() FROM app_user WHERE username = 'ob-dan'");
		Long other = jdbc.queryForObject("SELECT id FROM cash_account WHERE name = '別人的戶'", Long.class);

		mvc.perform(get("/welcome/accounts/" + other + "/edit")).andExpect(status().isNotFound());
		mvc.perform(accountPost("/welcome/accounts/" + other + "/preview", "改掉", "")).andExpect(status().isNotFound());
	}

	/** 只有銀行帳戶時按「下一步」：不往前走，回到帳戶頁並顯示提示；有交割戶才到借款。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void nextNeedsSettlementAccount() throws Exception {
		MockHttpSession session = new MockHttpSession();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "SELECT id, '台銀活存', 'BANK_DEMAND', '台銀', NOW() FROM app_user WHERE username = 'ob-cy'");

		mvc.perform(post("/welcome/accounts/go").param("to", "loans").param("name", "玉山").session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts"));
		mvc.perform(get("/welcome/accounts").session(session))
			.andExpect(content().string(containsString(ACCOUNT_HINT)))
			.andExpect(content().string(containsString("value=\"玉山\"")))
			.andExpect(content().string(matchesPattern("(?s).*disabled[^>]*>下一步：借款 →.*")));

		saveAccount(session, "玉山交割戶", "");
		mvc.perform(post("/welcome/accounts/go").param("to", "loans").with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));
	}

	/** 確認畫面的代號只能用在它自己的存檔網址：新增帳戶的代號拿去存修改，當作過期，什麼都不改。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void confirmTokenOnlyWorksForItsOwnSaveUrl() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long id = saveAccount(session, "玉山", "");
		String token = previewToken(session, "/welcome/accounts/preview", "台銀交割戶", "");

		mvc.perform(post("/welcome/accounts/" + id + "/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts/" + id + "/edit"))
			.andExpect(flash().attribute("notice", "確認畫面已過期，請再按一次儲存"));

		assertThat(jdbc.queryForList("SELECT name FROM cash_account", String.class)).containsExactly("玉山");
	}

	// ---------- 借款這一步（Task 5） ----------

	private static final String LOAN_ASK = "你有拿去投資的借款嗎？";

	/** 還沒有借款：先問有沒有；按「沒有，略過」記到股票那一步並轉過去。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void askThenNoneSkipsToStocks() throws Exception {
		mvc.perform(get("/welcome/loans"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(LOAN_ASK)))
			.andExpect(content().string(containsString("有，我要記借款")))
			.andExpect(content().string(containsString("沒有，略過")))
			.andExpect(content().string(not(containsString("儲存這筆借款"))));
		assertThat(step()).isEqualTo("LOANS");

		mvc.perform(post("/welcome/loans/none").with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"));

		assertThat(step()).isEqualTo("STOCKS");
	}

	/** 按了「有」之後回到借款頁，直接是表單，不再問一次；表單欄位照樣稿，不含擔保股票。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void yesIsRemembered() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mvc.perform(get("/welcome/loans/form").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("記你的借款")));
		mvc.perform(get("/welcome/loans").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString(LOAN_ASK))))
			.andExpect(content().string(containsString("記你的借款")))
			.andExpect(content().string(containsString("怎麼還？")))
			.andExpect(content().string(containsString(
					"這是股票質押（已經記在系統裡的股票在第 4 步設定擔保；還沒記的質押股票填在下面）")))
			.andExpect(content().string(containsString("往來帳戶")))
			.andExpect(content().string(containsString("開辦費、手續費（選填）")))
			.andExpect(content().string(containsString("儲存這筆借款")))
			.andExpect(content().string(containsString("借款記完了，下一步：股票 →")))
			.andExpect(content().string(not(containsString("擔保股票（勾選拿去質押的持股）"))));
	}

	/** 到期日已經過了：確認區塊列出提醒，仍然可以確認儲存。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void loanPreviewWarnsPastDueDate() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		LocalDate today = LocalDate.now(clock);

		MvcResult preview = mvc.perform(loanPost("/welcome/loans/preview", account, today.minusDays(400), "100000",
				today.minusDays(30)).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("確認要儲存這筆借款嗎？")))
			.andExpect(content().string(containsString("已經過了")))
			.andReturn();
		String token = (String) preview.getModelAndView().getModel().get("formToken");

		mvc.perform(post("/welcome/loans/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));

		assertThat(loanCount()).isEqualTo(1);
	}

	/** 到期日早於或等於撥款日：錯誤，不能存。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void loanPreviewRejectsDueBeforeStart() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		LocalDate start = LocalDate.now(clock).minusDays(30);

		MvcResult result = mvc.perform(loanPost("/welcome/loans/preview", account, start, "100000", start)
			.session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("還有欄位沒填好，不能儲存")))
			.andExpect(content().string(not(containsString("確認儲存"))))
			.andReturn();

		assertThat(errors(result)).containsExactly("到期日要晚於撥款日");
		assertThat(loanCount()).isZero();
	}

	/** 存好一筆借款後在引導裡修改金額：撥款金額跟著改，清單顯示「已儲存的借款」。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void saveLoanThenEditAmount() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		LocalDate start = LocalDate.now(clock).minusDays(60);
		LocalDate due = LocalDate.now(clock).plusDays(300);
		Long loan = saveLoan(session, account, start, "100000", due);

		mvc.perform(get("/welcome/loans").session(session))
			.andExpect(content().string(containsString("已儲存的借款")))
			.andExpect(content().string(containsString("玉山質押（到期一次還本金＋利息、股票質押）")))
			.andExpect(content().string(not(containsString(LOAN_ASK))));
		mvc.perform(post("/welcome/loans/" + loan + "/edit").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans/" + loan + "/edit"));
		mvc.perform(get("/welcome/loans/" + loan + "/edit").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"100000\"")))
			.andExpect(content().string(containsString("儲存修改")));

		String token = loanToken(session, "/welcome/loans/" + loan + "/preview", account, start, "120000", due);
		mvc.perform(post("/welcome/loans/" + loan + "/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));

		assertThat(loanCount()).isEqualTo(1);
		assertThat(disbursement(loan)).isEqualByComparingTo("120000");
	}

	/** 借款改小、不夠付已經用它買的股票：先列出要調整的交易，按「確定修改」才存。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editLoanShortfallAsksFirst() throws Exception {
		LocalDate today = LocalDate.now(clock);
		Long account = onboardingService.addAccount(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT,
				"玉山證券"), null).getId();
		LoanForm form = new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, today.minusMonths(2),
				new BigDecimal("100000"), new BigDecimal("2.5"), null, account, null, null, null, today.plusDays(300),
				RepaymentMethod.BULLET, null, null, List.of(), null, null);
		Long loan = onboardingService.addLoan(form).getId();
		jdbc.update("INSERT IGNORE INTO stock (code, name, kind, dividends_per_year, updated_at) "
				+ "VALUES ('0050', '元大台灣50', 'ETF', 2, NOW())");
		trades.create(new TradeForm(TradeType.BUY, today.minusMonths(1), "0050", account, 1000L, new BigDecimal("73.5"),
				null, null, null, List.of(new AllocationLine(loan, new BigDecimal("73500")))));
		MockHttpSession session = new MockHttpSession();

		String token = loanToken(session, "/welcome/loans/" + loan + "/preview", account, today.minusMonths(2), "50000",
				today.plusDays(300));
		MvcResult asked = mvc.perform(post("/welcome/loans/" + loan + "/save").param("formToken", token)
			.session(session).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("差 23,500 元")))
			.andExpect(content().string(containsString("0050")))
			.andExpect(content().string(containsString("確定修改")))
			.andExpect(content().string(containsString("/welcome/loans/" + loan + "/confirm-shortfall")))
			.andReturn();
		assertThat(disbursement(loan)).isEqualByComparingTo("100000");

		String again = (String) asked.getModelAndView().getModel().get("formToken");
		mvc.perform(post("/welcome/loans/" + loan + "/confirm-shortfall").param("formToken", again).session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));

		assertThat(disbursement(loan)).isEqualByComparingTo("50000");
		assertThat(jdbc.queryForObject("SELECT amount FROM source_allocation WHERE loan_id = ?", BigDecimal.class, loan))
			.isEqualByComparingTo("50000");
	}

	/** 金額打錯格式：錯誤畫面用 messages.properties 的訊息，打的字還在；按上一步存成草稿，回來時勾選的股票質押也還在。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void badAmountKeepsTypingAndDraftKeepsPledge() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		LocalDate start = LocalDate.now(clock).minusDays(30);

		MvcResult result = mvc.perform(loanPost("/welcome/loans/preview", account, start, "abc", start.plusDays(300))
			.session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("還有欄位沒填好，不能儲存")))
			.andExpect(content().string(containsString("value=\"abc\"")))
			.andReturn();
		assertThat(errors(result)).containsExactly("請輸入整數金額");

		mvc.perform(loanPost("/welcome/loans/go", account, start, "abc", start.plusDays(300)).param("to", "accounts")
			.session(session))
			.andExpect(redirectedUrl("/welcome/accounts"));
		mvc.perform(get("/welcome/loans").session(session))
			.andExpect(content().string(not(containsString(LOAN_ASK))))
			.andExpect(content().string(containsString("value=\"abc\"")))
			.andExpect(content().string(matchesPattern("(?s).*name=\"pledge\" value=\"true\" checked.*")));
	}

	// ---------- 借款這一步：還沒記在系統裡的質押股票（設計文件 2026-11-04-pledged-stocks 4.1、4.2） ----------

	private static final String NEW_PLEDGES = "還沒記在系統裡的質押股票";

	private static final LocalDate PLEDGE_DAY = LocalDate.of(2026, 7, 28);

	/**
	 * 確認畫面每檔一行「質押的股票　00646 3,000 股（2026-07-28，每股 75.15）」；存檔後記成期初持股，
	 * 第 4 步擔保頁 00646 已勾、擔保 3,000；修改已儲存的借款時不顯示這個表格（只有新增時能記）。
	 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void wizardLoanRecordsNewPledges() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		insertPledgeStocks();

		MvcResult preview = mvc.perform(pledgePost("/welcome/loans/preview", account).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("確認要儲存這筆借款嗎？")))
			.andExpect(content().string(containsString("00646 3,000 股（2026-07-28，每股 75.15）")))
			.andReturn();
		assertThat(rows(preview)).contains(new ConfirmRow("質押的股票", "00646 3,000 股（2026-07-28，每股 75.15）"));
		String token = (String) preview.getModelAndView().getModel().get("formToken");

		mvc.perform(post("/welcome/loans/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));

		Long loan = jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
		Long stock = jdbc.queryForObject("SELECT id FROM stock WHERE code = '00646'", Long.class);
		assertThat(jdbc.queryForList("SELECT CONCAT(type, '|', trade_date, '|', shares) FROM trade", String.class))
			.containsExactly("OPENING|2026-07-28|3000");
		assertThat(jdbc.queryForList("SELECT shares FROM pledge_collateral WHERE loan_id = ?", Long.class, loan))
			.containsExactly(3000L);

		mvc.perform(get("/welcome/collateral/" + loan).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(matchesPattern("(?s).*value=\"" + stock + "\"[^>]*checked.*")))
			.andExpect(content().string(containsString("value=\"3000\"")))
			.andExpect(content().string(containsString("　3,000 股")));

		mvc.perform(get("/welcome/loans/" + loan + "/edit").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("name=\"newPledges[")))) // 頁面註解裡有表格名稱，只看欄位
			.andExpect(content().string(not(containsString("再加一檔"))));
	}

	/** 加列、拿掉都保留打到一半的字；按上一步再回來，列還在。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void wizardPledgeRowsSurviveBack() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		insertPledgeStocks();

		mvc.perform(get("/welcome/loans/form").session(session))
			.andExpect(content().string(containsString(NEW_PLEDGES)))
			.andExpect(content().string(containsString("name=\"newPledges[0].stockCode\"")))
			.andExpect(content().string(containsString("/welcome/loans/pledges?action=add")));

		mvc.perform(pledgePost("/welcome/loans/pledges", account).param("action", "add").session(session))
			.andExpect(redirectedUrl("/welcome/loans"));
		mvc.perform(get("/welcome/loans").session(session))
			.andExpect(content().string(containsString("name=\"newPledges[0].stockCode\" value=\"00646\"")))
			.andExpect(content().string(containsString("name=\"newPledges[0].shares\" value=\"3000\"")))
			.andExpect(content().string(containsString("name=\"newPledges[1].stockCode\"")))
			.andExpect(content().string(containsString("value=\"玉山質押\"")));

		mvc.perform(pledgePost("/welcome/loans/go", account).param("to", "accounts").session(session))
			.andExpect(redirectedUrl("/welcome/accounts"));
		mvc.perform(get("/welcome/loans").session(session))
			.andExpect(content().string(containsString("name=\"newPledges[0].stockCode\" value=\"00646\"")))
			.andExpect(content().string(containsString("name=\"newPledges[0].date\" value=\"2026-07-28\"")));

		mvc.perform(pledgePost("/welcome/loans/pledges", account).param("action", "remove").param("index", "0")
			.session(session))
			.andExpect(redirectedUrl("/welcome/loans"));
		mvc.perform(get("/welcome/loans").session(session))
			.andExpect(content().string(not(containsString("value=\"00646\""))));
		assertThat(loanCount()).isZero();
	}

	/** 「帶入收盤價」：價格空白的列帶入證交所那天的收盤價並註明來源（假證交所，不連網路）。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void wizardPledgePricesFillsClose() throws Exception {
		twse.failure = null;
		twse.monthly = """
				{"stat":"OK","date":"20260701","title":"115年07月 00646 元大S&P500       各日成交資訊","fields":["日期","成交股數","成交金額","開盤價","最高價","最低價","收盤價","漲跌價差","成交筆數","註記"],"data":[["115/07/28","11,632,330","875,670,735","75.40","75.50","75.15","75.15","-1.05","4,829",""]]}
				""";
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		insertPledgeStocks();

		mvc.perform(pledgePost("/welcome/loans/pledges", account, "").param("action", "prices").session(session))
			.andExpect(redirectedUrl("/welcome/loans"));
		mvc.perform(get("/welcome/loans").session(session))
			.andExpect(content().string(containsString("name=\"newPledges[0].price\" value=\"75.15\"")))
			.andExpect(content().string(containsString("證交所 2026-07-28 收盤價")));
	}

	/**
	 * 先沒勾股票質押送出（例如欄位沒填完、畫回表單），質押股票列被拿掉；之後勾股票質押時仍要直接看到一列空白欄位，
	 * 不能只剩「＋ 再加一檔」（使用者 2026-11-09 回報質押0728 因此漏記擔保股票）。
	 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void pledgeRowsAlwaysShowOneBlankRow() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");

		MvcResult result = mvc.perform(post("/welcome/loans/preview").param("name", "質押0728")
			.param("bank", "玉山銀行")
			.param("origin", "NEW")
			.param("repaymentMethod", "BULLET")
			.param("startDate", PLEDGE_DAY.toString())
			.param("payAccountId", account.toString())
			.session(session)
			.with(csrf()))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(errors(result)).isNotEmpty();
		assertThat(result.getResponse().getContentAsString()).contains("name=\"newPledges[0].stockCode\"")
			.contains("name=\"newPledges[0].shares\"")
			.contains("name=\"newPledges[0].date\"")
			.contains("name=\"newPledges[0].price\"");
	}

	/** 同一檔分批質押（DR-055）：前一筆質押記過 00646，下一筆質押再記 00646 也能存，各自一筆期初持股與擔保。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void wizardSecondPledgeOfSameStockSaves() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		insertPledgeStocks();
		String token = (String) mvc.perform(pledgePost("/welcome/loans/preview", account).session(session))
			.andReturn().getModelAndView().getModel().get("formToken");
		mvc.perform(post("/welcome/loans/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));

		MvcResult again = mvc.perform(pledgePost("/welcome/loans/preview", account).session(session))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(errors(again)).isNullOrEmpty();
		mvc.perform(post("/welcome/loans/save").param("formToken", (String) again.getModelAndView().getModel()
			.get("formToken")).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));

		assertThat(loanCount()).isEqualTo(2);
		assertThat(jdbc.queryForList("SELECT CONCAT(t.type, '|', t.shares) FROM trade t JOIN stock s ON s.id = t.stock_id "
				+ "WHERE s.code = '00646' AND t.voided = FALSE", String.class))
			.containsExactly("OPENING|3000", "OPENING|3000");
		assertThat(jdbc.queryForList("SELECT c.shares FROM pledge_collateral c JOIN stock s ON s.id = c.stock_id "
				+ "WHERE s.code = '00646' ORDER BY c.loan_id", Long.class))
			.containsExactly(3000L, 3000L);
	}

	/** 修改已儲存的借款：送來的質押股票列不理（只有新增能記），不會多出期初持股。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void wizardEditIgnoresNewPledges() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		insertPledgeStocks();
		Long loan = saveLoan(session, account, PLEDGE_DAY, "500000", PLEDGE_DAY.plusYears(1));

		MvcResult preview = mvc.perform(pledgePost("/welcome/loans/" + loan + "/preview", account).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("確認要儲存這筆修改嗎？")))
			.andReturn();
		assertThat(rows(preview)).extracting(ConfirmRow::label).doesNotContain("質押的股票");
		String token = (String) preview.getModelAndView().getModel().get("formToken");
		mvc.perform(post("/welcome/loans/" + loan + "/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));

		assertThat(tradeTotal()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pledge_collateral", Integer.class)).isZero();
	}

	/** 沒勾股票質押（信貸）卻帶著質押股票列（例如沒有 JavaScript）：列不理，借款照存，不記期初持股。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void wizardCreditIgnoresNewPledges() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		insertPledgeStocks();
		MockHttpServletRequestBuilder credit = post("/welcome/loans/preview").param("name", "土銀信貸")
			.param("bank", "土地銀行")
			.param("origin", "NEW")
			.param("repaymentMethod", "BULLET")
			.param("startDate", PLEDGE_DAY.toString())
			.param("startPrincipal", "500000")
			.param("annualRatePercent", "2.5")
			.param("dueDate", PLEDGE_DAY.plusYears(1).toString())
			.param("payAccountId", account.toString())
			.param("newPledges[0].stockCode", "00646")
			.param("newPledges[0].shares", "3000")
			.param("newPledges[0].date", PLEDGE_DAY.toString())
			.param("newPledges[0].price", "75.15")
			.with(csrf());

		MvcResult preview = mvc.perform(credit.session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("確認要儲存這筆借款嗎？")))
			.andReturn();
		assertThat(rows(preview)).extracting(ConfirmRow::label).doesNotContain("質押的股票");
		String token = (String) preview.getModelAndView().getModel().get("formToken");
		mvc.perform(post("/welcome/loans/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));

		assertThat(loanCount()).isEqualTo(1);
		assertThat(tradeTotal()).isZero();
	}

	/** 加列時某一格打錯（股數 abc）：其他列和這一列的其他欄位都還在，不會整個表格清空。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void wizardBadValueKeepsOtherRows() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		insertPledgeStocks();

		mvc.perform(loanPost("/welcome/loans/pledges", account, PLEDGE_DAY, "500000", PLEDGE_DAY.plusYears(1))
			.param("newPledges[0].stockCode", "00646")
			.param("newPledges[0].shares", "abc")
			.param("newPledges[0].date", PLEDGE_DAY.toString())
			.param("newPledges[0].price", "75.15")
			.param("newPledges[1].stockCode", "00662")
			.param("newPledges[1].shares", "2000")
			.param("newPledges[1].date", PLEDGE_DAY.toString())
			.param("newPledges[1].price", "115.7")
			.param("action", "add")
			.session(session))
			.andExpect(redirectedUrl("/welcome/loans"));

		mvc.perform(get("/welcome/loans").session(session))
			.andExpect(content().string(containsString("name=\"newPledges[0].stockCode\" value=\"00646\"")))
			.andExpect(content().string(containsString("name=\"newPledges[0].price\" value=\"75.15\"")))
			.andExpect(content().string(containsString("name=\"newPledges[1].stockCode\" value=\"00662\"")))
			.andExpect(content().string(containsString("name=\"newPledges[1].shares\" value=\"2000\"")))
			.andExpect(content().string(containsString("name=\"newPledges[2].stockCode\"")));
	}

	private int tradeTotal() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM trade", Integer.class);
	}

	private void insertPledgeStocks() {
		jdbc.update("INSERT IGNORE INTO stock (code, name, kind, dividends_per_year, updated_at) "
				+ "VALUES ('00646', '元大S&P500', 'ETF', 1, NOW()), ('00662', '富邦NASDAQ', 'ETF', 1, NOW())");
	}

	/** 到期一次還的股票質押，加一列還沒記在系統裡的 00646（2026-07-28，3,000 股，每股 75.15）。 */
	private MockHttpServletRequestBuilder pledgePost(String url, Long account) {
		return pledgePost(url, account, "75.15");
	}

	private MockHttpServletRequestBuilder pledgePost(String url, Long account, String price) {
		return loanPost(url, account, PLEDGE_DAY, "500000", PLEDGE_DAY.plusYears(1))
			.param("newPledges[0].stockCode", "00646")
			.param("newPledges[0].shares", "3000")
			.param("newPledges[0].date", PLEDGE_DAY.toString())
			.param("newPledges[0].price", price);
	}

	// ---------- 股票這一步（Task 6） ----------

	/** 進股票頁就記進度；畫面照樣稿：記法二選一、說明文字、資金來源、儲存與下一步按鈕。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void stocksPageShowsFormAndReachesStep() throws Exception {
		MockHttpSession session = new MockHttpSession();
		saveAccount(session, "玉山交割戶", "");

		mvc.perform(get("/welcome/stocks").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("記你的股票")))
			.andExpect(content().string(containsString("一次買進")))
			.andExpect(content().string(containsString("目前持有（不記得每次怎麼買）")))
			.andExpect(content().string(containsString("開始記帳的日期")))
			.andExpect(content().string(containsString("平均成本（含手續費，不記得填大概的）")))
			.andExpect(content().string(containsString("券商 App 的庫存頁有「成本均價」；查不到就填今天的收盤價，報酬率會從今天開始算。")))
			.andExpect(content().string(containsString("從哪個交割戶扣款")))
			.andExpect(content().string(containsString("手續費系統會自動算，和對帳單不同再到股票頁修改。")))
			.andExpect(content().string(containsString("這筆錢從哪裡來？")))
			.andExpect(content().string(containsString("自己的錢")))
			.andExpect(content().string(containsString("儲存這筆股票")))
			.andExpect(content().string(containsString("先離開，下次再繼續")))
			.andExpect(content().string(containsString("股票記完了，下一步 →")));
		assertThat(step()).isEqualTo("STOCKS");
	}

	/** 沒填價格：不能儲存，訊息和股票頁的新增交易一樣（都是 TradeService 的訊息）。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void stockPreviewMissingPrice() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		addStocks();
		LocalDate day = LocalDate.now(clock).minusDays(10);

		MvcResult result = mvc.perform(stockPost("/welcome/stocks/preview", "BUY", "0050", day, "1000", "", account, null)
			.session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("還有欄位沒填好，不能儲存")))
			.andExpect(content().string(containsString("value=\"1000\"")))
			.andReturn();
		assertThat(errors(result)).containsExactly("請輸入成交價");
		assertThat(result.getModelAndView().getModel().get("formToken")).isNull();

		// 股票頁的新增交易：同樣沒填價格，同一句訊息
		String stockPageToken = (String) mvc.perform(get("/stocks/trades/new").session(session))
			.andReturn()
			.getModelAndView()
			.getModel()
			.get("formToken");
		mvc.perform(post("/stocks/trades/new").param("type", "BUY")
			.param("date", day.toString())
			.param("stockCode", "0050")
			.param("accountId", account.toString())
			.param("shares", "1000")
			.param("price", "")
			.param("formToken", stockPageToken)
			.session(session)
			.with(csrf()))
			.andExpect(content().string(containsString("請輸入成交價")));
		assertThat(tradeCount()).isZero();
	}

	/** 目前持有與一次買進各存一筆；用借款買時那筆借款出全額（由 Service 算總成本）。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void saveOpeningAndBuy() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		LocalDate today = LocalDate.now(clock);
		Long loan = saveLoan(session, account, today.minusDays(60), "500000", today.plusDays(300));
		addStocks();

		saveStock(session, "OPENING", "0050", today.minusDays(20), "5000", "73.5", account, null);
		MvcResult preview = mvc.perform(stockPost("/welcome/stocks/preview", "BUY", "0056", today.minusDays(10), "1000",
				"35.2", account, loan).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("確認要儲存這筆股票嗎？")))
			.andReturn();
		assertThat(rows(preview)).extracting(ConfirmRow::label).contains("交割戶", "資金來源");
		String token = (String) preview.getModelAndView().getModel().get("formToken");
		mvc.perform(post("/welcome/stocks/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"));

		assertThat(tradeCount()).isEqualTo(2);
		BigDecimal buyTotal = jdbc.queryForObject("SELECT amount FROM trade WHERE type = 'BUY'", BigDecimal.class);
		assertThat(jdbc.queryForObject("SELECT amount FROM source_allocation WHERE loan_id = ?", BigDecimal.class, loan))
			.isEqualByComparingTo(buyTotal);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_allocation", Integer.class)).isEqualTo(1);
		mvc.perform(get("/welcome/stocks").session(session))
			.andExpect(content().string(containsString("已儲存的股票")))
			.andExpect(content().string(containsString("0050　5 張　73.5 元")))
			.andExpect(content().string(containsString("0056　1 張　35.2 元（玉山質押 1）")));
	}

	/** 修改已儲存的股票：寫修改紀錄（原因自動填）；記法不能改，顯示 TradeService 的訊息。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editStockWritesRevisionAndKeepsType() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		addStocks();
		LocalDate day = LocalDate.now(clock).minusDays(20);
		Long id = saveStock(session, "OPENING", "0050", day, "5000", "73.5", account, null);

		MvcResult changed = mvc.perform(stockPost("/welcome/stocks/" + id + "/preview", "BUY", "0050", day, "5000",
				"73.5", account, null).session(session))
			.andExpect(content().string(containsString("還有欄位沒填好，不能儲存")))
			.andReturn();
		assertThat(errors(changed)).containsExactly("交易類型不能改；請作廢後重新記一筆");

		MvcResult preview = mvc.perform(stockPost("/welcome/stocks/" + id + "/preview", "OPENING", "0050", day, "6000",
				"73.5", account, null).session(session))
			.andExpect(content().string(containsString("確認要儲存這筆修改嗎？")))
			.andReturn();
		String token = (String) preview.getModelAndView().getModel().get("formToken");
		mvc.perform(post("/welcome/stocks/" + id + "/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"));

		assertThat(tradeCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT shares FROM trade WHERE id = ?", Long.class, id)).isEqualTo(6000L);
		assertThat(jdbc.queryForList("SELECT DISTINCT reason FROM trade_revision WHERE trade_id = ?", String.class, id))
			.containsExactly("第一次使用引導修改");
	}

	// ---------- 資金來源按張數分配（設計文件 3.4，Task 9） ----------

	/**
	 * 在股票頁拆過資金來源（300,000 是借款、其餘自己的錢，換不回整數張）：引導的修改頁用「填金額」顯示原本的金額，
	 * 不再是唯讀說明（設計文件 3.4）；只改日期存檔，300,000 原樣保留。
	 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editAmountSplitShowsAmountMode() throws Exception {
		// 先用 Service 準備資料（mvc.perform 之後測試執行緒就沒有登入的使用者了）
		LocalDate today = LocalDate.now(clock);
		Long account = onboardingService.addAccount(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT,
				"玉山證券"), null).getId();
		Long loan = onboardingService.addLoan(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null,
				today.minusDays(60), new BigDecimal("500000"), new BigDecimal("2.5"), null, account, null, null, null,
				today.plusDays(300), RepaymentMethod.BULLET, null, null, List.of(), null, null)).getId();
		addStocks();
		MockHttpSession session = new MockHttpSession();
		Long id = trades.create(new TradeForm(TradeType.BUY, today.minusDays(10), "0050", account, 10000L,
				new BigDecimal("73.5"), null, null, null, List.of(new AllocationLine(loan, new BigDecimal("300000")))))
			.getId();

		mvc.perform(get("/welcome/stocks").session(session))
			.andExpect(content().string(containsString("0050　10 張　73.5 元（玉山質押 300,000 元、自己 436,047 元）")));
		MvcResult page = mvc.perform(get("/welcome/stocks/" + id + "/edit").session(session))
			.andExpect(content().string(not(containsString("已在股票頁拆分資金來源"))))
			.andExpect(content().string(containsString("金額（元）")))
			.andExpect(content().string(containsString("改填張數")))
			.andReturn();
		assertThat(page.getModelAndView().getModel().get("sources")).isEqualTo(new StockSourceInput(
				StockSourceInput.Mode.AMOUNT, List.of(new StockSourceInput.Row(loan, "300000"))));

		MvcResult preview = mvc.perform(splitPost("/welcome/stocks/" + id + "/preview", account, today.minusDays(9),
				"AMOUNT", new Object[] { loan, "300000" }).session(session))
			.andExpect(content().string(containsString("確認要儲存這筆修改嗎？")))
			.andReturn();
		assertThat(rows(preview)).contains(new ConfirmRow("資金來源", "玉山質押（300,000 元）"),
				new ConfirmRow("", "自己的錢（436,047 元）"));
		confirm(session, preview, "/welcome/stocks/" + id + "/save");

		assertThat(jdbc.queryForObject("SELECT trade_date FROM trade WHERE id = ?", LocalDate.class, id))
			.isEqualTo(today.minusDays(9));
		assertThat(allocationsOf(id)).singleElement().satisfies(a -> assertThat(a).isEqualByComparingTo("300000"));
	}

	/** 0050 10 張 × 73.5（總成本 736,047）：玉山 4 張、土銀 3 張 → 存 294,419 與 220,814；清單寫張數。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void unitsSplitSavesAmounts() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);
		LocalDate day = LocalDate.now(clock).minusDays(10);

		MvcResult preview = mvc.perform(splitPost("/welcome/stocks/preview", l.account(), day, "UNITS",
				new Object[] { l.esun(), "4" }, new Object[] { l.land(), "3" }).session(session))
			.andReturn();
		Long id = confirm(session, preview, "/welcome/stocks/save");

		assertThat(jdbc.queryForObject("SELECT amount FROM trade WHERE id = ?", BigDecimal.class, id))
			.isEqualByComparingTo("736047");
		assertThat(allocationsOf(id)).usingElementComparator(BigDecimal::compareTo)
			.containsExactly(new BigDecimal("294419"), new BigDecimal("220814"));
		mvc.perform(get("/welcome/stocks").session(session))
			.andExpect(content().string(containsString("0050　10 張　73.5 元（玉山質押 4、土銀信貸 3、自己 3）")));
	}

	/** 確認畫面列出每個來源的張數與換算金額，自己的錢也列（設計文件 3.4）；表格也顯示換算金額。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void confirmShowsLotsAndAmounts() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);
		LocalDate day = LocalDate.now(clock).minusDays(10);

		MvcResult preview = mvc.perform(splitPost("/welcome/stocks/preview", l.account(), day, "UNITS",
				new Object[] { l.esun(), "4" }, new Object[] { l.land(), "3" }).session(session))
			.andExpect(content().string(containsString("確認要儲存這筆股票嗎？")))
			.andExpect(content().string(containsString("294,419")))
			.andExpect(content().string(containsString("3 張")))
			.andReturn();
		assertThat(rows(preview)).endsWith(new ConfirmRow("資金來源", "玉山質押 4 張（294,419 元）"),
				new ConfirmRow("", "土銀信貸 3 張（220,814 元）"), new ConfirmRow("", "自己的錢 3 張（220,814 元）"));

		// 全部自己的錢：只列自己的錢
		MvcResult own = mvc.perform(splitPost("/welcome/stocks/preview", l.account(), day, "UNITS").session(session))
			.andReturn();
		assertThat(rows(own)).endsWith(new ConfirmRow("交割戶", "玉山交割戶"),
				new ConfirmRow("資金來源", "自己的錢 10 張（736,047 元）"));
	}

	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void lotsOverTotalRejected() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);

		MvcResult preview = mvc.perform(splitPost("/welcome/stocks/preview", l.account(), LocalDate.now(clock)
			.minusDays(10), "UNITS", new Object[] { l.esun(), "6" }, new Object[] { l.land(), "5" }).session(session))
			.andExpect(content().string(containsString("還有欄位沒填好，不能儲存")))
			.andReturn();
		assertThat(errors(preview)).containsExactly("借款的張數加起來超過這筆的張數（10 張）");
		assertThat(preview.getModelAndView().getModel().get("formToken")).isNull();
	}

	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void duplicateLoanRejected() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);

		MvcResult preview = mvc.perform(splitPost("/welcome/stocks/preview", l.account(), LocalDate.now(clock)
			.minusDays(10), "UNITS", new Object[] { l.esun(), "4" }, new Object[] { l.esun(), "3" }).session(session))
			.andReturn();
		assertThat(errors(preview)).containsExactly("同一筆借款不能出現兩列");
	}

	/** 有零股（1,500 股）還填張數 → 請改填金額；張數不是正整數 → 張數要填正整數。都不存。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void oddLotUnitsRejected() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);
		LocalDate day = LocalDate.now(clock).minusDays(10);

		MvcResult odd = mvc.perform(stockPost("/welcome/stocks/preview", "BUY", "0050", day, "1500", "73.5", l.account(),
				l.esun()).session(session))
			.andReturn();
		assertThat(errors(odd)).containsExactly("這筆有零股，請改填金額");

		MvcResult half = mvc.perform(splitPost("/welcome/stocks/preview", l.account(), day, "UNITS",
				new Object[] { l.esun(), "1.5" }).session(session))
			.andReturn();
		assertThat(errors(half)).containsExactly("張數要填正整數");
		assertThat(tradeCount()).isZero();
	}

	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void amountOverCostRejected() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);

		MvcResult preview = mvc.perform(splitPost("/welcome/stocks/preview", l.account(), LocalDate.now(clock)
			.minusDays(10), "AMOUNT", new Object[] { l.esun(), "500000" }, new Object[] { l.land(), "300000" })
			.session(session))
			.andReturn();
		assertThat(errors(preview)).containsExactly("借款出的錢加起來超過這筆的總成本（736,047 元）");
	}

	/** 用張數存的，修改時換回張數（4、3）顯示填張數；只改日期存檔，金額不變。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editUnitsRoundTrip() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);
		LocalDate day = LocalDate.now(clock).minusDays(10);
		Long id = confirm(session, mvc.perform(splitPost("/welcome/stocks/preview", l.account(), day, "UNITS",
				new Object[] { l.esun(), "4" }, new Object[] { l.land(), "3" }).session(session)).andReturn(),
				"/welcome/stocks/save");

		mvc.perform(post("/welcome/stocks/" + id + "/edit").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks/" + id + "/edit"));
		MvcResult page = mvc.perform(get("/welcome/stocks/" + id + "/edit").session(session))
			.andExpect(content().string(containsString("改填金額")))
			.andExpect(content().string(containsString("294,419")))
			.andReturn();
		assertThat(page.getModelAndView().getModel().get("sources")).isEqualTo(new StockSourceInput(
				StockSourceInput.Mode.UNITS,
				List.of(new StockSourceInput.Row(l.esun(), "4"), new StockSourceInput.Row(l.land(), "3"))));

		confirm(session, mvc.perform(splitPost("/welcome/stocks/" + id + "/preview", l.account(), day.plusDays(1),
				"UNITS", new Object[] { l.esun(), "4" }, new Object[] { l.land(), "3" }).param("editing", id.toString())
			.session(session)).andReturn(), "/welcome/stocks/" + id + "/save");
		assertThat(allocationsOf(id)).usingElementComparator(BigDecimal::compareTo)
			.containsExactly(new BigDecimal("294419"), new BigDecimal("220814"));
		assertThat(jdbc.queryForObject("SELECT trade_date FROM trade WHERE id = ?", LocalDate.class, id))
			.isEqualTo(day.plusDays(1));
	}

	/**
	 * 「＋ 加一筆借款」「拿掉」「改填金額／改填張數」都是伺服器端改列：打的字（代號、股數、張數）都留著；
	 * 張數 ↔ 金額互相換算，換不回整數張時維持填金額並提示。
	 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void addAndRemoveRowKeepTyping() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);
		LocalDate day = LocalDate.now(clock).minusDays(10);

		mvc.perform(get("/welcome/stocks").session(session))
			.andExpect(content().string(containsString("＋ 加一筆借款")))
			.andExpect(content().string(containsString("全部是自己的錢就不用加。借款那幾張填張數，系統照「張數 ÷ 總張數 × 總成本」換算成金額，剩下的算自己的錢。")))
			.andExpect(content().string(not(containsString("formaction=\"/welcome/stocks/sources/mode\""))));

		// 加一列：預設第一筆借款
		mvc.perform(splitPost("/welcome/stocks/sources/add", l.account(), day, "UNITS").session(session))
			.andExpect(redirectedUrl("/welcome/stocks"));
		assertThat(sourcesOnPage(session)).isEqualTo(units(new StockSourceInput.Row(l.esun(), "")));

		// 再加一列：第一列打了 4，新的一列預設還沒用到的土銀
		mvc.perform(splitPost("/welcome/stocks/sources/add", l.account(), day, "UNITS", new Object[] { l.esun(), "4" })
			.session(session))
			.andExpect(redirectedUrl("/welcome/stocks"));
		assertThat(sourcesOnPage(session)).isEqualTo(units(new StockSourceInput.Row(l.esun(), "4"),
				new StockSourceInput.Row(l.land(), "")));

		// 改填金額：張數換算成金額
		mvc.perform(splitPost("/welcome/stocks/sources/mode", l.account(), day, "UNITS", new Object[] { l.esun(), "4" },
				new Object[] { l.land(), "3" }).session(session))
			.andExpect(redirectedUrl("/welcome/stocks"));
		assertThat(sourcesOnPage(session)).isEqualTo(new StockSourceInput(StockSourceInput.Mode.AMOUNT,
				List.of(new StockSourceInput.Row(l.esun(), "294419"), new StockSourceInput.Row(l.land(), "220814"))));

		// 改填張數：換得回來
		mvc.perform(splitPost("/welcome/stocks/sources/mode", l.account(), day, "AMOUNT",
				new Object[] { l.esun(), "294419" }, new Object[] { l.land(), "220814" }).session(session))
			.andExpect(redirectedUrl("/welcome/stocks"));
		assertThat(sourcesOnPage(session)).isEqualTo(units(new StockSourceInput.Row(l.esun(), "4"),
				new StockSourceInput.Row(l.land(), "3")));

		// 300,000 換不回整數張：維持填金額、提示
		mvc.perform(splitPost("/welcome/stocks/sources/mode", l.account(), day, "AMOUNT",
				new Object[] { l.esun(), "300000" }).session(session))
			.andExpect(redirectedUrl("/welcome/stocks"))
			.andExpect(flash().attribute("notice", "這些金額換不回整數張，請繼續填金額"));
		assertThat(sourcesOnPage(session)).isEqualTo(new StockSourceInput(StockSourceInput.Mode.AMOUNT,
				List.of(new StockSourceInput.Row(l.esun(), "300000"))));

		// 拿掉第 0 列：剩下土銀那一列；其他欄位都還在
		mvc.perform(splitPost("/welcome/stocks/sources/remove/0", l.account(), day, "UNITS",
				new Object[] { l.esun(), "4" }, new Object[] { l.land(), "3" }).session(session))
			.andExpect(redirectedUrl("/welcome/stocks"));
		assertThat(sourcesOnPage(session)).isEqualTo(units(new StockSourceInput.Row(l.land(), "3")));
		mvc.perform(get("/welcome/stocks").session(session))
			.andExpect(content().string(containsString("value=\"0050\"")))
			.andExpect(content().string(containsString("value=\"10000\"")))
			.andExpect(content().string(containsString("name=\"sources[0].value\" value=\"3\"")))
			.andExpect(content().string(containsString("7 張")));
		assertThat(tradeCount()).isZero();
	}

	/**
	 * 全部借款（各 5 張，368,023.5 → 368,024 × 2 多 1 元）：最後一列「有填的」借款吸收差額，後面空白的列不換算、不顯示金額。
	 * 改填金額時空白列照樣留空白。
	 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void blankRowDoesNotAbsorbDifference() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);
		LocalDate day = LocalDate.now(clock).minusDays(10);

		mvc.perform(splitPost("/welcome/stocks/go", l.account(), day, "UNITS", new Object[] { l.esun(), "5" },
				new Object[] { l.land(), "5" }, new Object[] { l.esun(), "" }).param("to", "stocks").session(session))
			.andExpect(redirectedUrl("/welcome/stocks"));
		StockSourceTable table = (StockSourceTable) mvc.perform(get("/welcome/stocks").session(session))
			.andReturn()
			.getModelAndView()
			.getModel()
			.get("sourceTable");
		assertThat(table.rows()).extracting(StockSourceTable.Row::amount).containsExactly("368,024", "368,023", "");
		assertThat(table.ownAmount()).isEqualTo("0");
		assertThat(table.ownUnits()).isEqualTo("0 張");

		mvc.perform(splitPost("/welcome/stocks/sources/mode", l.account(), day, "UNITS", new Object[] { l.esun(), "5" },
				new Object[] { l.land(), "5" }, new Object[] { l.esun(), "" }).session(session))
			.andExpect(redirectedUrl("/welcome/stocks"));
		assertThat(sourcesOnPage(session)).isEqualTo(new StockSourceInput(StockSourceInput.Mode.AMOUNT,
				List.of(new StockSourceInput.Row(l.esun(), "368024"), new StockSourceInput.Row(l.land(), "368023"),
						new StockSourceInput.Row(l.esun(), ""))));
	}

	/** 填金額、一列有填一列空白：改填張數時空白列不看，有填的換得回整數張就換，不提示換不回來。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void amountToLotsIgnoresBlankRow() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);

		mvc.perform(splitPost("/welcome/stocks/sources/mode", l.account(), LocalDate.now(clock).minusDays(10), "AMOUNT",
				new Object[] { l.esun(), "294419" }, new Object[] { l.land(), "" }).session(session))
			.andExpect(redirectedUrl("/welcome/stocks"))
			.andExpect(flash().attributeCount(0));
		assertThat(sourcesOnPage(session)).isEqualTo(units(new StockSourceInput.Row(l.esun(), "4"),
				new StockSourceInput.Row(l.land(), "")));
	}

	/** 填金額時 0 或負數：擋下來（不會顯示「玉山質押（0 元）」再被悄悄略過）。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void zeroOrNegativeAmountRejected() throws Exception {
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);
		LocalDate day = LocalDate.now(clock).minusDays(10);

		for (String amount : List.of("0", "-5")) {
			MvcResult preview = mvc.perform(splitPost("/welcome/stocks/preview", l.account(), day, "AMOUNT",
					new Object[] { l.esun(), amount }).session(session))
				.andReturn();
			assertThat(errors(preview)).as(amount).containsExactly("借款的金額要大於 0");
		}
		assertThat(tradeCount()).isZero();
	}

	/** 表格裡放別人的借款 id → 404、不存；已作廢的借款 → TradeService 原本的「已經作廢」訊息。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void othersOrVoidedLoanInSourcesRejected() throws Exception {
		Long dan = dan();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '別人的戶', 'SETTLEMENT', '元大證券', NOW())", dan);
		Long danAccount = jdbc.queryForObject("SELECT id FROM cash_account WHERE name = '別人的戶'", Long.class);
		LocalDate today = LocalDate.now(clock);
		jdbc.update("INSERT INTO loan (user_id, name, loan_type, repayment_method, bank, original_amount, origin, "
				+ "start_date, start_principal, pay_account_id, pay_day, posting_from, created_at) VALUES (?, '別人的質押', "
				+ "'PLEDGE', 'INTEREST_ONLY', '銀行', 1000000, 'EXISTING', ?, 1000000, ?, 25, ?, NOW())", dan,
				today.minusDays(60), danAccount, today.minusDays(60).withDayOfMonth(1));
		Long othersLoan = jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
		MockHttpSession session = new MockHttpSession();
		TwoLoans l = twoLoans(session);
		LocalDate day = today.minusDays(10);

		mvc.perform(splitPost("/welcome/stocks/preview", l.account(), day, "UNITS", new Object[] { othersLoan, "4" })
			.session(session))
			.andExpect(status().isNotFound());
		mvc.perform(splitPost("/welcome/stocks/preview", l.account(), day, "AMOUNT",
				new Object[] { othersLoan, "300000" }).session(session))
			.andExpect(status().isNotFound());

		jdbc.update("UPDATE loan SET voided = TRUE WHERE id = ?", l.land());
		MvcResult voided = mvc.perform(splitPost("/welcome/stocks/preview", l.account(), day, "UNITS",
				new Object[] { l.land(), "3" }).session(session))
			.andReturn();
		assertThat(errors(voided)).containsExactly("「土銀信貸」已經作廢");
		assertThat(tradeCount()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_allocation", Integer.class)).isZero();
	}

	private StockSourceInput sourcesOnPage(MockHttpSession session) throws Exception {
		return (StockSourceInput) mvc.perform(get("/welcome/stocks").session(session))
			.andExpect(status().isOk())
			.andReturn()
			.getModelAndView()
			.getModel()
			.get("sources");
	}

	private static StockSourceInput units(StockSourceInput.Row... rows) {
		return new StockSourceInput(StockSourceInput.Mode.UNITS, List.of(rows));
	}

	/** 股票頁從不是交割戶的帳戶買的：引導的修改頁只顯示說明；只改日期存檔，帳戶不會被換成第一個交割戶。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editKeepsNonSettlementAccount() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long settlement = onboardingService.addAccount(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT,
				"玉山證券"), null).getId();
		Long bank = onboardingService.addAccount(new CashAccountForm("台銀活存", CashAccountType.BANK_DEMAND, "台銀"),
				null).getId();
		LocalDate today = LocalDate.now(clock);
		addStocks();
		Long id = trades.create(new TradeForm(TradeType.BUY, today.minusDays(10), "0050", bank, 1000L,
				new BigDecimal("73.5"), null, null, null, List.of())).getId();

		mvc.perform(get("/welcome/stocks/" + id + "/edit").session(session))
			.andExpect(content().string(containsString("記在「台銀活存」帳戶，要改請到股票頁")));

		String token = (String) mvc.perform(stockPost("/welcome/stocks/" + id + "/preview", "BUY", "0050",
				today.minusDays(9), "1000", "73.5", settlement, null).session(session))
			.andExpect(content().string(containsString("確認要儲存這筆修改嗎？")))
			.andReturn()
			.getModelAndView()
			.getModel()
			.get("formToken");
		mvc.perform(post("/welcome/stocks/" + id + "/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"));

		assertThat(jdbc.queryForObject("SELECT account_id FROM trade WHERE id = ?", Long.class, id)).isEqualTo(bank);
		assertThat(jdbc.queryForObject("SELECT trade_date FROM trade WHERE id = ?", LocalDate.class, id))
			.isEqualTo(today.minusDays(9));
	}

	/** 新股票打了「0056」→ 按某筆已儲存的「修改」→ 取消修改 → 欄位回到「0056」。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void editKeepsNewDraft() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		addStocks();
		Long id = saveStock(session, "OPENING", "0050", LocalDate.now(clock).minusDays(20), "5000", "73.5", account,
				null);

		mvc.perform(post("/welcome/stocks/" + id + "/edit").param("type", "BUY").param("stockCode", "0056")
			.session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks/" + id + "/edit"));
		mvc.perform(get("/welcome/stocks/" + id + "/edit").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"0050\"")))
			.andExpect(content().string(containsString(EDIT_NOTE)))
			.andExpect(content().string(containsString("儲存修改")));
		mvc.perform(post("/welcome/stocks/cancel-edit").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"));
		mvc.perform(get("/welcome/stocks").session(session))
			.andExpect(content().string(containsString("value=\"0056\"")))
			.andExpect(content().string(containsString("儲存這筆股票")));
	}

	/** 有沒作廢的質押借款：「股票記完了，下一步」記到擔保那一步，轉到擔保頁（Task 7 才有頁面）。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void nextGoesToCollateralWhenPledgeExists() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long account = saveAccount(session, "玉山交割戶", "");
		LocalDate today = LocalDate.now(clock);
		saveLoan(session, account, today.minusDays(60), "500000", today.plusDays(300));
		setStep("STOCKS");

		mvc.perform(post("/welcome/stocks/go").param("to", "next").param("stockCode", "0050").session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/collateral"));
		assertThat(step()).isEqualTo("COLLATERAL");
	}

	/** 沒有質押借款：直接到完成頁，進度不會變成完成（要按「到總覽看看」才算）。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void nextGoesToDoneWithoutPledge() throws Exception {
		MockHttpSession session = new MockHttpSession();
		setStep("STOCKS");

		mvc.perform(post("/welcome/stocks/go").param("to", "next").param("stockCode", "0050").session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/done"));
		assertThat(step()).isEqualTo("STOCKS");
		mvc.perform(get("/welcome/stocks").session(session))
			.andExpect(content().string(containsString("value=\"0050\"")));
	}

	// ---------- 擔保這一步與完成頁（Task 7） ----------

	private static final String COLLATERAL_HINT = "不知道就用預設值，之後可以在資金來源頁改。";

	/** 進擔保那一步轉到第一筆還沒設定擔保的質押；畫面照樣稿：持股清單（預設全部勾、全部股數）、門檻 150／130。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void collateralEntryAndPage() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Pledge p = pledgeWithStocks(session);

		mvc.perform(get("/welcome/collateral").session(session))
			.andExpect(redirectedUrl("/welcome/collateral/" + p.loan()));
		assertThat(step()).isEqualTo("COLLATERAL");
		mvc.perform(get("/welcome/collateral/" + p.loan()).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("「玉山質押」押了哪些股票？")))
			.andExpect(content().string(containsString("持有 5,000")))
			.andExpect(content().string(containsString("持有 1,000")))
			.andExpect(content().string(containsString("維持率提醒線（%）")))
			.andExpect(content().string(containsString("value=\"150\"")))
			.andExpect(content().string(containsString("value=\"130\"")))
			.andExpect(content().string(containsString(COLLATERAL_HINT)))
			.andExpect(content().string(containsString("儲存擔保股票")))
			.andExpect(content().string(containsString("之後再設定")))
			.andExpect(content().string(containsString("下一步：完成 →")))
			.andExpect(content().string(matchesPattern("(?s).*value=\"" + p.stock0050() + "\"[^>]*checked.*")));
	}

	/** 預覽列出要存的內容、欄位鎖住；確認儲存才存，存好顯示「已儲存」；預設門檻不寫 RateSetting。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void collateralPreviewThenSave() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Pledge p = pledgeWithStocks(session);

		MvcResult preview = mvc.perform(collateralPost(p.loan(), "preview", p, "3000", false).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("確認要儲存這筆擔保股票嗎？")))
			.andExpect(content().string(containsString("<fieldset class=\"step-fields\" disabled")))
			.andReturn();
		assertThat(rows(preview)).containsExactly(new ConfirmRow("0050 元大台灣50", "3,000 股"),
				new ConfirmRow("提醒線（%）", "150"), new ConfirmRow("追繳線（%）", "130"));
		assertThat(collateralCount()).isZero();
		String token = (String) preview.getModelAndView().getModel().get("formToken");

		mvc.perform(post("/welcome/collateral/" + p.loan() + "/save").param("formToken", token).session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/collateral/" + p.loan()));
		mvc.perform(post("/welcome/collateral/" + p.loan() + "/save").param("formToken", token).session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/collateral/" + p.loan()));

		assertThat(jdbc.queryForList("SELECT shares FROM pledge_collateral WHERE loan_id = ?", Long.class, p.loan()))
			.containsExactly(3000L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_setting WHERE loan_id = ? AND kind LIKE "
				+ "'MAINTENANCE%'", Integer.class, p.loan())).isZero();
		mvc.perform(get("/welcome/collateral/" + p.loan()).session(session))
			.andExpect(content().string(containsString("已儲存")))
			.andExpect(content().string(containsString("0050 元大台灣50　3,000 股")));
	}

	/** 擔保股數超過持股：不能存，訊息和資金來源頁一樣；打的字還在。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void collateralOverHoldingsRejected() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Pledge p = pledgeWithStocks(session);

		MvcResult preview = mvc.perform(collateralPost(p.loan(), "preview", p, "5001", false).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("還有欄位沒填好，不能儲存")))
			.andExpect(content().string(containsString("value=\"5001\"")))
			.andReturn();
		MvcResult funding = mvc.perform(post("/funding/" + p.loan() + "/collateral").param("stockCode", "0050")
			.param("shares", "5001").with(csrf())).andReturn();

		assertThat(errors(preview)).containsExactly((String) funding.getFlashMap().get("message"));
		assertThat(errors(preview)).containsExactly("擔保股數不能超過持有股數（5,000 股）");
		assertThat(collateralCount()).isZero();
	}

	/** 別人的借款、自己的信貸都沒有擔保頁（404），也不能預覽、存檔、留草稿。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void collateralOfOthersLoanIsNotFound() throws Exception {
		if (!users.existsByUsername("ob-dan")) {
			users.save(AppUser.create("ob-dan", encoder.encode("password123"), "ob-dan", Role.USER,
					LocalDateTime.now(clock)));
		}
		Long dan = users.findByUsername("ob-dan").orElseThrow().getId();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '別人的戶', 'SETTLEMENT', '元大證券', NOW())", dan);
		Long danAccount = jdbc.queryForObject("SELECT id FROM cash_account WHERE name = '別人的戶'", Long.class);
		LocalDate today = LocalDate.now(clock);
		jdbc.update("INSERT INTO loan (user_id, name, loan_type, repayment_method, bank, original_amount, origin, "
				+ "start_date, start_principal, pay_account_id, pay_day, posting_from, created_at) VALUES (?, '別人的質押', "
				+ "'PLEDGE', 'INTEREST_ONLY', '銀行', 1000, 'EXISTING', ?, 1000, ?, 25, ?, NOW())", dan, today,
				danAccount, today.withDayOfMonth(1));
		Long other = jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);

		mvc.perform(get("/welcome/collateral/" + other)).andExpect(status().isNotFound());
		mvc.perform(post("/welcome/collateral/" + other + "/preview").param("warnPercent", "150").with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/welcome/collateral/" + other + "/save").param("formToken", "x").with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/welcome/collateral/" + other + "/go").param("to", "next").with(csrf()))
			.andExpect(status().isNotFound());
		assertThat(collateralCount()).isZero();
	}

	/**
	 * 兩筆質押：下一步、之後再設定 → 下一筆，最後一筆 → 完成頁；上一步 → 上一筆，第一筆 → 股票。
	 * 完成頁的上一步是最後一個擔保頁。打到一半的內容存成草稿，回來還在。
	 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void collateralNavigationAndDraft() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Pledge p = pledgeWithStocks(session);
		LocalDate today = LocalDate.now(clock);
		Long second = saveLoan(session, p.account(), today.minusDays(30), "200000", today.plusDays(200));
		String first = "/welcome/collateral/" + p.loan();

		mvc.perform(collateralPost(p.loan(), "go", p, "1234", false).param("to", "next").session(session))
			.andExpect(redirectedUrl("/welcome/collateral/" + second));
		mvc.perform(get(first).session(session))
			.andExpect(content().string(containsString("value=\"1234\"")))
			.andExpect(content().string(containsString("下一步：下一筆質押 →")));
		mvc.perform(get("/welcome/collateral/" + second).session(session))
			.andExpect(content().string(containsString("「玉山質押」押了哪些股票？")))
			.andExpect(content().string(containsString("下一步：完成 →")));
		mvc.perform(post("/welcome/collateral/" + second + "/go").param("to", "later").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/done"));
		mvc.perform(post("/welcome/collateral/" + second + "/go").param("to", "back").session(session).with(csrf()))
			.andExpect(redirectedUrl(first));
		mvc.perform(post(first + "/go").param("to", "back").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"));
		mvc.perform(post(first + "/go").param("to", "https://evil.example").session(session).with(csrf()))
			.andExpect(redirectedUrl(first));
		mvc.perform(get("/welcome/done").session(session))
			.andExpect(content().string(containsString("href=\"/welcome/collateral/" + second + "\"")));
		assertThat(collateralCount()).isZero();
	}

	/** 完成頁：帳戶、借款、股票的數字；沒設定擔保時步驟列的「4 擔保」是略過；「到總覽看看」標成完成回總覽。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void donePageCountsAndFinishes() throws Exception {
		MockHttpSession session = new MockHttpSession();
		pledgeWithStocks(session);
		saveAccount(session, "台銀活存", "");

		MvcResult done = mvc.perform(get("/welcome/done").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("完成了")))
			.andExpect(content().string(matchesPattern("(?s).*class=\"done skipped\">4 擔保.*")))
			.andReturn();
		assertThat(done.getModelAndView().getModel().get("summary"))
			.isEqualTo(new com.sideproject.stockledger.dto.OnboardingSummary(2, 1, 2));

		mvc.perform(post("/welcome/finish").with(csrf())).andExpect(redirectedUrl("/"));
		assertThat(step()).isEqualTo("DONE");
		mvc.perform(get("/welcome/collateral")).andExpect(redirectedUrl("/"));
	}

	// ---------- 全分支審查後補的（final review） ----------

	private static final String ALREADY_SUBMITTED = OnboardingController.ALREADY_SUBMITTED;

	/** 第二筆質押押同一批股票：第一筆照預設全押，第二筆的預設是 0、不勾，照預設按儲存可以過。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void secondPledgeDefaultsToAvailableShares() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Pledge p = pledgeWithStocks(session);
		LocalDate today = LocalDate.now(clock);
		Long second = saveLoan(session, p.account(), today.minusDays(30), "200000", today.plusDays(200));
		mvc.perform(get("/welcome/collateral/" + p.loan()).session(session))
			.andExpect(content().string(matchesPattern("(?s).*value=\"" + p.stock0050() + "\"[^>]*checked.*")))
			.andExpect(content().string(containsString("value=\"5000\"")));
		String token = (String) mvc.perform(collateralPost(p.loan(), "preview", p, "5000", true).session(session))
			.andReturn().getModelAndView().getModel().get("formToken");
		mvc.perform(post("/welcome/collateral/" + p.loan() + "/save").param("formToken", token).session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/collateral/" + p.loan()));

		mvc.perform(get("/welcome/collateral/" + second).session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(not(matchesPattern("(?s).*value=\"" + p.stock0050() + "\"[^>]*checked.*"))))
			.andExpect(content().string(not(matchesPattern("(?s).*value=\"" + p.stock0056() + "\"[^>]*checked.*"))))
			.andExpect(content().string(not(containsString("value=\"5000\""))));
		MvcResult preview = mvc.perform(post("/welcome/collateral/" + second + "/preview")
			.param("collateral[0].shares", "0")
			.param("collateral[1].shares", "0")
			.param("warnPercent", "150")
			.param("callPercent", "130")
			.session(session)
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("確認要儲存這筆擔保股票嗎？")))
			.andReturn();
		assertThat(errors(preview)).isEmpty();
	}

	/** 別人的借款、股票：修改、預覽、存檔、確定修改都是 404，什麼都不改。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void othersLoanAndStockAreNotFound() throws Exception {
		Long dan = dan();
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, '別人的戶', 'SETTLEMENT', '元大證券', NOW())", dan);
		Long danAccount = jdbc.queryForObject("SELECT id FROM cash_account WHERE name = '別人的戶'", Long.class);
		LocalDate today = LocalDate.now(clock);
		jdbc.update("INSERT INTO loan (user_id, name, loan_type, repayment_method, bank, original_amount, origin, "
				+ "start_date, start_principal, pay_account_id, pay_day, posting_from, created_at) VALUES (?, '別人的質押', "
				+ "'PLEDGE', 'INTEREST_ONLY', '銀行', 1000, 'EXISTING', ?, 1000, ?, 25, ?, NOW())", dan, today,
				danAccount, today.withDayOfMonth(1));
		Long loan = jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
		addStocks();
		jdbc.update("INSERT INTO trade (user_id, type, trade_date, stock_id, shares, amount, created_at) "
				+ "SELECT ?, 'OPENING', ?, id, 1000, 50000, NOW() FROM stock WHERE code = '0050'", dan, today);
		Long trade = jdbc.queryForObject("SELECT MAX(id) FROM trade", Long.class);
		MockHttpSession session = new MockHttpSession();
		Long mine = saveAccount(session, "玉山交割戶", "");

		mvc.perform(get("/welcome/loans/" + loan + "/edit").session(session)).andExpect(status().isNotFound());
		mvc.perform(post("/welcome/loans/" + loan + "/edit").session(session).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(loanPost("/welcome/loans/" + loan + "/preview", mine, today, "1", today.plusDays(30))
			.session(session)).andExpect(status().isNotFound());
		mvc.perform(post("/welcome/loans/" + loan + "/save").param("formToken", "x").session(session).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(post("/welcome/loans/" + loan + "/confirm-shortfall").param("formToken", "x").session(session)
			.with(csrf())).andExpect(status().isNotFound());
		mvc.perform(get("/welcome/stocks/" + trade + "/edit").session(session)).andExpect(status().isNotFound());
		mvc.perform(post("/welcome/stocks/" + trade + "/edit").session(session).with(csrf()))
			.andExpect(status().isNotFound());
		mvc.perform(stockPost("/welcome/stocks/" + trade + "/preview", "OPENING", "0050", today, "1", "1", mine, null)
			.session(session)).andExpect(status().isNotFound());
		mvc.perform(post("/welcome/stocks/" + trade + "/save").param("formToken", "x").session(session).with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(jdbc.queryForObject("SELECT start_principal FROM loan WHERE id = ?", BigDecimal.class, loan))
			.isEqualByComparingTo("1000");
		assertThat(jdbc.queryForObject("SELECT shares FROM trade WHERE id = ?", Long.class, trade)).isEqualTo(1000L);
	}

	/** 確認儲存的代號已經用過或失效（按兩次、上一頁再按）：不存，回清單並說明，不是悄悄轉回去。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void usedTokenSaysAlreadySubmitted() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Pledge p = pledgeWithStocks(session);
		Long trade = jdbc.queryForObject("SELECT MIN(id) FROM trade", Long.class);

		String token = previewToken(session, "/welcome/accounts/preview", "台銀交割戶", "");
		mvc.perform(post("/welcome/accounts/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts"));
		mvc.perform(post("/welcome/accounts/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts"))
			.andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		mvc.perform(post("/welcome/accounts/" + p.account() + "/save").param("formToken", "x").session(session)
			.with(csrf())).andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		mvc.perform(post("/welcome/loans/save").param("formToken", "x").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"))
			.andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		mvc.perform(post("/welcome/loans/" + p.loan() + "/save").param("formToken", "x").session(session)
			.with(csrf())).andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		mvc.perform(post("/welcome/loans/" + p.loan() + "/confirm-shortfall").param("formToken", "x")
			.session(session).with(csrf())).andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		mvc.perform(post("/welcome/stocks/save").param("formToken", "x").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"))
			.andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		mvc.perform(post("/welcome/stocks/" + trade + "/save").param("formToken", "x").session(session)
			.with(csrf())).andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		mvc.perform(post("/welcome/collateral/" + p.loan() + "/save").session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/collateral/" + p.loan()))
			.andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		mvc.perform(get("/welcome/stocks").session(session).flashAttr("notice", ALREADY_SUBMITTED))
			.andExpect(content().string(containsString(ALREADY_SUBMITTED)));

		assertThat(accountCount()).isEqualTo(2);
	}

	/** 確認區塊按上一步、修改、回去修改把收起來的內容拿回去當草稿時，代號一起用掉（不會越積越多，也不能再拿去存）。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void takingBackStashConsumesToken() throws Exception {
		MockHttpSession session = new MockHttpSession();
		Long saved = saveAccount(session, "玉山交割戶", "");

		String token = previewToken(session, "/welcome/accounts/preview", "台銀交割戶", "");
		mvc.perform(post("/welcome/accounts/go").param("to", "accounts").param("formToken", token).session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts"));
		assertThat(issuedTokens(session)).doesNotContain(token);
		mvc.perform(post("/welcome/accounts/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(flash().attribute("notice", ALREADY_SUBMITTED));
		mvc.perform(get("/welcome/accounts").session(session))
			.andExpect(content().string(containsString("value=\"台銀交割戶\"")));

		String again = previewToken(session, "/welcome/accounts/preview", "台銀交割戶", "");
		mvc.perform(post("/welcome/accounts/" + saved + "/edit").param("formToken", again).session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/accounts/" + saved + "/edit"));
		assertThat(issuedTokens(session)).doesNotContain(again);
		assertThat(accountCount()).isEqualTo(1);
	}

	/** 一般預覽的代號拿去「確定修改」：當作過期，什麼都不改。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void previewTokenOnShortfallUrlIsExpired() throws Exception {
		Shortfall s = loanUsedByStock();
		MockHttpSession session = new MockHttpSession();

		String token = loanToken(session, "/welcome/loans/" + s.loan() + "/preview", s.account(), s.start(), "50000",
				s.due());
		mvc.perform(post("/welcome/loans/" + s.loan() + "/confirm-shortfall").param("formToken", token)
			.session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans/" + s.loan() + "/edit"))
			.andExpect(flash().attribute("notice", "確認畫面已過期，請再按一次儲存"));

		assertThat(disbursement(s.loan())).isEqualByComparingTo("100000");
	}

	/** 要調整交易的畫面按「取消」：不存，回到修改表單，剛才改小的金額還在。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void shortfallCancelReturnsToEditForm() throws Exception {
		Shortfall s = loanUsedByStock();
		MockHttpSession session = new MockHttpSession();
		// 修改畫面的表單帶著隱藏欄位 editing（正在改哪一筆）
		String token = (String) mvc.perform(loanPost("/welcome/loans/" + s.loan() + "/preview", s.account(), s.start(),
				"50000", s.due()).param("editing", s.loan().toString()).session(session))
			.andExpect(status().isOk())
			.andReturn().getModelAndView().getModel().get("formToken");
		MvcResult asked = mvc.perform(post("/welcome/loans/" + s.loan() + "/save").param("formToken", token)
			.session(session).with(csrf()))
			.andExpect(content().string(containsString("確定修改")))
			.andReturn();
		String again = (String) asked.getModelAndView().getModel().get("formToken");

		mvc.perform(post("/welcome/loans/go").param("to", "loans").param("formToken", again).session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));
		mvc.perform(get("/welcome/loans").session(session))
			.andExpect(redirectedUrl("/welcome/loans/" + s.loan() + "/edit"));
		mvc.perform(get("/welcome/loans/" + s.loan() + "/edit").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("value=\"50000\"")))
			.andExpect(content().string(containsString("儲存修改")));

		assertThat(disbursement(s.loan())).isEqualByComparingTo("100000");
		assertThat(issuedTokens(session)).doesNotContain(again);
	}

	/** 有表單的頁面按「先離開」（go?to=home）：和沒有表單的先離開一樣，這個 session 裡 GET / 看總覽。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void goHomeLeavesForThisSession() throws Exception {
		setStep("ACCOUNTS");
		MockHttpSession session = new MockHttpSession();

		mvc.perform(post("/welcome/accounts/go").param("to", "home").param("name", "玉山").session(session)
			.with(csrf()))
			.andExpect(redirectedUrl("/"));

		mvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("淨資產")));
		assertThat(step()).isEqualTo("ACCOUNTS");
	}

	/** 同一個 session 換人登入：前一個人按過先離開，不影響下一個人，還是轉到他自己的那一步。 */
	@Test
	@WithUserDetails(value = "ob-cy", setupBefore = TestExecutionEvent.TEST_EXECUTION)
	void otherUserOnSameSessionIsStillRedirected() throws Exception {
		setStep("LOANS");
		dan();
		jdbc.update("UPDATE app_user SET onboarding_step = 'STOCKS' WHERE username = 'ob-dan'");
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/welcome/leave").session(session).with(csrf())).andExpect(redirectedUrl("/"));
		mvc.perform(get("/").session(session)).andExpect(status().isOk());

		mvc.perform(get("/").session(session).with(user(userDetails.loadUserByUsername("ob-dan"))))
			.andExpect(redirectedUrl("/welcome/stocks"));
	}

	private record Shortfall(Long account, Long loan, LocalDate start, LocalDate due) {
	}

	/** 10 萬的質押，已經用它出全額買了 0050 1,000 股（73,500 元）：改成 5 萬就不夠付。 */
	private Shortfall loanUsedByStock() {
		LocalDate today = LocalDate.now(clock);
		Long account = onboardingService.addAccount(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT,
				"玉山證券"), null).getId();
		LocalDate start = today.minusMonths(2);
		LocalDate due = today.plusDays(300);
		LoanForm form = new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, start,
				new BigDecimal("100000"), new BigDecimal("2.5"), null, account, null, null, null, due,
				RepaymentMethod.BULLET, null, null, List.of(), null, null);
		Long loan = onboardingService.addLoan(form).getId();
		addStocks();
		trades.create(new TradeForm(TradeType.BUY, today.minusMonths(1), "0050", account, 1000L, new BigDecimal("73.5"),
				null, null, null, List.of(new AllocationLine(loan, new BigDecimal("73500")))));
		return new Shortfall(account, loan, start, due);
	}

	private Long dan() {
		if (!users.existsByUsername("ob-dan")) {
			users.save(AppUser.create("ob-dan", encoder.encode("password123"), "ob-dan", Role.USER,
					LocalDateTime.now(clock)));
		}
		return users.findByUsername("ob-dan").orElseThrow().getId();
	}

	@SuppressWarnings("unchecked")
	private static java.util.Set<String> issuedTokens(MockHttpSession session) {
		Object tokens = session.getAttribute(FormTokens.class.getName());
		return tokens == null ? java.util.Set.of() : (java.util.Set<String>) tokens;
	}

	private record Pledge(Long account, Long loan, Long stock0050, Long stock0056) {
	}

	/** 交割戶、一筆質押、0050 5,000 股（目前持有）、0056 1,000 股（一次買進），都從引導存。 */
	private Pledge pledgeWithStocks(MockHttpSession session) throws Exception {
		Long account = saveAccount(session, "玉山交割戶", "");
		LocalDate today = LocalDate.now(clock);
		Long loan = saveLoan(session, account, today.minusDays(60), "500000", today.plusDays(300));
		addStocks();
		saveStock(session, "OPENING", "0050", today.minusDays(20), "5000", "73.5", account, null);
		saveStock(session, "BUY", "0056", today.minusDays(10), "1000", "35.2", account, null);
		setStep("STOCKS");
		return new Pledge(account, loan, jdbc.queryForObject("SELECT id FROM stock WHERE code = '0050'", Long.class),
				jdbc.queryForObject("SELECT id FROM stock WHERE code = '0056'", Long.class));
	}

	/** 擔保頁的表單：勾 0050（股數 shares0050），0056 沒勾（checked0056 為 true 時也勾、全部股數）；門檻用預設值。 */
	private MockHttpServletRequestBuilder collateralPost(Long loan, String action, Pledge p, String shares0050,
			boolean checked0056) {
		MockHttpServletRequestBuilder b = post("/welcome/collateral/" + loan + "/" + action)
			.param("collateral[0].stockId", p.stock0050().toString())
			.param("collateral[0].shares", shares0050)
			.param("collateral[1].shares", "1000")
			.param("warnPercent", "150")
			.param("callPercent", "130")
			.with(csrf());
		return checked0056 ? b.param("collateral[1].stockId", p.stock0056().toString()) : b;
	}

	private int collateralCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM pledge_collateral", Integer.class);
	}

	private void addStocks() {
		jdbc.update("INSERT IGNORE INTO stock (code, name, kind, dividends_per_year, updated_at) "
				+ "VALUES ('0050', '元大台灣50', 'ETF', 2, NOW())");
		jdbc.update("INSERT IGNORE INTO stock (code, name, kind, dividends_per_year, updated_at) "
				+ "VALUES ('0056', '元大高股息', 'ETF', 4, NOW())");
	}

	private Long saveStock(MockHttpSession session, String type, String code, LocalDate date, String shares,
			String price, Long account, Long loan) throws Exception {
		String token = (String) mvc.perform(stockPost("/welcome/stocks/preview", type, code, date, shares, price,
				account, loan).session(session))
			.andExpect(status().isOk())
			.andReturn()
			.getModelAndView()
			.getModel()
			.get("formToken");
		assertThat(token).isNotNull();
		mvc.perform(post("/welcome/stocks/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"));
		return jdbc.queryForObject("SELECT MAX(id) FROM trade", Long.class);
	}

	/** loan 不是 null：那筆借款出全部的張數（填張數，例如 1,000 股 → 1 張）。 */
	private MockHttpServletRequestBuilder stockPost(String url, String type, String code, LocalDate date,
			String shares, String price, Long account, Long loan) {
		MockHttpServletRequestBuilder b = post(url).param("type", type)
			.param("stockCode", code)
			.param("date", date.toString())
			.param("shares", shares)
			.param("price", price)
			.param("accountId", account.toString())
			.param("sourceMode", "UNITS")
			.with(csrf());
		return loan == null ? b
				: b.param("sources[0].loanId", loan.toString())
					.param("sources[0].value", String.valueOf(Long.parseLong(shares) / 1000));
	}

	/** 0050 10,000 股 × 73.5 一次買進，資金來源表格用 sourceMode 與 rows（每列 {借款 id, 張數或金額}）。 */
	private MockHttpServletRequestBuilder splitPost(String url, Long account, LocalDate date, String mode,
			Object[]... rows) {
		MockHttpServletRequestBuilder b = post(url).param("type", "BUY")
			.param("stockCode", "0050")
			.param("date", date.toString())
			.param("shares", "10000")
			.param("price", "73.5")
			.param("accountId", account.toString())
			.param("sourceMode", mode)
			.with(csrf());
		for (int i = 0; i < rows.length; i++) {
			b = b.param("sources[" + i + "].loanId", rows[i][0].toString())
				.param("sources[" + i + "].value", rows[i][1].toString());
		}
		return b;
	}

	/** 交割戶、玉山質押（50 萬）、土銀信貸（50 萬），都從引導存。 */
	private record TwoLoans(Long account, Long esun, Long land) {
	}

	private TwoLoans twoLoans(MockHttpSession session) throws Exception {
		Long account = saveAccount(session, "玉山交割戶", "");
		LocalDate today = LocalDate.now(clock);
		Long esun = saveLoan(session, account, today.minusDays(60), "500000", today.plusDays(300));
		String token = (String) mvc.perform(post("/welcome/loans/preview").param("name", "土銀信貸")
			.param("bank", "土地銀行")
			.param("origin", "NEW")
			.param("repaymentMethod", "BULLET")
			.param("startDate", today.minusDays(60).toString())
			.param("startPrincipal", "500000")
			.param("annualRatePercent", "2.5")
			.param("dueDate", today.plusDays(300).toString())
			.param("payAccountId", account.toString())
			.param("fee", "")
			.with(csrf())
			.session(session))
			.andReturn()
			.getModelAndView()
			.getModel()
			.get("formToken");
		mvc.perform(post("/welcome/loans/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));
		addStocks();
		return new TwoLoans(account, esun, jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class));
	}

	/** 預覽成功後按「確認儲存」，回傳交易 id。 */
	private Long confirm(MockHttpSession session, MvcResult preview, String saveUrl) throws Exception {
		String token = (String) preview.getModelAndView().getModel().get("formToken");
		assertThat(token).as("預覽應該成功，錯誤：%s", errors(preview)).isNotNull();
		mvc.perform(post(saveUrl).param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/stocks"));
		return jdbc.queryForObject("SELECT MAX(id) FROM trade", Long.class);
	}

	private List<BigDecimal> allocationsOf(Long tradeId) {
		return jdbc.queryForList("SELECT amount FROM source_allocation WHERE trade_id = ? ORDER BY id", BigDecimal.class,
				tradeId);
	}

	private int tradeCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM trade", Integer.class);
	}

	private Long saveLoan(MockHttpSession session, Long account, LocalDate start, String principal, LocalDate due)
			throws Exception {
		String token = loanToken(session, "/welcome/loans/preview", account, start, principal, due);
		mvc.perform(post("/welcome/loans/save").param("formToken", token).session(session).with(csrf()))
			.andExpect(redirectedUrl("/welcome/loans"));
		return jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
	}

	private String loanToken(MockHttpSession session, String url, Long account, LocalDate start, String principal,
			LocalDate due) throws Exception {
		String token = (String) mvc.perform(loanPost(url, account, start, principal, due).session(session))
			.andExpect(status().isOk())
			.andReturn()
			.getModelAndView()
			.getModel()
			.get("formToken");
		assertThat(token).isNotNull();
		return token;
	}

	/** 到期一次還本金＋利息的股票質押（畫面有 JavaScript 時只送這個還款方式用得到的欄位）。 */
	private MockHttpServletRequestBuilder loanPost(String url, Long account, LocalDate start, String principal,
			LocalDate due) {
		return post(url).param("name", "玉山質押")
			.param("bank", "玉山銀行")
			.param("origin", "NEW")
			.param("repaymentMethod", "BULLET")
			.param("pledge", "true")
			.param("startDate", start.toString())
			.param("startPrincipal", principal)
			.param("annualRatePercent", "2.5")
			.param("dueDate", due.toString())
			.param("payAccountId", account.toString())
			.param("fee", "")
			.with(csrf());
	}

	private int loanCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM loan", Integer.class);
	}

	private BigDecimal disbursement(Long loan) {
		return jdbc.queryForObject("SELECT amount FROM cash_movement WHERE loan_id = ? AND type = 'LOAN_DISBURSEMENT' "
				+ "AND voided = FALSE", BigDecimal.class, loan);
	}

	private Long saveAccount(MockHttpSession session, String name, String amount) throws Exception {
		String token = previewToken(session, "/welcome/accounts/preview", name, amount);
		mvc.perform(accountPost("/welcome/accounts/save", name, amount).param("formToken", token).session(session))
			.andExpect(redirectedUrl("/welcome/accounts"));
		return jdbc.queryForObject("SELECT id FROM cash_account WHERE name = ?", Long.class, name);
	}

	private String previewToken(MockHttpSession session, String url, String name, String amount) throws Exception {
		return (String) mvc.perform(accountPost(url, name, amount).session(session))
			.andExpect(status().isOk())
			.andReturn()
			.getModelAndView()
			.getModel()
			.get("formToken");
	}

	private MockHttpServletRequestBuilder accountPost(String url, String name, String amount) {
		return post(url).param("name", name)
			.param("type", "SETTLEMENT")
			.param("institution", "玉山證券")
			.param("amount", amount)
			.param("date", LocalDate.now(clock).toString())
			.with(csrf());
	}

	@SuppressWarnings("unchecked")
	private static List<ConfirmRow> rows(MvcResult result) {
		return (List<ConfirmRow>) result.getModelAndView().getModel().get("rows");
	}

	@SuppressWarnings("unchecked")
	private static List<String> errors(MvcResult result) {
		return (List<String>) result.getModelAndView().getModel().get("errors");
	}

	private int accountCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM cash_account", Integer.class);
	}

	private void setStep(String step) {
		jdbc.update("UPDATE app_user SET onboarding_step = ? WHERE username = 'ob-cy'", step);
	}

	private String step() {
		return jdbc.queryForObject("SELECT onboarding_step FROM app_user WHERE username = 'ob-cy'", String.class);
	}

}
