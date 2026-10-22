package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

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
import com.sideproject.stockledger.entity.AmountMode;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.RecurringCycle;
import com.sideproject.stockledger.entity.RecurringDirection;
import com.sideproject.stockledger.entity.RecurringItem;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.repository.RecurringItemRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/**
 * 固定收支自動記帳（設計文件 4.2、DR-036）。項目直接用 repository 建立，避免新增時的補記干擾；
 * 用 {@code postDue(item, today)} 指定「今天」，結果才不受執行測試的日期影響。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RecurringPostingServiceTest {

	@Autowired
	private RecurringPostingService posting;

	@Autowired
	private RecurringItemRepository items;

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

	private Long amyId;
	private Long bank;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		amyId = createIfMissing("rp-amy");
		createIfMissing("rp-ben");
		loginAs("rp-amy");
		bank = accounts.create(new CashAccountForm("薪資帳戶", CashAccountType.BANK_DEMAND, "國泰世華")).getId();
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void postsDuePeriodsAsMovements() {
		RecurringItem salary = fixed(amyId, bank, "薪水", RecurringDirection.INCOME, "62000", 5, "2026-08", null);

		int created = posting.postDue(salary, LocalDate.parse("2026-10-04"));

		assertThat(created).isEqualTo(2);
		assertThat(rows(salary)).extracting(r -> r.get("movement_date").toString(), r -> r.get("period").toString(),
				r -> r.get("type"), r -> r.get("note"))
			.containsExactly(
					org.assertj.core.groups.Tuple.tuple("2026-08-05", "2026-08-01", "DEPOSIT", "薪水"),
					org.assertj.core.groups.Tuple.tuple("2026-09-05", "2026-09-01", "DEPOSIT", "薪水"));
	}

	/** 啟動補記與 00:10 排程重疊時，同一期只會有一筆。 */
	@Test
	void postingTwiceCreatesOnce() {
		RecurringItem rent = fixed(amyId, bank, "孝親費", RecurringDirection.EXPENSE, "8000", 5, "2026-08", null);

		posting.postDue(rent, LocalDate.parse("2026-10-05"));
		int second = posting.postDue(rent, LocalDate.parse("2026-10-05"));

		assertThat(second).isZero();
		assertThat(rows(rent)).hasSize(3).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("WITHDRAWAL"));
	}

	/** 固定金額的那期作廢後不再自動補，避免作廢後又冒出來。 */
	@Test
	void voidedFixedPeriodNotRegenerated() {
		RecurringItem rent = fixed(amyId, bank, "孝親費", RecurringDirection.EXPENSE, "8000", 5, "2026-10", null);
		posting.postDue(rent, LocalDate.parse("2026-10-05"));
		Long id = (Long) rows(rent).get(0).get("id");
		movements.voidMovement(id, "這個月沒給");

		int again = posting.postDue(rent, LocalDate.parse("2026-10-06"));

		assertThat(again).isZero();
		assertThat(rows(rent)).hasSize(1);
	}

	@Test
	void manualItemsNotPosted() {
		RecurringItem living = items.save(RecurringItem.create(amyId, "生活費", RecurringDirection.EXPENSE,
				AmountMode.MANUAL, null, RecurringCycle.MONTHLY, null, 31, bank, YearMonth.of(2026, 8), null,
				LocalDateTime.now(clock)));

		assertThat(posting.postDue(living, LocalDate.parse("2026-10-31"))).isZero();
		assertThat(rows(living)).isEmpty();
	}

	/** 早於初始現金餘額日期的期數略過；結束月份之後不產生。 */
	@Test
	void backfillSkipsBeforeOpeningAndAfterEnd() {
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, voided, created_at) "
				+ "VALUES (?, 'OPENING', '2026-08-10', ?, 1000, FALSE, NOW())", amyId, bank);
		RecurringItem gym = fixed(amyId, bank, "健身房", RecurringDirection.EXPENSE, "1500", 5, "2026-07", "2026-09");

		posting.postDue(gym, LocalDate.parse("2026-12-31"));

		assertThat(rows(gym)).extracting(r -> r.get("period").toString()).containsExactly("2026-09-01");
	}

	@Test
	void neverPostsFutureDates() {
		RecurringItem salary = fixed(amyId, bank, "薪水", RecurringDirection.INCOME, "62000", 25, "2026-10", null);

		assertThat(posting.postDue(salary, LocalDate.parse("2026-10-24"))).isZero();
		assertThat(posting.postDue(salary, LocalDate.parse("2026-10-25"))).isEqualTo(1);
	}

	/** 改了項目之後產生的期數用新的設定；已經記好的不變。 */
	@Test
	void laterPeriodsUseCurrentSettings() {
		RecurringItem rent = fixed(amyId, bank, "孝親費", RecurringDirection.EXPENSE, "8000", 5, "2026-09", null);
		posting.postDue(rent, LocalDate.parse("2026-09-30"));
		rent.change("孝親費", new BigDecimal("9000"), null, 10, bank, null, YearMonth.of(2026, 10));
		items.save(rent);

		posting.postDue(rent, LocalDate.parse("2026-10-10"));

		assertThat(rows(rent)).extracting(r -> r.get("movement_date").toString(),
				r -> ((BigDecimal) r.get("amount")).stripTrailingZeros().toPlainString())
			.containsExactly(org.assertj.core.groups.Tuple.tuple("2026-09-05", "8000"),
					org.assertj.core.groups.Tuple.tuple("2026-10-10", "9000"));
	}

	/** 排程不是使用者操作：沒有登入者也能跑，替每個使用者記到他自己的帳戶（DR-036）。 */
	@Test
	void postsForEveryUserIntoOwnAccounts() {
		YearMonth lastMonth = YearMonth.now(clock).minusMonths(1);
		fixed(amyId, bank, "薪水", RecurringDirection.INCOME, "62000", 1, lastMonth.toString(), null);
		loginAs("rp-ben");
		Long benId = users.findByUsername("rp-ben").orElseThrow().getId();
		Long benBank = accounts.create(new CashAccountForm("班的帳戶", CashAccountType.BANK_DEMAND, "台銀")).getId();
		fixed(benId, benBank, "房租", RecurringDirection.EXPENSE, "15000", 1, lastMonth.toString(), null);
		SecurityContextHolder.clearContext();

		int created = posting.postDue();

		// 兩人各兩期：上個月 1 號、本月 1 號
		assertThat(created).isEqualTo(4);
		assertThat(jdbc.queryForList("SELECT DISTINCT user_id, account_id FROM cash_movement ORDER BY user_id"))
			.extracting(r -> r.get("user_id"), r -> r.get("account_id"))
			.containsExactly(org.assertj.core.groups.Tuple.tuple(amyId, bank),
					org.assertj.core.groups.Tuple.tuple(benId, benBank));
	}

	/** 一個項目出錯不影響其他項目（每個項目各自一個交易）。 */
	@Test
	void oneBrokenItemDoesNotStopOthers() {
		YearMonth lastMonth = YearMonth.now(clock).minusMonths(1);
		RecurringItem first = fixed(amyId, bank, "壞掉的", RecurringDirection.EXPENSE, "100", 1, lastMonth.toString(),
				null);
		RecurringItem second = fixed(amyId, bank, "正常的", RecurringDirection.EXPENSE, "200", 1, lastMonth.toString(),
				null);
		// 加一條測試用的 CHECK，讓第一個項目寫入時被資料庫擋下
		jdbc.update("UPDATE recurring_item SET name = ? WHERE id = ?", "x".repeat(50), first.getId());
		jdbc.update("ALTER TABLE cash_movement ADD CONSTRAINT ck_test_broken CHECK (note IS NULL OR note <> ?)",
				"x".repeat(50));
		try {
			posting.postDue();
		}
		finally {
			jdbc.update("ALTER TABLE cash_movement DROP CHECK ck_test_broken");
		}

		assertThat(rows(first)).isEmpty();
		assertThat(rows(second)).isNotEmpty();
	}

	private RecurringItem fixed(Long userId, Long account, String name, RecurringDirection direction, String amount,
			int day, String start, String end) {
		return items.save(RecurringItem.create(userId, name, direction, AmountMode.FIXED, new BigDecimal(amount),
				RecurringCycle.MONTHLY, null, day, account, YearMonth.parse(start),
				end == null ? null : YearMonth.parse(end), LocalDateTime.now(clock)));
	}

	private List<Map<String, Object>> rows(RecurringItem item) {
		return jdbc.queryForList("SELECT id, movement_date, period, type, note, amount FROM cash_movement "
				+ "WHERE recurring_item_id = ? ORDER BY period", item.getId());
	}

	private void loginAs(String username) {
		UserDetails details = userDetailsService.loadUserByUsername(username);
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	private Long createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
		return users.findByUsername(username).orElseThrow().getId();
	}

}
