package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

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
import com.sideproject.stockledger.dto.AccountCard;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.CashMovementForm;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.Direction;
import com.sideproject.stockledger.dto.MovementRow;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.ReconcileForm;
import com.sideproject.stockledger.dto.TotalAssetsView;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 總資產頁（spec 第 3 節第 2 頁、4.3、4.6）：這一版只有現金。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class TotalAssetsServiceTest {

	@Autowired
	private TotalAssetsService service;

	@Autowired
	private CashMovementService movements;

	@Autowired
	private LoanService loans;

	@Autowired
	private CashAccountService accounts;

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

	private LocalDate today;
	/** 本月 1 號（測試日期都放在本月，月初也成立）。 */
	private LocalDate firstOfMonth;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		createIfMissing("ta-amy");
		createIfMissing("ta-ben");
		loginAs("ta-amy");
		today = LocalDate.now(clock);
		firstOfMonth = today.withDayOfMonth(1);
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void emptyUserShowsZeros() {
		TotalAssetsView view = service.view();

		assertThat(view.totalAssets()).isEqualByComparingTo("0");
		assertThat(view.stockValue()).isEqualByComparingTo("0");
		assertThat(view.cash()).isEqualByComparingTo("0");
		assertThat(view.cashSharePercent()).isNull();
		assertThat(view.allocation()).isEmpty();
		assertThat(view.largest()).isNull();
		assertThat(view.monthChange().total()).isEqualByComparingTo("0");
	}

	@Test
	void cardsAndAllocation() {
		Long a = account("大帳戶");
		Long b = account("小帳戶");
		movements.setOpening(b, new OpeningForm(firstOfMonth, new BigDecimal("400")));
		movements.setOpening(a, new OpeningForm(firstOfMonth, new BigDecimal("600")));

		TotalAssetsView view = service.view();

		assertThat(view.totalAssets()).isEqualByComparingTo("1000");
		assertThat(view.cash()).isEqualByComparingTo("1000");
		assertThat(view.accountCount()).isEqualTo(2);
		assertThat(view.cashSharePercent()).isEqualByComparingTo("100.00");
		assertThat(view.allocation()).extracting(r -> r.name()).containsExactly("大帳戶", "小帳戶");
		assertThat(view.allocation().get(0).sharePercent()).isEqualByComparingTo("60.00");
		assertThat(view.allocation().get(1).sharePercent()).isEqualByComparingTo("40.00");
		assertThat(view.largest().name()).isEqualTo("大帳戶");
	}

	/** 本月存入 500、支出 200、對帳調整 −100 → 200；初始現金餘額與互轉不算（spec 4.3-2）。 */
	@Test
	void monthChangeFromMovements() {
		Long a = account("A");
		Long b = account("B");
		movements.setOpening(a, new OpeningForm(firstOfMonth, new BigDecimal("1000")));
		movements.record(form(CashMovementType.DEPOSIT, firstOfMonth, a, null, "500"));
		movements.record(form(CashMovementType.WITHDRAWAL, firstOfMonth, a, null, "200"));
		movements.record(form(CashMovementType.TRANSFER, firstOfMonth, a, b, "300"));
		// 對帳前系統餘額 1000 + 500 − 200 − 300 = 1000，實際 900
		movements.reconcile(a, new ReconcileForm(today, new BigDecimal("900")));

		TotalAssetsView view = service.view();

		assertThat(view.monthChange().deposits()).isEqualByComparingTo("500");
		assertThat(view.monthChange().withdrawals()).isEqualByComparingTo("200");
		assertThat(view.monthChange().adjustments()).isEqualByComparingTo("-100");
		assertThat(view.monthChange().total()).isEqualByComparingTo("200");
		assertThat(view.cash()).isEqualByComparingTo("1200");
	}

	/** 本月淨資產變動加上借款利息（應計，DR-008）；質押今天起算 → 1 天的利息 33 元。 */
	@Test
	void monthChangeIncludesLoanInterest() {
		Long a = account("A");
		loans.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING, new BigDecimal("400000"),
				today, new BigDecimal("400000"), new BigDecimal("3"), null, a, 25, null, null, null));

		TotalAssetsView view = service.view();

		assertThat(view.monthChange().interest()).isEqualByComparingTo("33");
		assertThat(view.monthChange().total()).isEqualByComparingTo("-33");
	}

	@Test
	void accountCardShowsBalanceAndLastReconciliation() {
		Long a = account("A");
		movements.setOpening(a, new OpeningForm(firstOfMonth, new BigDecimal("1000")));
		movements.reconcile(a, new ReconcileForm(today, new BigDecimal("950")));

		AccountCard card = service.view().accounts().get(0);

		assertThat(card.balance()).isEqualByComparingTo("950");
		assertThat(card.lastReconciledOn()).isEqualTo(today);
		assertThat(card.lastAdjustment()).isEqualByComparingTo("-50");
		assertThat(card.hasOpening()).isTrue();
		assertThat(card.reconcileOverdue()).isFalse();
		assertThat(card.typeLabel()).isEqualTo("銀行活存");
	}

	/** 超過 30 天沒對帳要提醒；從沒對帳過的從初始現金餘額日期起算（spec 4.6-7）。 */
	@Test
	void overdueReminderAfter30Days() {
		Long overdue = account("太久沒對帳");
		Long fine = account("剛好 30 天");
		movements.setOpening(overdue, new OpeningForm(today.minusDays(31), BigDecimal.ZERO));
		movements.setOpening(fine, new OpeningForm(today.minusDays(30), BigDecimal.ZERO));

		TotalAssetsView view = service.view();

		assertThat(card(view, "太久沒對帳").reconcileOverdue()).isTrue();
		assertThat(card(view, "剛好 30 天").reconcileOverdue()).isFalse();
	}

	/** 沒有初始現金餘額也沒對帳：從帳戶建立日起算。 */
	@Test
	void overdueCountsFromAccountCreationWhenNothingElse() {
		Long a = account("舊帳戶");
		jdbc.update("UPDATE cash_account SET created_at = ? WHERE id = ?", LocalDateTime.now(clock).minusDays(40), a);

		assertThat(card(service.view(), "舊帳戶").reconcileOverdue()).isTrue();
		assertThat(card(service.view(), "舊帳戶").hasOpening()).isFalse();
	}

	@Test
	void negativeBalanceFlagged() {
		Long a = account("A");
		movements.record(form(CashMovementType.WITHDRAWAL, today, a, null, "100"));

		AccountCard card = service.view().accounts().get(0);
		assertThat(card.balance()).isEqualByComparingTo("-100");
		assertThat(card.negative()).isTrue();
	}

	@Test
	void recentShowsLatest10WithRevisedFlagAndDirections() {
		Long a = account("A");
		Long b = account("B");
		for (int i = 0; i < 11; i++) {
			movements.record(form(CashMovementType.DEPOSIT, firstOfMonth, a, null, "1"));
		}
		Long withdrawal = movements.record(form(CashMovementType.WITHDRAWAL, today, a, null, "5")).getId();
		Long transfer = movements.record(form(CashMovementType.TRANSFER, today, a, b, "7")).getId();
		movements.edit(withdrawal, form(CashMovementType.WITHDRAWAL, today, a, null, "6"), "打錯");

		var recent = service.view().recent();

		assertThat(recent).hasSize(10);
		MovementRow w = recent.stream().filter(r -> r.id().equals(withdrawal)).findFirst().orElseThrow();
		assertThat(w.direction()).isEqualTo(Direction.OUT);
		assertThat(w.amount()).isEqualByComparingTo("6");
		assertThat(w.revised()).isTrue();
		MovementRow t = recent.stream().filter(r -> r.id().equals(transfer)).findFirst().orElseThrow();
		assertThat(t.direction()).isEqualTo(Direction.NEUTRAL);
		assertThat(t.accountLabel()).isEqualTo("A → B");
	}

	@Test
	void reconcileRowShowsAdjustment() {
		Long a = account("A");
		movements.setOpening(a, new OpeningForm(firstOfMonth, new BigDecimal("1000")));
		Long r = movements.reconcile(a, new ReconcileForm(today, new BigDecimal("1200"))).getId();

		MovementRow row = service.view().recent().stream().filter(x -> x.id().equals(r)).findFirst().orElseThrow();
		assertThat(row.direction()).isEqualTo(Direction.IN);
		assertThat(row.amount()).isEqualByComparingTo("200");
		assertThat(row.description()).contains("1,200");
	}

	@Test
	void onlyMyDataInView() {
		Long a = account("艾咪的帳戶");
		movements.setOpening(a, new OpeningForm(firstOfMonth, new BigDecimal("1000")));
		loginAs("ta-ben");

		TotalAssetsView view = service.view();

		assertThat(view.accounts()).isEmpty();
		assertThat(view.recent()).isEmpty();
		assertThat(view.totalAssets()).isEqualByComparingTo("0");
	}

	/** 作廢的不計入餘額、本月變動、上次對帳日期（Review Focus #5）。 */
	@Test
	void voidedEventsIgnored() {
		Long a = account("A");
		movements.setOpening(a, new OpeningForm(firstOfMonth, new BigDecimal("1000")));
		Long deposit = movements.record(form(CashMovementType.DEPOSIT, firstOfMonth, a, null, "500")).getId();
		Long reconcile = movements.reconcile(a, new ReconcileForm(today, new BigDecimal("100"))).getId();
		movements.voidMovement(deposit, null);
		movements.voidMovement(reconcile, null);

		TotalAssetsView view = service.view();

		assertThat(view.cash()).isEqualByComparingTo("1000");
		assertThat(view.monthChange().total()).isEqualByComparingTo("0");
		assertThat(view.accounts().get(0).lastReconciledOn()).isNull();
		assertThat(view.recent()).extracting(MovementRow::id).doesNotContain(deposit, reconcile);
	}

	/** 先排除作廢的再取 10 筆：最新的一筆作廢了，仍要顯示 10 筆沒作廢的（審核補測）。 */
	@Test
	void recentFiltersVoidedBeforeLimit() {
		Long a = account("A");
		for (int i = 0; i < 10; i++) {
			movements.record(form(CashMovementType.DEPOSIT, firstOfMonth, a, null, "1"));
		}
		Long newest = movements.record(form(CashMovementType.DEPOSIT, today, a, null, "2")).getId();
		movements.voidMovement(newest, null);

		assertThat(service.view().recent()).hasSize(10).extracting(MovementRow::id).doesNotContain(newest);
	}

	/** 全部異動頁：依月份，含作廢的（標記已作廢）。 */
	@Test
	void movementsByMonthIncludeVoided() {
		Long a = account("A");
		Long thisMonth = movements.record(form(CashMovementType.DEPOSIT, firstOfMonth, a, null, "1")).getId();
		Long lastMonth = movements.record(form(CashMovementType.DEPOSIT, firstOfMonth.minusDays(1), a, null, "2")).getId();
		movements.voidMovement(thisMonth, null);

		var rows = service.movements(YearMonth.from(today));

		assertThat(rows).extracting(MovementRow::id).containsExactly(thisMonth);
		assertThat(rows.get(0).voided()).isTrue();
		assertThat(service.movements(YearMonth.from(firstOfMonth.minusDays(1)))).extracting(MovementRow::id)
			.contains(lastMonth);
	}

	// ---------- 備註搜尋（spec 4.6-9） ----------

	/** 搜尋所有月份，新的在前。 */
	@Test
	void searchAcrossMonthsNewestFirst() {
		Long a = account("A");
		Long older = note(a, firstOfMonth.minusDays(1), "保險");
		Long newer = note(a, today, "年繳保險");
		note(a, today, "薪水");

		assertThat(service.search("保險").rows()).extracting(MovementRow::id).containsExactly(newer, older);
	}

	@Test
	void searchIgnoresCaseAndTrims() {
		Long a = account("A");
		Long id = note(a, today, "ETF 定期定額");

		assertThat(service.search("  etf ").rows()).extracting(MovementRow::id).containsExactly(id);
	}

	@Test
	void searchIncludesVoided() {
		Long a = account("A");
		Long id = note(a, today, "保險");
		movements.voidMovement(id, null);

		assertThat(service.search("保險").rows()).singleElement().satisfies(row -> assertThat(row.voided()).isTrue());
	}

	/** % 和 _ 是一般文字，不是萬用字元；跳脫用的反斜線本身也能搜尋。 */
	@Test
	void searchTreatsWildcardsLiterally() {
		Long a = account("A");
		Long percent = note(a, today, "100%");
		note(a, today, "1000");
		Long underscore = note(a, today, "a_b");
		note(a, today, "axb");

		assertThat(service.search("%").rows()).extracting(MovementRow::id).containsExactly(percent);
		assertThat(service.search("_").rows()).extracting(MovementRow::id).containsExactly(underscore);
		Long backslash = note(a, today, "a\\b");
		assertThat(service.search("\\").rows()).extracting(MovementRow::id).containsExactly(backslash);
	}

	@Test
	void searchOnlyMine() {
		loginAs("ta-ben");
		note(account("班的帳戶"), today, "保險");
		loginAs("ta-amy");
		Long mine = note(account("A"), today, "保險");

		assertThat(service.search("保險").rows()).extracting(MovementRow::id).containsExactly(mine);
	}

	@Test
	void searchTooLong() {
		assertThat(service.search("字".repeat(50)).error()).isNull();
		assertThat(service.search("字".repeat(51)).error()).isEqualTo("搜尋字最多 50 個字");
		assertThat(service.search("字".repeat(51)).rows()).isEmpty();
	}

	/** 存入一筆有備註的異動，回傳 id。 */
	private Long note(Long account, LocalDate date, String note) {
		return movements.record(new CashMovementForm(CashMovementType.DEPOSIT, date, null, account, BigDecimal.TEN,
				note)).getId();
	}

	private AccountCard card(TotalAssetsView view, String name) {
		return view.accounts().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
	}

	private Long account(String name) {
		return accounts.create(new CashAccountForm(name, CashAccountType.BANK_DEMAND, "國泰世華")).getId();
	}

	/** account 是錢動到的帳戶：存入時是轉入帳戶，支出與互轉時是轉出帳戶；to 只有互轉用。 */
	private static CashMovementForm form(CashMovementType type, LocalDate date, Long account, Long to, String amount) {
		return type == CashMovementType.DEPOSIT
				? new CashMovementForm(type, date, null, account, new BigDecimal(amount), null)
				: new CashMovementForm(type, date, account, to, new BigDecimal(amount), null);
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
