package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.CashMovementForm;
import com.sideproject.stockledger.dto.CollateralForm;
import com.sideproject.stockledger.dto.CollateralPage;
import com.sideproject.stockledger.dto.CollateralPick;
import com.sideproject.stockledger.dto.ConfirmRow;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.OnboardingSummary;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.SavedRow;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.OnboardingStep;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 第一次使用引導的進度與轉址（設計文件 2026-10-25-onboarding 5、6）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class OnboardingServiceTest {

	@Autowired
	private OnboardingService service;

	@Autowired
	private CashAccountService accounts;

	@Autowired
	private TradeService trades;

	@Autowired
	private CashMovementService movements;

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
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM rate_setting WHERE user_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		jdbc.update("DELETE FROM stock_price");
		jdbc.update("DELETE FROM dividend");
		jdbc.update("DELETE FROM stock");
		createIfMissing("ob-amy");
		createIfMissing("ob-ben");
		jdbc.update("UPDATE app_user SET onboarding_step = 'WELCOME' WHERE username IN ('ob-amy', 'ob-ben')");
		loginAs("ob-amy");
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void newUserStartsAtWelcome() {
		assertThat(service.currentStep()).isEqualTo(OnboardingStep.WELCOME);
		assertThat(service.done()).isFalse();
	}

	/** 進度只往前：回到前面的頁面不會把進度改小。 */
	@Test
	void reachOnlyMovesForward() {
		service.reach(OnboardingStep.LOANS);
		service.reach(OnboardingStep.ACCOUNTS);

		assertThat(service.currentStep()).isEqualTo(OnboardingStep.LOANS);
	}

	@Test
	void finishMarksDone() {
		service.finish();

		assertThat(service.done()).isTrue();
		assertThat(service.currentStep()).isEqualTo(OnboardingStep.DONE);
	}

	@Test
	void progressIsPerUser() {
		service.reach(OnboardingStep.STOCKS);

		loginAs("ob-ben");

		assertThat(service.currentStep()).isEqualTo(OnboardingStep.WELCOME);
	}

	@Test
	void urlOfEachStep() {
		assertThat(service.urlOf(OnboardingStep.WELCOME)).isEqualTo("/welcome");
		assertThat(service.urlOf(OnboardingStep.ACCOUNTS)).isEqualTo("/welcome/accounts");
		assertThat(service.urlOf(OnboardingStep.LOANS)).isEqualTo("/welcome/loans");
		assertThat(service.urlOf(OnboardingStep.STOCKS)).isEqualTo("/welcome/stocks");
		assertThat(service.urlOf(OnboardingStep.COLLATERAL)).isEqualTo("/welcome/collateral");
		assertThat(service.urlOf(OnboardingStep.DONE)).isEqualTo("/");
	}

	@Test
	void summaryOfNewUserIsZero() {
		assertThat(service.summary()).isEqualTo(new OnboardingSummary(0, 0, 0));
	}

	/** 帳戶數、借款數、有持股的檔數；別人的資料不算。 */
	@Test
	void summaryCounts() {
		Long settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		accounts.create(new CashAccountForm("台銀活存", CashAccountType.BANK_DEMAND, "台銀"));
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0056', '元大高股息', "
				+ "'ETF', 4, NOW())");
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0050', '元大台灣50', "
				+ "'ETF', 2, NOW())");
		LocalDate today = LocalDate.now(clock);
		trades.create(buy("0056", today, settlement));
		trades.create(buy("0056", today, settlement));
		trades.create(buy("0050", today, settlement));
		loginAs("ob-ben");
		accounts.create(new CashAccountForm("別人的戶", CashAccountType.SETTLEMENT, "元大證券"));

		loginAs("ob-amy");
		assertThat(service.summary()).isEqualTo(new OnboardingSummary(2, 0, 2));
	}

	/** 確認區塊的欄位：照樣稿的欄位名稱，沒填的是空字串（畫面顯示「（沒填）」）。 */
	@Test
	void rowsForAccount() {
		LocalDate today = LocalDate.now(clock);

		assertThat(service.rowsFor(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券"),
				new OpeningForm(today, new BigDecimal("50000"))))
			.containsExactly(new ConfirmRow("名稱", "玉山交割戶"), new ConfirmRow("類型", "證券交割戶"),
					new ConfirmRow("銀行或券商", "玉山證券"), new ConfirmRow("現在的餘額", "50,000"),
					new ConfirmRow("餘額日期", today.toString()));
		assertThat(service.rowsFor(new CashAccountForm("台銀活存", CashAccountType.BANK_DEMAND, ""), null))
			.contains(new ConfirmRow("銀行或券商", ""), new ConfirmRow("現在的餘額", ""), new ConfirmRow("餘額日期", ""));
	}

	/** 還款方式顯示中文、往來帳戶顯示名稱、質押顯示是／否；只列這個還款方式用得到的欄位。 */
	@Test
	void rowsForLoan() {
		Long account = accounts.create(new CashAccountForm("台銀活存", CashAccountType.BANK_DEMAND, "台銀")).getId();
		LocalDate start = LocalDate.of(2026, 7, 28);

		List<ConfirmRow> monthly = service.rowsFor(new LoanForm("土銀信貸", LoanType.CREDIT, "土地銀行", LoanOrigin.NEW,
				null, start, new BigDecimal("100000"), new BigDecimal("4"), null, account, 1, 84, null, null,
				RepaymentMethod.MONTHLY_FIXED, new BigDecimal("1367"), null, List.of(), null, null));
		assertThat(monthly).containsExactly(new ConfirmRow("名稱", "土銀信貸"), new ConfirmRow("銀行", "土地銀行"),
				new ConfirmRow("還款方式", "每月固定還款"), new ConfirmRow("股票質押", "否"),
				new ConfirmRow("撥款日", "2026-07-28"), new ConfirmRow("借款金額", "100,000"),
				new ConfirmRow("年利率（%）", "4"), new ConfirmRow("每月扣款金額", "1,367"),
				new ConfirmRow("每月扣款日", "1"), new ConfirmRow("總期數", "84"), new ConfirmRow("往來帳戶", "台銀活存"),
				new ConfirmRow("開辦費、手續費", ""));

		List<ConfirmRow> pledge = service.rowsFor(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW,
				null, start, new BigDecimal("300000"), new BigDecimal("2.55"), null, null, 5, null, null,
				start.plusYears(1), RepaymentMethod.INTEREST_ONLY, null, new BigDecimal("3000"), List.of(), null, null));
		assertThat(pledge).containsExactly(new ConfirmRow("名稱", "玉山質押"), new ConfirmRow("銀行", "玉山銀行"),
				new ConfirmRow("還款方式", "每月繳息、到期還本"), new ConfirmRow("股票質押", "是"),
				new ConfirmRow("撥款日", "2026-07-28"), new ConfirmRow("借款金額", "300,000"),
				new ConfirmRow("年利率（%）", "2.55"), new ConfirmRow("每月繳息日", "5"),
				new ConfirmRow("到期日", "2027-07-28"), new ConfirmRow("往來帳戶", ""),
				new ConfirmRow("開辦費、手續費", "3,000"));
	}

	/** 股票：一次買進列交割戶；目前持有不列；資金來源顯示借款名稱，沒選是「自己的錢」。 */
	@Test
	void rowsForTrade() {
		Long settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		LocalDate day = LocalDate.of(2026, 3, 12);

		assertThat(service.rowsFor(new TradeForm(TradeType.BUY, day, "0050", settlement, 1000L, new BigDecimal("73.50"),
				null, null, null, List.of())))
			.containsExactly(new ConfirmRow("股票代號", "0050"), new ConfirmRow("記法", "一次買進"),
					new ConfirmRow("日期", "2026-03-12"), new ConfirmRow("股數", "1,000"), new ConfirmRow("價格", "73.5"),
					new ConfirmRow("交割戶", "玉山交割戶"), new ConfirmRow("資金來源", "自己的錢"));
		assertThat(service.rowsFor(new TradeForm(TradeType.OPENING, day, "", null, null, null, null, null, null,
				List.of())))
			.containsExactly(new ConfirmRow("股票代號", ""), new ConfirmRow("記法", "目前持有"),
					new ConfirmRow("日期", "2026-03-12"), new ConfirmRow("股數", ""), new ConfirmRow("價格", ""),
					new ConfirmRow("資金來源", "自己的錢"));
	}

	/** 借款預覽的提醒：到期日已過、合約月付金和銀行扣款差很多。 */
	@Test
	void loanWarnings() {
		Long account = accounts.create(new CashAccountForm("台銀活存", CashAccountType.BANK_DEMAND, "台銀")).getId();
		LocalDate today = LocalDate.now(clock);

		assertThat(service.loanWarnings(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null,
				today.minusYears(2), new BigDecimal("300000"), new BigDecimal("2.5"), null, account, null, null, null,
				today.minusDays(1), RepaymentMethod.BULLET, null, null, List.of(), null, null)))
			.singleElement()
			.asString()
			.contains("已經過了");
		assertThat(service.loanWarnings(new LoanForm("土銀信貸", LoanType.CREDIT, "土地銀行", LoanOrigin.NEW, null,
				today.minusDays(3), new BigDecimal("100000"), new BigDecimal("4"), null, account, 1, 84, null, null,
				RepaymentMethod.MONTHLY_FIXED, new BigDecimal("1500"), null, List.of(), null, null)))
			.singleElement()
			.asString()
			.contains("和銀行扣款 1,500");
	}

	// ---------- 帳戶這一步（Task 4） ----------

	/** 帳戶和初始現金餘額在同一個交易裡建立；沒填餘額只建帳戶。 */
	@Test
	void addAccountWithOpeningBalance() {
		LocalDate today = LocalDate.now(clock);

		CashAccount account = service.addAccount(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券"),
				new OpeningForm(today, new BigDecimal("50000")));
		service.addAccount(new CashAccountForm("台銀活存", CashAccountType.BANK_DEMAND, "台銀"), null);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_account", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement WHERE type = 'OPENING' AND account_id = ? "
				+ "AND amount = 50000 AND movement_date = ?", Integer.class, account.getId(), today)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement", Integer.class)).isEqualTo(1);
	}

	/** 餘額不合規則（負數）時帳戶也不會建立：兩筆在同一個交易，不會留下沒有餘額的半套資料。 */
	@Test
	void addAccountRollsBackWhenOpeningInvalid() {
		assertThatThrownBy(() -> service.addAccount(
				new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券"),
				new OpeningForm(LocalDate.now(clock), new BigDecimal("-1"))))
			.isInstanceOf(CashValidationException.class);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_account", Integer.class)).isZero();
	}

	/** 修改：帳戶照現有的 update；已有初始現金餘額就修改（原因「第一次使用引導修改」），沒有就新增。 */
	@Test
	void editAccountInWizard() {
		LocalDate today = LocalDate.now(clock);
		Long withOpening = service.addAccount(new CashAccountForm("玉山", CashAccountType.SETTLEMENT, "玉山證券"),
				new OpeningForm(today, new BigDecimal("50000"))).getId();
		Long without = service.addAccount(new CashAccountForm("台銀", CashAccountType.BANK_DEMAND, "台銀"), null).getId();

		service.editAccount(withOpening, new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券"),
				new OpeningForm(today, new BigDecimal("60000")));
		service.editAccount(without, new CashAccountForm("台銀活存", CashAccountType.BANK_DEMAND, "台灣銀行"),
				new OpeningForm(today, new BigDecimal("1000")));

		assertThat(accounts.getMine(withOpening).getName()).isEqualTo("玉山交割戶");
		assertThat(accounts.getMine(without).getInstitution()).isEqualTo("台灣銀行");
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE account_id = ? AND type = 'OPENING'",
				BigDecimal.class, withOpening)).isEqualByComparingTo("60000");
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE account_id = ? AND type = 'OPENING'",
				BigDecimal.class, without)).isEqualByComparingTo("1000");
		assertThat(jdbc.queryForList("SELECT DISTINCT reason FROM cash_movement_revision", String.class))
			.containsExactly("第一次使用引導修改");
	}

	/** 「儲存修改」什麼都沒改：不寫修改紀錄。 */
	@Test
	void editWithoutChangesWritesNoRevision() {
		LocalDate day = LocalDate.now(clock).minusDays(3);
		CashAccountForm form = new CashAccountForm("玉山", CashAccountType.SETTLEMENT, "玉山證券");
		Long id = service.addAccount(form, new OpeningForm(day, new BigDecimal("50000"))).getId();

		service.editAccount(id, form, new OpeningForm(day, new BigDecimal("50000.00")));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement_revision", Integer.class)).isZero();
	}

	/** 修改時的預覽和存檔一樣：初始現金餘額日期不能晚於帳戶裡已經有的異動。 */
	@Test
	void editPreviewChecksEarlierMovements() {
		LocalDate today = LocalDate.now(clock);
		CashAccountForm form = new CashAccountForm("玉山", CashAccountType.SETTLEMENT, "玉山證券");
		Long id = service.addAccount(form, new OpeningForm(today.minusDays(10), new BigDecimal("1000"))).getId();
		movements.record(new CashMovementForm(CashMovementType.DEPOSIT, today.minusDays(5), null, id,
				new BigDecimal("100"), null));
		OpeningForm later = new OpeningForm(today.minusDays(2), new BigDecimal("1000"));

		assertThat(service.accountErrors(id, form, later)).singleElement().asString().contains("已經有異動");
		assertThatThrownBy(() -> service.editAccount(id, form, later)).hasMessageContaining("已經有異動");
		assertThat(service.accountErrors(id, form, new OpeningForm(today.minusDays(6), new BigDecimal("1000"))))
			.isEmpty();
	}

	/** 已經有初始現金餘額卻把餘額清空：不能存，免得確認畫面寫「（沒填）」但資料庫還留著舊的餘額。 */
	@Test
	void editCannotClearOpening() {
		Long id = service.addAccount(new CashAccountForm("玉山", CashAccountType.SETTLEMENT, "玉山證券"),
				new OpeningForm(LocalDate.now(clock), new BigDecimal("50000"))).getId();
		CashAccountForm form = new CashAccountForm("玉山", CashAccountType.SETTLEMENT, "玉山證券");

		assertThat(service.accountErrors(id, form, null)).containsExactly(OnboardingService.OPENING_CANNOT_CLEAR);
		assertThatThrownBy(() -> service.editAccount(id, form, null)).hasMessage(OnboardingService.OPENING_CANNOT_CLEAR);
	}

	/** 預覽的檢查：名稱重複（修改時排除自己）、餘額規則，訊息和存檔時一樣。 */
	@Test
	void accountErrorsMatchSave() {
		Long id = service.addAccount(new CashAccountForm("玉山", CashAccountType.SETTLEMENT, "玉山證券"), null).getId();
		LocalDate today = LocalDate.now(clock);

		assertThat(service.accountErrors(null, new CashAccountForm("玉山", CashAccountType.SETTLEMENT, "x"), null))
			.containsExactly("帳戶名稱已存在");
		assertThat(service.accountErrors(id, new CashAccountForm("玉山", CashAccountType.SETTLEMENT, "x"), null))
			.isEmpty();
		assertThat(service.accountErrors(null, new CashAccountForm("台銀", CashAccountType.BANK_DEMAND, "台銀"),
				new OpeningForm(today, new BigDecimal("-5"))))
			.containsExactly("初始現金餘額不能是負數");
		assertThat(service.accountErrors(null, new CashAccountForm("台銀", CashAccountType.BANK_DEMAND, "台銀"),
				new OpeningForm(null, new BigDecimal("5"))))
			.containsExactly("請選擇日期");
	}

	/** 已儲存清單「名稱（類型）」；至少一個證券交割戶才能往下走；別人的帳戶不算。 */
	@Test
	void savedAccountsAndSettlementCheck() {
		loginAs("ob-ben");
		accounts.create(new CashAccountForm("別人的戶", CashAccountType.SETTLEMENT, "元大證券"));
		loginAs("ob-amy");
		assertThat(service.hasSettlementAccount()).isFalse();

		Long bank = accounts.create(new CashAccountForm("台銀活存", CashAccountType.BANK_DEMAND, "台銀")).getId();
		assertThat(service.hasSettlementAccount()).isFalse();
		Long settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();

		assertThat(service.hasSettlementAccount()).isTrue();
		assertThat(service.savedAccounts()).containsExactly(new SavedRow(bank, "台銀活存（銀行活存）"),
				new SavedRow(settlement, "玉山交割戶（證券交割戶）"));
	}

	// ---------- 擔保這一步（Task 7） ----------

	/** 擔保頁的持股清單與預設：還沒設定擔保時全部勾、股數是全部持股；存了之後照存的內容，別的股票沒勾。 */
	@Test
	void collateralPageDefaultsAndSavedRows() {
		Pledged p = pledgedWithStocks();

		CollateralPage page = service.collateralPage(p.loan());
		assertThat(page.loanName()).isEqualTo("玉山質押");
		assertThat(page.rows()).extracting(CollateralPage.Row::code, CollateralPage.Row::held,
				CollateralPage.Row::pledged)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("0050", 2000L, null),
					org.assertj.core.groups.Tuple.tuple("0056", 1000L, null));
		assertThat(page.warnPercent()).isEqualByComparingTo("150");
		assertThat(page.callPercent()).isEqualByComparingTo("130");
		assertThat(page.saved()).isEmpty();
		assertThat(service.collateralForm(p.loan()).collateral()).containsExactly(
				new CollateralPick(p.stock0050(), 2000L), new CollateralPick(p.stock0056(), 1000L));

		service.saveCollateral(p.loan(), List.of(new CollateralPick(p.stock0050(), 1500L)), new BigDecimal("150"),
				new BigDecimal("130"));

		assertThat(service.collateralPage(p.loan()).saved()).containsExactly("0050 元大台灣50　1,500 股");
		assertThat(service.collateralForm(p.loan()).collateral()).containsExactly(
				new CollateralPick(p.stock0050(), 1500L), new CollateralPick(null, 1000L));
	}

	/**
	 * 第二筆質押押同一批股票：預設股數是持股扣掉其他（沒作廢）質押已押的；可押的是 0 就不勾。
	 * 第一筆照預設（全部持股）存好後，第二筆的預設是 0、不勾，照預設預覽可以過。
	 */
	@Test
	void secondPledgeDefaultsToAvailableShares() {
		Pledged p = pledgedWithStocks();
		Long second = service.addLoan(pledge("國泰質押", p.account())).getId();
		CollateralForm first = service.collateralForm(p.loan());
		service.saveCollateral(p.loan(), first.collateral(), first.warnPercent(), first.callPercent());

		assertThat(service.collateralPage(second).rows()).extracting(CollateralPage.Row::code,
				CollateralPage.Row::held, CollateralPage.Row::available)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("0050", 2000L, 0L),
					org.assertj.core.groups.Tuple.tuple("0056", 1000L, 0L));
		CollateralForm defaults = service.collateralForm(second);
		assertThat(defaults.collateral()).containsExactly(new CollateralPick(null, 0L), new CollateralPick(null, 0L));
		assertThat(service.checkCollateral(second, defaults,
				new org.springframework.validation.BeanPropertyBindingResult(defaults, "form"))).isEmpty();

		// 第一筆只押一部分：第二筆的預設是剩下的、勾起來
		service.saveCollateral(p.loan(), List.of(new CollateralPick(p.stock0050(), 1500L)), first.warnPercent(),
				first.callPercent());
		assertThat(service.collateralForm(second).collateral()).containsExactly(
				new CollateralPick(p.stock0050(), 500L), new CollateralPick(p.stock0056(), 1000L));
		// 已經存了第二筆：沒押的那檔預設也是可押的股數
		service.saveCollateral(second, List.of(new CollateralPick(p.stock0056(), 1000L)), first.warnPercent(),
				first.callPercent());
		assertThat(service.collateralForm(second).collateral()).containsExactly(new CollateralPick(null, 500L),
				new CollateralPick(p.stock0056(), 1000L));
	}

	/** 儲存擔保：勾的照股數存，沒勾的拿掉（呼叫 LoanService.setCollateral，0 ＝ 拿掉）。 */
	@Test
	void saveCollateralSetsShares() {
		Pledged p = pledgedWithStocks();

		service.saveCollateral(p.loan(), List.of(new CollateralPick(p.stock0050(), 2000L),
				new CollateralPick(p.stock0056(), 400L)), new BigDecimal("150"), new BigDecimal("130"));
		assertThat(collateralShares(p.loan())).containsExactly(2000L, 400L);

		service.saveCollateral(p.loan(), List.of(new CollateralPick(null, 2000L),
				new CollateralPick(p.stock0056(), 1000L)), new BigDecimal("150"), new BigDecimal("130"));
		assertThat(collateralShares(p.loan())).containsExactly(1000L);
	}

	/** 門檻只有和目前生效值不同才寫新的 RateSetting 版本：預設值存兩次不寫，改了寫一次，再存一樣的不寫。 */
	@Test
	void unchangedThresholdsWriteNoRateSetting() {
		Pledged p = pledgedWithStocks();
		List<CollateralPick> picks = List.of(new CollateralPick(p.stock0050(), 2000L));

		service.saveCollateral(p.loan(), picks, new BigDecimal("150"), new BigDecimal("130"));
		service.saveCollateral(p.loan(), picks, new BigDecimal("150.00"), new BigDecimal("130"));
		assertThat(thresholdRows(p.loan())).isZero();

		service.saveCollateral(p.loan(), picks, new BigDecimal("160"), new BigDecimal("130"));
		assertThat(thresholdRows(p.loan())).isEqualTo(2);
		service.saveCollateral(p.loan(), picks, new BigDecimal("160"), new BigDecimal("130"));
		assertThat(thresholdRows(p.loan())).isEqualTo(2);
		assertThat(service.collateralPage(p.loan()).warnPercent()).isEqualByComparingTo("160");
	}

	/** 好幾檔一起存：有一檔超過持股就全部不存（同一個交易），訊息和資金來源頁一樣。 */
	@Test
	void saveCollateralIsAllOrNothing() {
		Pledged p = pledgedWithStocks();

		assertThatThrownBy(() -> service.saveCollateral(p.loan(), List.of(new CollateralPick(p.stock0050(), 2000L),
				new CollateralPick(p.stock0056(), 1001L)), new BigDecimal("160"), new BigDecimal("130")))
			.isInstanceOf(LoanValidationException.class)
			.hasMessage("擔保股數不能超過持有股數（1,000 股）");

		assertThat(collateralShares(p.loan())).isEmpty();
		assertThat(thresholdRows(p.loan())).isZero();
	}

	/** 進擔保那一步：第一筆還沒設定擔保的質押；都設定過就第一筆。上一步、下一步照質押的順序。 */
	@Test
	void pledgeOrderForCollateralPages() {
		Pledged p = pledgedWithStocks();
		Long second = service.addLoan(pledge("國泰質押", p.account())).getId();

		assertThat(service.collateralEntryUrl()).isEqualTo("/welcome/collateral/" + p.loan());
		service.saveCollateral(p.loan(), List.of(new CollateralPick(p.stock0050(), 100L)), new BigDecimal("150"),
				new BigDecimal("130"));
		assertThat(service.collateralEntryUrl()).isEqualTo("/welcome/collateral/" + second);
		assertThat(service.collateralSkipped()).isTrue();
		service.saveCollateral(second, List.of(new CollateralPick(p.stock0056(), 100L)), new BigDecimal("150"),
				new BigDecimal("130"));
		assertThat(service.collateralEntryUrl()).isEqualTo("/welcome/collateral/" + p.loan());
		assertThat(service.collateralSkipped()).isFalse();

		assertThat(service.collateralBackUrl(p.loan())).isEqualTo("/welcome/stocks");
		assertThat(service.collateralBackUrl(second)).isEqualTo("/welcome/collateral/" + p.loan());
		assertThat(service.collateralNextUrl(p.loan())).isEqualTo("/welcome/collateral/" + second);
		assertThat(service.collateralNextUrl(second)).isEqualTo("/welcome/done");
		assertThat(service.doneBackUrl()).isEqualTo("/welcome/collateral/" + second);
		// 擔保頁的下一步按鈕：最後一筆到完成頁，不是最後一筆到下一筆質押
		assertThat(service.collateralPage(p.loan()).nextLabel()).isEqualTo("下一步：下一筆質押 →");
		assertThat(service.collateralPage(second).nextLabel()).isEqualTo("下一步：完成 →");
	}

	/** 別人的、信貸、不存在的借款都沒有擔保頁（404）。 */
	@Test
	void collateralPageOnlyForMyPledges() {
		Pledged p = pledgedWithStocks();
		loginAs("ob-ben");
		assertThatThrownBy(() -> service.collateralPage(p.loan())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.saveCollateral(p.loan(), List.of(), new BigDecimal("150"),
				new BigDecimal("130"))).isInstanceOf(NotFoundException.class);
		loginAs("ob-amy");
		assertThat(service.collateralSkipped()).isTrue();
		assertThat(service.doneBackUrl()).isEqualTo("/welcome/collateral/" + p.loan());
	}

	private record Pledged(Long account, Long loan, Long stock0050, Long stock0056) {
	}

	/** 一個交割戶、一筆質押、持有 0050 2,000 股與 0056 1,000 股。 */
	private Pledged pledgedWithStocks() {
		Long settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0050', '元大台灣50', "
				+ "'ETF', 2, NOW())");
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0056', '元大高股息', "
				+ "'ETF', 4, NOW())");
		LocalDate today = LocalDate.now(clock);
		trades.create(buy("0050", today.minusDays(5), settlement));
		trades.create(buy("0050", today.minusDays(4), settlement));
		trades.create(buy("0056", today.minusDays(3), settlement));
		Long loan = service.addLoan(pledge("玉山質押", settlement)).getId();
		return new Pledged(settlement, loan, stockId("0050"), stockId("0056"));
	}

	private LoanForm pledge(String name, Long account) {
		LocalDate today = LocalDate.now(clock);
		return new LoanForm(name, LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, today.minusDays(60),
				new BigDecimal("500000"), new BigDecimal("2.5"), null, account, null, null, null, today.plusDays(300),
				RepaymentMethod.BULLET, null, null, List.of(), null, null);
	}

	private Long stockId(String code) {
		return jdbc.queryForObject("SELECT id FROM stock WHERE code = ?", Long.class, code);
	}

	private List<Long> collateralShares(Long loan) {
		return jdbc.queryForList("SELECT shares FROM pledge_collateral WHERE loan_id = ? ORDER BY stock_id", Long.class,
				loan);
	}

	private int thresholdRows(Long loan) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM rate_setting WHERE loan_id = ? AND kind IN "
				+ "('MAINTENANCE_WARN', 'MAINTENANCE_CALL')", Integer.class, loan);
	}

	private TradeForm buy(String code, LocalDate date, Long account) {
		return new TradeForm(TradeType.BUY, date, code, account, 1000L, new BigDecimal("50"), null, null, null,
				List.of());
	}

	private void loginAs(String username) {
		UserDetails details = userDetailsService.loadUserByUsername(username);
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	private void createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
	}

}
