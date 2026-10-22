package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.sideproject.stockledger.calculator.CashEvent;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.CashMovementForm;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.ReconcileForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 修改與作廢：不硬刪除，修改前先寫修改紀錄，每個改到的欄位一列（spec 4.9、DR-033）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CashMovementRevisionTest {

	@Autowired
	private CashMovementService service;

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
	private Long bank;
	private Long settlement;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		createIfMissing("cr-amy");
		createIfMissing("cr-ben");
		loginAs("cr-amy");
		today = LocalDate.now(clock);
		bank = accounts.create(new CashAccountForm("薪資帳戶", CashAccountType.BANK_DEMAND, "國泰世華")).getId();
		settlement = accounts.create(new CashAccountForm("交割戶", CashAccountType.SETTLEMENT, "國泰證券")).getId();
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void editWritesOneRowPerChangedField() {
		Long id = deposit("62000", "薪資");

		service.edit(id, form(CashMovementType.DEPOSIT, today, bank, "61000", "十月薪資"), "金額打錯");

		List<Map<String, Object>> rows = revisions(id);
		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(r -> r.get("field")).containsExactlyInAnyOrder("amount", "note");
		assertThat(rows).allSatisfy(r -> {
			assertThat(r.get("action")).isEqualTo("EDIT");
			assertThat(r.get("reason")).isEqualTo("金額打錯");
		});
		Map<String, Object> amountRow = rows.stream().filter(r -> r.get("field").equals("amount")).findFirst().orElseThrow();
		assertThat(amountRow.get("old_value")).isEqualTo("62000");
		assertThat(amountRow.get("new_value")).isEqualTo("61000");
		assertThat(service.activeEvents()).singleElement()
			.satisfies(e -> assertThat(e.amount()).isEqualByComparingTo("61000"));
	}

	@Test
	void editWithoutChangesWritesNothing() {
		Long id = deposit("62000", "薪資");

		service.edit(id, form(CashMovementType.DEPOSIT, today, bank, "62000.00", "薪資"), null);

		assertThat(revisions(id)).isEmpty();
		assertThat(service.revisedIds()).doesNotContain(id);
	}

	@Test
	void editCannotChangeType() {
		Long id = deposit("100", null);

		assertThatThrownBy(() -> service.edit(id, form(CashMovementType.WITHDRAWAL, today, bank, "100", null), null))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void editTransferTarget() {
		Long id = service.record(new CashMovementForm(CashMovementType.TRANSFER, today, bank, settlement,
				new BigDecimal("300"), null)).getId();
		Long third = accounts.create(new CashAccountForm("第三個", CashAccountType.BANK_DEMAND, "台銀")).getId();

		service.edit(id, new CashMovementForm(CashMovementType.TRANSFER, today, bank, third, new BigDecimal("300"), null),
				null);

		assertThat(revisions(id)).extracting(r -> r.get("field")).containsExactly("toAccountId");
	}

	@Test
	void voidWritesRevisionAndExcludesFromEvents() {
		Long id = deposit("100", null);

		service.voidMovement(id, "重複記了");

		assertThat(service.activeEvents()).isEmpty();
		assertThat(revisions(id)).singleElement().satisfies(r -> {
			assertThat(r.get("action")).isEqualTo("VOID");
			assertThat(r.get("field")).isEqualTo("voided");
			assertThat(r.get("old_value")).isEqualTo("false");
			assertThat(r.get("new_value")).isEqualTo("true");
			assertThat(r.get("reason")).isEqualTo("重複記了");
		});
		assertThat(service.revisedIds()).contains(id);
		// 不硬刪除：資料還在
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement WHERE id = ?", Integer.class, id)).isEqualTo(1);
	}

	@Test
	void voidTwiceRejected() {
		Long id = deposit("100", null);
		service.voidMovement(id, null);

		assertThatThrownBy(() -> service.voidMovement(id, null)).isInstanceOf(CashValidationException.class);
	}

	@Test
	void editVoidedRejected() {
		Long id = deposit("100", null);
		service.voidMovement(id, null);

		assertThatThrownBy(() -> service.edit(id, form(CashMovementType.DEPOSIT, today, bank, "200", null), null))
			.isInstanceOf(CashValidationException.class);
	}

	/** 作廢的初始現金餘額不佔位置：作廢後可以重新設定（Review Focus #5）。 */
	@Test
	void voidedOpeningCanBeRecreated() {
		Long id = service.setOpening(bank, new OpeningForm(today.minusDays(5), new BigDecimal("1000"))).getId();

		service.voidMovement(id, "金額填錯");
		service.setOpening(bank, new OpeningForm(today.minusDays(5), new BigDecimal("2000")));

		assertThat(service.activeEvents()).singleElement()
			.satisfies(e -> assertThat(e.amount()).isEqualByComparingTo("2000"));
	}

	@Test
	void voidedReconcileFreesTheDay() {
		Long id = service.reconcile(bank, new ReconcileForm(today, new BigDecimal("900"))).getId();

		service.voidMovement(id, null);
		service.reconcile(bank, new ReconcileForm(today, new BigDecimal("800")));

		assertThat(service.activeEvents()).extracting(CashEvent::actualBalance)
			.usingElementComparator(BigDecimal::compareTo)
			.containsExactly(new BigDecimal("800"));
	}

	@Test
	void editReconcileChangesActualBalance() {
		Long id = service.reconcile(bank, new ReconcileForm(today, new BigDecimal("900"))).getId();

		service.editReconcile(id, new ReconcileForm(today, new BigDecimal("950")), "看錯數字");

		assertThat(jdbc.queryForObject("SELECT actual_balance FROM cash_movement WHERE id = ?", BigDecimal.class, id))
			.isEqualByComparingTo("950");
		assertThat(revisions(id)).extracting(r -> r.get("field")).containsExactly("actualBalance");
	}

	/** 改到另一天，但那天已經有對帳 → 不行；改回同一天（自己）→ 可以。 */
	@Test
	void editReconcileRespectsOnePerDay() {
		service.reconcile(bank, new ReconcileForm(today.minusDays(1), new BigDecimal("900")));
		Long id = service.reconcile(bank, new ReconcileForm(today, new BigDecimal("800"))).getId();

		assertThatThrownBy(() -> service.editReconcile(id, new ReconcileForm(today.minusDays(1), BigDecimal.TEN), null))
			.isInstanceOf(CashValidationException.class);
		service.editReconcile(id, new ReconcileForm(today, new BigDecimal("850")), null);
	}

	@Test
	void editOpeningChangesAmountAndDate() {
		Long id = service.setOpening(bank, new OpeningForm(today.minusDays(5), new BigDecimal("1000"))).getId();

		service.editOpening(id, new OpeningForm(today.minusDays(6), new BigDecimal("1200")), null);

		assertThat(revisions(id)).extracting(r -> r.get("field")).containsExactlyInAnyOrder("movementDate", "amount");
	}

	/** 初始現金餘額是起點：改日期時不能晚於這個帳戶其他的異動。 */
	@Test
	void editOpeningCannotMovePastOtherMovements() {
		Long id = service.setOpening(bank, new OpeningForm(today.minusDays(5), new BigDecimal("1000"))).getId();
		service.record(form(CashMovementType.DEPOSIT, today.minusDays(3), bank, "100", null));

		assertThatThrownBy(() -> service.editOpening(id, new OpeningForm(today.minusDays(2), new BigDecimal("1000")), null))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void editUsesTheRightMethodPerType() {
		Long opening = service.setOpening(bank, new OpeningForm(today.minusDays(5), new BigDecimal("1000"))).getId();
		Long deposit = deposit("100", null);

		assertThatThrownBy(() -> service.edit(opening, form(CashMovementType.OPENING, today, bank, "5", null), null))
			.isInstanceOf(CashValidationException.class);
		assertThatThrownBy(() -> service.editReconcile(deposit, new ReconcileForm(today, BigDecimal.ONE), null))
			.isInstanceOf(CashValidationException.class);
		assertThatThrownBy(() -> service.editOpening(deposit, new OpeningForm(today, BigDecimal.ONE), null))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void otherUserCannotEditOrVoid() {
		Long id = deposit("100", null);
		loginAs("cr-ben");

		assertThatThrownBy(() -> service.edit(id, form(CashMovementType.DEPOSIT, today, bank, "1", null), null))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.voidMovement(id, null)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.find(id)).isInstanceOf(NotFoundException.class);
		loginAs("cr-amy");
		assertThat(service.activeEvents()).singleElement()
			.satisfies(e -> assertThat(e.amount()).isEqualByComparingTo("100"));
		assertThat(revisions(id)).isEmpty();
	}

	@Test
	void editRevalidates() {
		Long id = deposit("100", null);

		assertThatThrownBy(() -> service.edit(id, form(CashMovementType.DEPOSIT, today.plusDays(1), bank, "100", null), null))
			.isInstanceOf(CashValidationException.class);
		assertThatThrownBy(() -> service.edit(id, form(CashMovementType.DEPOSIT, today, bank, "100.5", null), null))
			.isInstanceOf(CashValidationException.class);
		assertThat(revisions(id)).isEmpty();
	}

	/** 修改時一樣不能早於初始現金餘額；日期欄位的舊值、新值要正確（審核補測）。 */
	@Test
	void editDateRespectsOpeningAndRecordsDates() {
		service.setOpening(bank, new OpeningForm(today.minusDays(10), new BigDecimal("1000")));
		Long id = service.record(form(CashMovementType.DEPOSIT, today.minusDays(2), bank, "100", null)).getId();

		assertThatThrownBy(() -> service.edit(id, form(CashMovementType.DEPOSIT, today.minusDays(11), bank, "100", null), null))
			.isInstanceOf(CashValidationException.class);
		service.edit(id, form(CashMovementType.DEPOSIT, today.minusDays(3), bank, "100", null), null);

		assertThat(revisions(id)).singleElement().satisfies(r -> {
			assertThat(r.get("field")).isEqualTo("movementDate");
			assertThat(r.get("old_value")).isEqualTo(today.minusDays(2).toString());
			assertThat(r.get("new_value")).isEqualTo(today.minusDays(3).toString());
		});
	}

	@Test
	void editReconcileBeforeOpeningRejected() {
		service.setOpening(bank, new OpeningForm(today.minusDays(10), new BigDecimal("1000")));
		Long id = service.reconcile(bank, new ReconcileForm(today, new BigDecimal("900"))).getId();

		assertThatThrownBy(() -> service.editReconcile(id, new ReconcileForm(today.minusDays(11), BigDecimal.TEN), null))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void editTransferToOtherUsersAccountIsNotFound() {
		Long id = service.record(new CashMovementForm(CashMovementType.TRANSFER, today, bank, settlement,
				new BigDecimal("300"), null)).getId();
		loginAs("cr-ben");
		Long benAccount = accounts.create(new CashAccountForm("班的帳戶", CashAccountType.BANK_DEMAND, "台銀")).getId();
		loginAs("cr-amy");

		assertThatThrownBy(() -> service.edit(id, new CashMovementForm(CashMovementType.TRANSFER, today, bank, benAccount,
				new BigDecimal("300"), null), null)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.edit(id, new CashMovementForm(CashMovementType.TRANSFER, today, bank, bank,
				new BigDecimal("300"), null), null)).isInstanceOf(CashValidationException.class);
		assertThat(revisions(id)).isEmpty();
	}

	@Test
	void reasonTooLongRejected() {
		Long id = deposit("100", null);

		assertThatThrownBy(() -> service.voidMovement(id, "長".repeat(201))).isInstanceOf(CashValidationException.class);
	}

	private Long deposit(String amount, String note) {
		return service.record(form(CashMovementType.DEPOSIT, today, bank, amount, note)).getId();
	}

	/** account 是錢動到的帳戶：存入時是轉入帳戶，支出時是轉出帳戶。 */
	private static CashMovementForm form(CashMovementType type, LocalDate date, Long account, String amount, String note) {
		return type == CashMovementType.DEPOSIT
				? new CashMovementForm(type, date, null, account, new BigDecimal(amount), note)
				: new CashMovementForm(type, date, account, null, new BigDecimal(amount), note);
	}

	private List<Map<String, Object>> revisions(Long id) {
		return jdbc.queryForList("SELECT action, field, old_value, new_value, reason FROM cash_movement_revision "
				+ "WHERE cash_movement_id = ? ORDER BY id", id);
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
