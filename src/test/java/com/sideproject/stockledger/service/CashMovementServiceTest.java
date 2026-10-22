package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 記一筆現金異動、初始現金餘額、輸入實際餘額（spec 4.6、DR-032）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CashMovementServiceTest {

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
	private Long settlement;
	private Long bank;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		createIfMissing("cm-amy");
		createIfMissing("cm-ben");
		loginAs("cm-amy");
		today = LocalDate.now(clock);
		settlement = accounts.create(new CashAccountForm("交割戶", CashAccountType.SETTLEMENT, "國泰證券")).getId();
		bank = accounts.create(new CashAccountForm("薪資帳戶", CashAccountType.BANK_DEMAND, "國泰世華")).getId();
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void depositWithdrawalTransferAreRecorded() {
		service.record(form(CashMovementType.DEPOSIT, today, bank, null, "62000", "薪資入帳"));
		service.record(form(CashMovementType.WITHDRAWAL, today, bank, null, "2000", null));
		service.record(form(CashMovementType.TRANSFER, today, bank, settlement, "30000", "轉去交割戶"));

		assertThat(service.activeEvents()).extracting(CashEvent::type)
			.containsExactlyInAnyOrder(CashMovementType.DEPOSIT, CashMovementType.WITHDRAWAL, CashMovementType.TRANSFER);
		assertThat(jdbc.queryForObject("SELECT note FROM cash_movement WHERE type = 'DEPOSIT'", String.class))
			.isEqualTo("薪資入帳");
	}

	@Test
	void recordOnlyAcceptsDepositWithdrawalTransfer() {
		assertThatThrownBy(() -> service.record(form(CashMovementType.OPENING, today, bank, null, "100", null)))
			.isInstanceOf(CashValidationException.class);
		assertThatThrownBy(() -> service.record(form(CashMovementType.RECONCILE, today, bank, null, "100", null)))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void openingOncePerAccount() {
		service.setOpening(bank, new OpeningForm(today.minusDays(10), new BigDecimal("100000")));

		assertThatThrownBy(() -> service.setOpening(bank, new OpeningForm(today, new BigDecimal("5"))))
			.isInstanceOf(CashValidationException.class)
			.hasMessage("這個帳戶已經有初始現金餘額");
	}

	@Test
	void openingZeroAllowed() {
		CashMovement opening = service.setOpening(bank, new OpeningForm(today, BigDecimal.ZERO));

		assertThat(opening.getAmount()).isEqualByComparingTo("0");
	}

	/** 已經有比初始現金餘額更早的異動時，不能把初始現金餘額設在它們之後。 */
	@Test
	void openingAfterExistingMovementRejected() {
		service.record(form(CashMovementType.DEPOSIT, today.minusDays(5), bank, null, "100", null));

		assertThatThrownBy(() -> service.setOpening(bank, new OpeningForm(today, new BigDecimal("1000"))))
			.isInstanceOf(CashValidationException.class);
	}

	/** 轉入這個帳戶的互轉、對帳也算這個帳戶的異動（審核補測）。 */
	@Test
	void openingAfterIncomingTransferOrReconcileRejected() {
		service.record(form(CashMovementType.TRANSFER, today.minusDays(5), settlement, bank, "100", null));
		service.reconcile(settlement, new ReconcileForm(today.minusDays(6), BigDecimal.TEN));

		assertThatThrownBy(() -> service.setOpening(bank, new OpeningForm(today, new BigDecimal("1000"))))
			.isInstanceOf(CashValidationException.class);
		assertThatThrownBy(() -> service.setOpening(settlement, new OpeningForm(today, new BigDecimal("1000"))))
			.isInstanceOf(CashValidationException.class);
	}

	/** 不經過表單檢查時（之後的 REST API），超過 100 字的備註也要是中文錯誤，不是 500（審核 Minor）。 */
	@Test
	void longNoteRejectedByService() {
		assertThatThrownBy(() -> service.record(form(CashMovementType.DEPOSIT, today, bank, null, "100", "長".repeat(101))))
			.isInstanceOf(CashValidationException.class)
			.hasMessage("備註最多 100 個字");
	}

	/** 存入只選轉入帳戶；資料表照舊存在 account_id（錢動到的帳戶），to_account_id 只有互轉用（DR-035）。 */
	@Test
	void depositUsesToAccountOnly() {
		Long id = service.record(new CashMovementForm(CashMovementType.DEPOSIT, today, null, bank,
				new BigDecimal("62000"), null)).getId();

		assertThat(jdbc.queryForMap("SELECT account_id, to_account_id FROM cash_movement WHERE id = ?", id))
			.containsEntry("account_id", bank)
			.containsEntry("to_account_id", null);
	}

	/** 沒有 JavaScript 時兩個帳戶欄位都會顯示：選了這個類型用不到的欄位要提醒，而不是默默忽略。 */
	@Test
	void unusedAccountFieldRejected() {
		assertThatThrownBy(() -> service.record(new CashMovementForm(CashMovementType.DEPOSIT, today, settlement, bank,
				BigDecimal.TEN, null))).isInstanceOf(CashValidationException.class).hasMessage("存入不用選轉出帳戶");
		assertThatThrownBy(() -> service.record(new CashMovementForm(CashMovementType.WITHDRAWAL, today, bank,
				settlement, BigDecimal.TEN, null))).isInstanceOf(CashValidationException.class)
			.hasMessage("支出不用選轉入帳戶");
	}

	@Test
	void requiredAccountFieldByType() {
		assertThatThrownBy(() -> service.record(new CashMovementForm(CashMovementType.DEPOSIT, today, null, null,
				BigDecimal.TEN, null))).isInstanceOf(CashValidationException.class).hasMessage("請選擇轉入帳戶");
		assertThatThrownBy(() -> service.record(new CashMovementForm(CashMovementType.WITHDRAWAL, today, null, null,
				BigDecimal.TEN, null))).isInstanceOf(CashValidationException.class).hasMessage("請選擇轉出帳戶");
		assertThatThrownBy(() -> service.record(new CashMovementForm(CashMovementType.TRANSFER, today, null, bank,
				BigDecimal.TEN, null))).isInstanceOf(CashValidationException.class).hasMessage("請選擇轉出帳戶");
		assertThatThrownBy(() -> service.record(new CashMovementForm(CashMovementType.TRANSFER, today, bank, null,
				BigDecimal.TEN, null))).isInstanceOf(CashValidationException.class).hasMessage("請選擇轉入帳戶");
	}

	/** 修改畫面要把帳戶帶到對的欄位：存入是轉入帳戶、支出是轉出帳戶、互轉兩個都有。 */
	@Test
	void editFormPrefillsAccountsByType() {
		Long deposit = service.record(form(CashMovementType.DEPOSIT, today, bank, null, "100", null)).getId();
		Long withdrawal = service.record(form(CashMovementType.WITHDRAWAL, today, bank, null, "50", null)).getId();
		Long transfer = service.record(form(CashMovementType.TRANSFER, today, bank, settlement, "30", null)).getId();

		assertThat(service.movementForm(deposit)).extracting(CashMovementForm::fromAccountId,
				CashMovementForm::toAccountId).containsExactly(null, bank);
		assertThat(service.movementForm(withdrawal)).extracting(CashMovementForm::fromAccountId,
				CashMovementForm::toAccountId).containsExactly(bank, null);
		assertThat(service.movementForm(transfer)).extracting(CashMovementForm::fromAccountId,
				CashMovementForm::toAccountId).containsExactly(bank, settlement);
	}

	@Test
	void nullFieldsRejectedWithoutBeanValidation() {
		assertThatThrownBy(() -> service.record(new CashMovementForm(CashMovementType.DEPOSIT, null, null, bank,
				BigDecimal.TEN, null))).isInstanceOf(CashValidationException.class);
		assertThatThrownBy(() -> service.record(new CashMovementForm(CashMovementType.DEPOSIT, today, null, null,
				BigDecimal.TEN, null))).isInstanceOf(CashValidationException.class);
		assertThatThrownBy(() -> service.record(new CashMovementForm(null, today, bank, null, BigDecimal.TEN, null)))
			.isInstanceOf(CashValidationException.class);
		assertThatThrownBy(() -> service.reconcile(bank, new ReconcileForm(today, null)))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void reconcileStoresActualBalance() {
		service.reconcile(bank, new ReconcileForm(today, new BigDecimal("900")));

		assertThat(jdbc.queryForObject("SELECT actual_balance FROM cash_movement WHERE type = 'RECONCILE'",
				BigDecimal.class)).isEqualByComparingTo("900");
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE type = 'RECONCILE'",
				BigDecimal.class)).isNull();
	}

	/** 帳戶透支時實際餘額可以是負數。 */
	@Test
	void reconcileAllowsNegativeActualBalance() {
		service.reconcile(bank, new ReconcileForm(today, new BigDecimal("-500")));

		assertThat(service.activeEvents()).singleElement()
			.satisfies(e -> assertThat(e.actualBalance()).isEqualByComparingTo("-500"));
	}

	@Test
	void oneReconcilePerAccountPerDay() {
		service.reconcile(bank, new ReconcileForm(today, new BigDecimal("900")));

		assertThatThrownBy(() -> service.reconcile(bank, new ReconcileForm(today, new BigDecimal("800"))))
			.isInstanceOf(CashValidationException.class)
			.hasMessage("這個帳戶這一天已經輸入過實際餘額");
		service.reconcile(settlement, new ReconcileForm(today, new BigDecimal("800")));
	}

	@Test
	void futureDateRejected() {
		assertThatThrownBy(() -> service.record(form(CashMovementType.DEPOSIT, today.plusDays(1), bank, null, "100", null)))
			.isInstanceOf(CashValidationException.class)
			.hasMessage("日期不能晚於今天");
		assertThatThrownBy(() -> service.reconcile(bank, new ReconcileForm(today.plusDays(1), BigDecimal.ONE)))
			.isInstanceOf(CashValidationException.class);
		assertThatThrownBy(() -> service.setOpening(bank, new OpeningForm(today.plusDays(1), BigDecimal.ONE)))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void dateBeforeOpeningRejected() {
		service.setOpening(bank, new OpeningForm(today.minusDays(10), new BigDecimal("1000")));

		assertThatThrownBy(() -> service.record(form(CashMovementType.DEPOSIT, today.minusDays(11), bank, null, "100", null)))
			.isInstanceOf(CashValidationException.class)
			.hasMessageContaining("初始現金餘額");
		assertThatThrownBy(() -> service.reconcile(bank, new ReconcileForm(today.minusDays(11), BigDecimal.ONE)))
			.isInstanceOf(CashValidationException.class);
		// 互轉的轉入帳戶也要檢查
		assertThatThrownBy(() -> service.record(form(CashMovementType.TRANSFER, today.minusDays(11), settlement, bank, "100", null)))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void dateBefore2000Rejected() {
		assertThatThrownBy(() -> service.record(form(CashMovementType.DEPOSIT, LocalDate.of(1999, 12, 31), bank, null, "100", null)))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void transferToSameAccountRejected() {
		assertThatThrownBy(() -> service.record(form(CashMovementType.TRANSFER, today, bank, bank, "100", null)))
			.isInstanceOf(CashValidationException.class)
			.hasMessage("轉出和轉入不能是同一個帳戶");
	}

	@Test
	void transferWithoutTargetRejected() {
		assertThatThrownBy(() -> service.record(form(CashMovementType.TRANSFER, today, bank, null, "100", null)))
			.isInstanceOf(CashValidationException.class)
			.hasMessage("請選擇轉入帳戶");
	}

	@Test
	void otherUsersAccountIsNotFound() {
		Long amyBank = bank;
		loginAs("cm-ben");
		Long benAccount = accounts.create(new CashAccountForm("班的帳戶", CashAccountType.BANK_DEMAND, "台銀")).getId();

		assertThatThrownBy(() -> service.record(form(CashMovementType.DEPOSIT, today, amyBank, null, "100", null)))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.record(form(CashMovementType.TRANSFER, today, benAccount, amyBank, "100", null)))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.setOpening(amyBank, new OpeningForm(today, BigDecimal.ONE)))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.reconcile(amyBank, new ReconcileForm(today, BigDecimal.ONE)))
			.isInstanceOf(NotFoundException.class);
		assertThat(movementCount()).isZero();
	}

	@Test
	void otherUsersEventsNotVisible() {
		service.record(form(CashMovementType.DEPOSIT, today, bank, null, "100", null));
		loginAs("cm-ben");

		assertThat(service.activeEvents()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1", "100.5", "1e2147483647", "10000000000000" })
	void invalidAmountsRejected(String amount) {
		assertThatThrownBy(() -> service.record(form(CashMovementType.DEPOSIT, today, bank, null, amount, null)))
			.isInstanceOf(CashValidationException.class);
		assertThat(movementCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "-1", "100.5", "1e2147483647" })
	void invalidOpeningAmountsRejected(String amount) {
		assertThatThrownBy(() -> service.setOpening(bank, new OpeningForm(today, new BigDecimal(amount))))
			.isInstanceOf(CashValidationException.class);
	}

	@Test
	void trailingZerosAreWholeDollars() {
		service.record(form(CashMovementType.DEPOSIT, today, bank, null, "100.00", null));

		assertThat(movementCount()).isEqualTo(1);
	}

	@Test
	void noteIsStrippedAndBlankBecomesNull() {
		service.record(form(CashMovementType.DEPOSIT, today, bank, null, "100", "　 "));

		assertThat(jdbc.queryForObject("SELECT note FROM cash_movement", String.class)).isNull();
	}

	/** account 是錢動到的帳戶：存入時是轉入帳戶，支出與互轉時是轉出帳戶；to 只有互轉用。 */
	private static CashMovementForm form(CashMovementType type, LocalDate date, Long account, Long to, String amount,
			String note) {
		return type == CashMovementType.DEPOSIT
				? new CashMovementForm(type, date, null, account, new BigDecimal(amount), note)
				: new CashMovementForm(type, date, account, to, new BigDecimal(amount), note);
	}

	private int movementCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement", Integer.class);
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
