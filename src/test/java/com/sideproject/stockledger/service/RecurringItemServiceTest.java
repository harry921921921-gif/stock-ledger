package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.function.UnaryOperator;

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
import com.sideproject.stockledger.dto.FillForm;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.RecurringItemForm;
import com.sideproject.stockledger.dto.UnfilledPeriod;
import com.sideproject.stockledger.entity.AmountMode;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.RecurringCycle;
import com.sideproject.stockledger.entity.RecurringDirection;
import com.sideproject.stockledger.entity.RecurringItem;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 固定項目的新增、修改、結束（設計文件 4.1、4.2）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RecurringItemServiceTest {

	@Autowired
	private RecurringItemService service;

	@Autowired
	private CashAccountService accounts;

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

	private LocalDate today;
	private YearMonth thisMonth;
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
		createIfMissing("ri-amy");
		createIfMissing("ri-ben");
		loginAs("ri-amy");
		today = LocalDate.now(clock);
		thisMonth = YearMonth.from(today);
		bank = accounts.create(new CashAccountForm("薪資帳戶", CashAccountType.BANK_DEMAND, "國泰世華")).getId();
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void newFormDefaults() {
		RecurringItemForm form = service.newForm();

		assertThat(form.direction()).isEqualTo(RecurringDirection.EXPENSE);
		assertThat(form.amountMode()).isEqualTo(AmountMode.FIXED);
		assertThat(form.cycle()).isEqualTo(RecurringCycle.MONTHLY);
		assertThat(form.startMonth()).isEqualTo(thisMonth);
	}

	@Test
	void createValidatesFields() {
		rejects(f -> name(f, " "), "請輸入名稱");
		rejects(f -> name(f, "長".repeat(51)), "名稱最多 50 個字");
		rejects(f -> new RecurringItemForm(f.name(), null, f.amountMode(), f.amount(), f.cycle(), f.anchorMonth(),
				f.dayOfMonth(), f.accountId(), f.startMonth(), f.endMonth()), "請選擇收入或支出");
		rejects(f -> new RecurringItemForm(f.name(), f.direction(), null, f.amount(), f.cycle(), f.anchorMonth(),
				f.dayOfMonth(), f.accountId(), f.startMonth(), f.endMonth()), "請選擇金額方式");
		rejects(f -> new RecurringItemForm(f.name(), f.direction(), f.amountMode(), f.amount(), null, f.anchorMonth(),
				f.dayOfMonth(), f.accountId(), f.startMonth(), f.endMonth()), "請選擇週期");
		rejects(f -> amount(f, null), "請輸入金額");
		rejects(f -> amount(f, "0"), "金額要大於 0");
		rejects(f -> amount(f, "10.5"), "金額只能是整數元");
		rejects(f -> amount(f, "1e12"), "金額太大");
		rejects(f -> new RecurringItemForm(f.name(), f.direction(), AmountMode.MANUAL, new BigDecimal("100"), f.cycle(),
				null, f.dayOfMonth(), f.accountId(), f.startMonth(), null), "每月自己填的項目不用填金額");
		rejects(f -> new RecurringItemForm(f.name(), f.direction(), AmountMode.MANUAL, null, RecurringCycle.YEARLY, 3,
				f.dayOfMonth(), f.accountId(), f.startMonth(), null), "每月自己填只能選每月");
		rejects(f -> day(f, null), "入帳日要在 1～31 號");
		rejects(f -> day(f, 0), "入帳日要在 1～31 號");
		rejects(f -> day(f, 32), "入帳日要在 1～31 號");
		rejects(f -> cycle(f, RecurringCycle.YEARLY, null), "請選擇月份");
		rejects(f -> cycle(f, RecurringCycle.SEMIANNUAL, 13), "請選擇月份");
		rejects(f -> cycle(f, RecurringCycle.MONTHLY, 3), "每月的項目不用選月份");
		rejects(f -> months(f, null, null), "請選擇開始月份");
		rejects(f -> months(f, YearMonth.of(1999, 12), null), "開始月份要在 2000-01 之後");
		rejects(f -> months(f, thisMonth, thisMonth.minusMonths(1)), "結束月份不能早於開始月份");
		rejects(f -> new RecurringItemForm(f.name(), f.direction(), f.amountMode(), f.amount(), f.cycle(),
				f.anchorMonth(), f.dayOfMonth(), null, f.startMonth(), f.endMonth()), "請選擇帳戶");

		assertThat(service.listMine()).isEmpty();
	}

	@Test
	void otherUsersAccountIsNotFound() {
		loginAs("ri-ben");
		Long benAccount = accounts.create(new CashAccountForm("班的帳戶", CashAccountType.BANK_DEMAND, "台銀")).getId();
		loginAs("ri-amy");

		assertThatThrownBy(() -> service.create(new RecurringItemForm("薪水", RecurringDirection.INCOME, AmountMode.FIXED,
				new BigDecimal("62000"), RecurringCycle.MONTHLY, null, 5, benAccount, thisMonth, null)))
			.isInstanceOf(NotFoundException.class);
	}

	/** 開始月份選過去：補記已到期的期數，備註是項目名稱，收入是存入（設計文件 4.2）。 */
	@Test
	void createBackfillsPastPeriods() {
		RecurringItemService.Created created = service.create(new RecurringItemForm("  薪水 ", RecurringDirection.INCOME,
				AmountMode.FIXED, new BigDecimal("62000"), RecurringCycle.MONTHLY, null, 1, bank,
				thisMonth.minusMonths(2), null));

		assertThat(created.item().getName()).isEqualTo("薪水");
		assertThat(created.backfilled()).isEqualTo(3);
		assertThat(jdbc.queryForList("SELECT type, note FROM cash_movement WHERE recurring_item_id = ?",
				created.item().getId())).hasSize(3)
			.allSatisfy(r -> assertThat(r).containsEntry("type", "DEPOSIT").containsEntry("note", "薪水"));
	}

	/** 修改金額只影響還沒產生的期數，已經記好的不變。 */
	@Test
	void editOnlyAffectsFuture() {
		RecurringItem item = service.create(rentForm()).item();

		service.edit(item.getId(), new RecurringItemForm("孝親費（調整）", RecurringDirection.EXPENSE, AmountMode.FIXED,
				new BigDecimal("9000"), RecurringCycle.MONTHLY, null, 1, bank, thisMonth, null));

		assertThat(service.find(item.getId()).getAmount()).isEqualByComparingTo("9000");
		assertThat(service.find(item.getId()).getName()).isEqualTo("孝親費（調整）");
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE recurring_item_id = ?", BigDecimal.class,
				item.getId())).isEqualByComparingTo("8000");
	}

	/** 年繳從 1 月改成 2 月：不能用新規則回頭補記過去幾年的 2 月（審查 Critical）。 */
	@Test
	void editAnchorDoesNotBackfillPast() {
		YearMonth start = YearMonth.of(today.getYear() - 2, 1);
		RecurringItem item = service.create(new RecurringItemForm("壽險", RecurringDirection.EXPENSE, AmountMode.FIXED,
				new BigDecimal("48000"), RecurringCycle.YEARLY, 1, 1, bank, start, null)).item();
		assertThat(periodsBeforeThisMonth(item)).isEqualTo(thisMonth.getMonthValue() == 1 ? 2 : 3);

		service.edit(item.getId(), new RecurringItemForm("壽險", null, null, new BigDecimal("48000"), null, 2, 1, bank,
				null, null));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement WHERE recurring_item_id = ? "
				+ "AND MONTH(period) = 2 AND period < ?", Integer.class, item.getId(), thisMonth.atDay(1))).isZero();
	}

	/** 換到沒有初始現金餘額的帳戶：原本因為早於初始現金餘額而略過的期數，不會在新帳戶補出來（審查 Important）。 */
	@Test
	void editAccountDoesNotBackfillSkippedPeriods() {
		movements.setOpening(bank, new OpeningForm(today, new BigDecimal("100000")));
		Long other = accounts.create(new CashAccountForm("另一個帳戶", CashAccountType.BANK_DEMAND, "台銀")).getId();
		RecurringItem item = service.create(new RecurringItemForm("孝親費", RecurringDirection.EXPENSE, AmountMode.FIXED,
				new BigDecimal("8000"), RecurringCycle.MONTHLY, null, 1, bank, thisMonth.minusMonths(3), null)).item();

		service.edit(item.getId(), new RecurringItemForm("孝親費", null, null, new BigDecimal("8000"), null, null, 1,
				other, null, null));

		assertThat(periodsBeforeThisMonth(item)).isZero();
	}

	/** 自動產生的異動屬於某一期：修改日期只能在那個月內，現金流和期別才對得上（審查 Minor）。 */
	@Test
	void generatedMovementDateStaysInItsPeriod() {
		RecurringItem living = livingFrom(thisMonth.minusMonths(1));
		CashMovement filled = service.fill(living.getId(), new FillForm(thisMonth.minusMonths(1), new BigDecimal("27000")));

		assertThatThrownBy(() -> movements.edit(filled.getId(), new CashMovementForm(CashMovementType.WITHDRAWAL, today,
				bank, null, new BigDecimal("27000"), "生活費"), null))
			.isInstanceOf(CashValidationException.class)
			.hasMessage("這筆是 " + thisMonth.minusMonths(1) + " 的固定收支，日期要在那個月內");
	}

	@Test
	void editCannotChangeLockedFields() {
		RecurringItem item = service.create(rentForm()).item();
		String message = "要改收入或支出、金額方式、週期或開始月份，請結束這個項目後另建一個";

		assertThatThrownBy(() -> service.edit(item.getId(), new RecurringItemForm("孝親費", RecurringDirection.INCOME,
				AmountMode.FIXED, new BigDecimal("8000"), RecurringCycle.MONTHLY, null, 1, bank, thisMonth, null)))
			.hasMessage(message);
		assertThatThrownBy(() -> service.edit(item.getId(), new RecurringItemForm("孝親費", RecurringDirection.EXPENSE,
				AmountMode.FIXED, new BigDecimal("8000"), RecurringCycle.YEARLY, 1, 1, bank, thisMonth, null)))
			.hasMessage(message);
		assertThatThrownBy(() -> service.edit(item.getId(), new RecurringItemForm("孝親費", RecurringDirection.EXPENSE,
				AmountMode.FIXED, new BigDecimal("8000"), RecurringCycle.MONTHLY, null, 1, bank, thisMonth.minusMonths(1),
				null))).hasMessage(message);
	}

	/** 不再需要：填結束月份，之後不再產生（不刪除）。 */
	@Test
	void endMonthStopsPosting() {
		RecurringItem item = service.create(new RecurringItemForm("健身房", RecurringDirection.EXPENSE, AmountMode.FIXED,
				new BigDecimal("1500"), RecurringCycle.MONTHLY, null, 28, bank, thisMonth.plusMonths(1), null)).item();

		service.edit(item.getId(), new RecurringItemForm("健身房", RecurringDirection.EXPENSE, AmountMode.FIXED,
				new BigDecimal("1500"), RecurringCycle.MONTHLY, null, 28, bank, thisMonth.plusMonths(1),
				thisMonth.plusMonths(1)));

		assertThat(service.find(item.getId()).getEndMonth()).isEqualTo(thisMonth.plusMonths(1));
	}

	@Test
	void listOnlyMineAndOthersAreNotFound() {
		RecurringItem mine = service.create(rentForm()).item();
		loginAs("ri-ben");

		assertThat(service.listMine()).isEmpty();
		assertThatThrownBy(() -> service.find(mine.getId())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.edit(mine.getId(), rentForm())).isInstanceOf(NotFoundException.class);
	}

	// ---------- 每月自己填（設計文件 4.3） ----------

	@Test
	void unfilledListsPastAndCurrentMonth() {
		RecurringItem living = livingFrom(thisMonth.minusMonths(2));

		assertThat(service.unfilled()).extracting(UnfilledPeriod::itemId, UnfilledPeriod::itemName,
				UnfilledPeriod::period, UnfilledPeriod::dueDate, UnfilledPeriod::overdue)
			.containsExactly(
					tuple(living.getId(), "生活費", thisMonth.minusMonths(2), thisMonth.minusMonths(2).atEndOfMonth(), true),
					tuple(living.getId(), "生活費", thisMonth.minusMonths(1), thisMonth.minusMonths(1).atEndOfMonth(), true),
					tuple(living.getId(), "生活費", thisMonth, thisMonth.atEndOfMonth(),
							today.equals(thisMonth.atEndOfMonth())));
	}

	/** 過去的期數用那一期的入帳日；本月還沒到入帳日就先填，用今天（不產生未來日期）。 */
	@Test
	void fillUsesDueDateOrToday() {
		RecurringItem living = livingFrom(thisMonth.minusMonths(1));

		CashMovement past = service.fill(living.getId(), new FillForm(thisMonth.minusMonths(1), new BigDecimal("27000")));
		CashMovement current = service.fill(living.getId(), new FillForm(thisMonth, new BigDecimal("25000")));

		assertThat(past.getMovementDate()).isEqualTo(thisMonth.minusMonths(1).atEndOfMonth());
		assertThat(past.getType()).isEqualTo(CashMovementType.WITHDRAWAL);
		assertThat(past.getNote()).isEqualTo("生活費");
		assertThat(past.getPeriod()).isEqualTo(thisMonth.minusMonths(1));
		assertThat(current.getMovementDate()).isEqualTo(today);
		assertThat(service.unfilled()).isEmpty();
	}

	@Test
	void fillRules() {
		RecurringItem living = livingFrom(thisMonth);
		RecurringItem rent = service.create(rentForm()).item();
		service.fill(living.getId(), new FillForm(thisMonth, new BigDecimal("27000")));

		assertThatThrownBy(() -> service.fill(living.getId(), new FillForm(thisMonth, new BigDecimal("1"))))
			.hasMessage("這一期已經填過");
		assertThatThrownBy(() -> service.fill(living.getId(), new FillForm(thisMonth.plusMonths(1), BigDecimal.TEN)))
			.hasMessage("這一期不用填");
		assertThatThrownBy(() -> service.fill(living.getId(), new FillForm(thisMonth.minusMonths(1), BigDecimal.TEN)))
			.hasMessage("這一期不用填");
		assertThatThrownBy(() -> service.fill(living.getId(), new FillForm(null, BigDecimal.TEN)))
			.hasMessage("請選擇月份");
		assertThatThrownBy(() -> service.fill(rent.getId(), new FillForm(thisMonth, BigDecimal.TEN)))
			.hasMessage("這個項目是固定金額，會自動記帳");
		RecurringItem other = livingFrom(thisMonth, "零用錢");
		assertThatThrownBy(() -> service.fill(other.getId(), new FillForm(thisMonth, BigDecimal.ZERO)))
			.hasMessage("金額要大於 0");
	}

	@Test
	void fillOtherUsersItemIsNotFound() {
		RecurringItem living = livingFrom(thisMonth);
		loginAs("ri-ben");

		assertThatThrownBy(() -> service.fill(living.getId(), new FillForm(thisMonth, BigDecimal.TEN)))
			.isInstanceOf(NotFoundException.class);
		assertThat(service.unfilled()).isEmpty();
	}

	/** 填了再作廢：那一期回到「還沒填」，提醒再出現，可以重新填（設計文件 4.2）。 */
	@Test
	void voidedManualPeriodCanBeRefilled() {
		RecurringItem living = livingFrom(thisMonth);
		CashMovement filled = service.fill(living.getId(), new FillForm(thisMonth, new BigDecimal("27000")));
		movements.voidMovement(filled.getId(), "填錯");

		assertThat(service.unfilled()).extracting(UnfilledPeriod::period).containsExactly(thisMonth);
		CashMovement again = service.fill(living.getId(), new FillForm(thisMonth, new BigDecimal("26000")));
		assertThat(again.getAmount()).isEqualByComparingTo("26000");
	}

	/** 總資產頁只提醒入帳日已經到了（≤ 今天）的期數。 */
	@Test
	void overdueRemindersOnlyPastDue() {
		assumeFalse(today.equals(thisMonth.atEndOfMonth()), "月底當天本月那一期也算到期");
		livingFrom(thisMonth.minusMonths(1));

		assertThat(service.overdueReminders()).extracting(UnfilledPeriod::period)
			.containsExactly(thisMonth.minusMonths(1));
	}

	/** 入帳日早於帳戶初始現金餘額日期的期數不用填，也不提醒。 */
	@Test
	void periodsBeforeOpeningSkipped() {
		movements.setOpening(bank, new OpeningForm(today, new BigDecimal("100000")));
		livingFrom(thisMonth.minusMonths(2));

		assertThat(service.unfilled()).extracting(UnfilledPeriod::period).containsExactly(thisMonth);
	}

	@Test
	void endedManualItemListsOnlyPeriodsUntilEnd() {
		RecurringItem living = livingFrom(thisMonth.minusMonths(2));
		service.edit(living.getId(), new RecurringItemForm("生活費", null, null, null, null, null, 31, bank, null,
				thisMonth.minusMonths(2)));

		assertThat(service.unfilled()).extracting(UnfilledPeriod::period).containsExactly(thisMonth.minusMonths(2));
	}

	@Test
	void formOfExistingItem() {
		RecurringItem item = service.create(rentForm()).item();

		RecurringItemForm form = service.form(item.getId());

		assertThat(form.amount()).isEqualByComparingTo("8000");
		assertThat(form.amount().scale()).isZero();
		assertThat(form.startMonth()).isEqualTo(thisMonth);
	}

	private int periodsBeforeThisMonth(RecurringItem item) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement WHERE recurring_item_id = ? AND period < ?",
				Integer.class, item.getId(), thisMonth.atDay(1));
	}

	private RecurringItem livingFrom(YearMonth start) {
		return livingFrom(start, "生活費");
	}

	/** 每月自己填的支出，入帳日 31 號（月底）。 */
	private RecurringItem livingFrom(YearMonth start, String name) {
		return service.create(new RecurringItemForm(name, RecurringDirection.EXPENSE, AmountMode.MANUAL, null,
				RecurringCycle.MONTHLY, null, 31, bank, start, null)).item();
	}

	private RecurringItemForm rentForm() {
		return new RecurringItemForm("孝親費", RecurringDirection.EXPENSE, AmountMode.FIXED, new BigDecimal("8000"),
				RecurringCycle.MONTHLY, null, 1, bank, thisMonth, null);
	}

	private void rejects(UnaryOperator<RecurringItemForm> change, String message) {
		assertThatThrownBy(() -> service.create(change.apply(rentForm())))
			.isInstanceOf(RecurringValidationException.class)
			.hasMessage(message);
	}

	private static RecurringItemForm name(RecurringItemForm f, String name) {
		return new RecurringItemForm(name, f.direction(), f.amountMode(), f.amount(), f.cycle(), f.anchorMonth(),
				f.dayOfMonth(), f.accountId(), f.startMonth(), f.endMonth());
	}

	private static RecurringItemForm amount(RecurringItemForm f, String amount) {
		return new RecurringItemForm(f.name(), f.direction(), f.amountMode(), amount == null ? null : new BigDecimal(amount),
				f.cycle(), f.anchorMonth(), f.dayOfMonth(), f.accountId(), f.startMonth(), f.endMonth());
	}

	private static RecurringItemForm day(RecurringItemForm f, Integer day) {
		return new RecurringItemForm(f.name(), f.direction(), f.amountMode(), f.amount(), f.cycle(), f.anchorMonth(), day,
				f.accountId(), f.startMonth(), f.endMonth());
	}

	private static RecurringItemForm cycle(RecurringItemForm f, RecurringCycle cycle, Integer anchor) {
		return new RecurringItemForm(f.name(), f.direction(), f.amountMode(), f.amount(), cycle, anchor, f.dayOfMonth(),
				f.accountId(), f.startMonth(), f.endMonth());
	}

	private static RecurringItemForm months(RecurringItemForm f, YearMonth start, YearMonth end) {
		return new RecurringItemForm(f.name(), f.direction(), f.amountMode(), f.amount(), f.cycle(), f.anchorMonth(),
				f.dayOfMonth(), f.accountId(), start, end);
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
