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
import com.sideproject.stockledger.dto.RateRow;
import com.sideproject.stockledger.dto.RateSettingForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.RateKind;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 費率設定（spec 4.5、5）。法定費率來自真的 V4 seed，不是測試自己塞的資料。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RateSettingServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

	@Autowired
	private RateSettingService service;

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
		// 只清掉個人版本；seed 的法定費率與全域預設要保留
		jdbc.update("DELETE FROM rate_setting WHERE user_id IS NOT NULL");
		createIfMissing("amy");
		createIfMissing("ben");
		loginAs("amy");
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	// ---------- 法定費率（V4 seed） ----------

	@Test
	void nhiRateToday() {
		assertThat(effective(RateKind.NHI_SUPPLEMENT_RATE, TODAY)).isEqualByComparingTo("0.0211");
	}

	@Test
	void nhiRateHistory() {
		assertThat(effective(RateKind.NHI_SUPPLEMENT_RATE, LocalDate.of(2015, 12, 31))).isEqualByComparingTo("0.02");
		assertThat(effective(RateKind.NHI_SUPPLEMENT_RATE, LocalDate.of(2016, 1, 1))).isEqualByComparingTo("0.0191");
		assertThat(effective(RateKind.NHI_SUPPLEMENT_RATE, LocalDate.of(2020, 12, 31))).isEqualByComparingTo("0.0191");
	}

	@Test
	void nhiThreshold() {
		assertThat(effective(RateKind.NHI_SUPPLEMENT_THRESHOLD, TODAY)).isEqualByComparingTo("20000");
		assertThat(effective(RateKind.NHI_SUPPLEMENT_THRESHOLD, LocalDate.of(2015, 12, 31)))
			.isEqualByComparingTo("5000");
	}

	@Test
	void dayTradeHalfRateEndsAfter2027() {
		assertThat(effective(RateKind.SECURITIES_TAX_DAY_TRADE, LocalDate.of(2017, 4, 27))).isEqualByComparingTo("0.003");
		assertThat(effective(RateKind.SECURITIES_TAX_DAY_TRADE, LocalDate.of(2017, 4, 28))).isEqualByComparingTo("0.0015");
		assertThat(effective(RateKind.SECURITIES_TAX_DAY_TRADE, LocalDate.of(2027, 12, 31))).isEqualByComparingTo("0.0015");
		assertThat(effective(RateKind.SECURITIES_TAX_DAY_TRADE, LocalDate.of(2028, 1, 1))).isEqualByComparingTo("0.003");
	}

	@Test
	void bondEtfExemptUntil2026() {
		assertThat(effective(RateKind.SECURITIES_TAX_BOND_ETF, LocalDate.of(2016, 12, 31))).isEqualByComparingTo("0.001");
		assertThat(effective(RateKind.SECURITIES_TAX_BOND_ETF, LocalDate.of(2026, 12, 31))).isEqualByComparingTo("0");
		assertThat(effective(RateKind.SECURITIES_TAX_BOND_ETF, LocalDate.of(2027, 1, 1))).isEqualByComparingTo("0.001");
	}

	@Test
	void stockAndEtfTaxAndFeeCap() {
		assertThat(effective(RateKind.SECURITIES_TAX_STOCK, TODAY)).isEqualByComparingTo("0.003");
		assertThat(effective(RateKind.SECURITIES_TAX_ETF, TODAY)).isEqualByComparingTo("0.001");
		assertThat(effective(RateKind.BROKERAGE_FEE_RATE, TODAY)).isEqualByComparingTo("0.001425");
	}

	/** 法定費率所有人共用：就算資料庫裡出現某個使用者的法定費率列（壞資料），也不能被拿來用。 */
	@Test
	void userRowForLegalKindIsIgnored() {
		jdbc.update("INSERT INTO rate_setting (user_id, kind, value, effective_date, created_at) "
				+ "VALUES (?, 'SECURITIES_TAX_STOCK', 0.5, '2026-01-01', NOW())", idOf("amy"));

		assertThat(effective(RateKind.SECURITIES_TAX_STOCK, TODAY)).isEqualByComparingTo("0.003");
	}

	// ---------- 個人費率 ----------

	@Test
	void minFeeDefaultsThenPersonal() {
		assertThat(effective(RateKind.MIN_FEE_BOARD_LOT, TODAY)).isEqualByComparingTo("20");

		service.addPersonalVersion(form(RateKind.MIN_FEE_BOARD_LOT, "1", LocalDate.of(2026, 10, 1)));

		assertThat(effective(RateKind.MIN_FEE_BOARD_LOT, TODAY)).isEqualByComparingTo("1");
		loginAs("ben");
		assertThat(effective(RateKind.MIN_FEE_BOARD_LOT, TODAY)).isEqualByComparingTo("20");
	}

	@Test
	void discountUnsetIsEmpty() {
		assertThat(service.findEffective(RateKind.BROKERAGE_DISCOUNT, TODAY)).isEmpty();
	}

	/** 畫面輸入的是百分比：2.8 折填 28，存成 0.280000。 */
	@Test
	void percentInputConverted() {
		service.addPersonalVersion(form(RateKind.BROKERAGE_DISCOUNT, "28", LocalDate.of(2026, 10, 1)));

		assertThat(jdbc.queryForObject("SELECT value FROM rate_setting WHERE kind = 'BROKERAGE_DISCOUNT' AND user_id = ?",
				BigDecimal.class, idOf("amy"))).isEqualTo(new BigDecimal("0.280000"));
	}

	@Test
	void cannotAddLegalKind() {
		assertThatThrownBy(() -> service.addPersonalVersion(
				form(RateKind.SECURITIES_TAX_STOCK, "0.1", LocalDate.of(2026, 10, 1))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(personalRowCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-5", "150", "100.0001" })
	void discountOutOfRangeRejected(String input) {
		assertThatThrownBy(() -> service.addPersonalVersion(
				form(RateKind.BROKERAGE_DISCOUNT, input, LocalDate.of(2026, 10, 1))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(personalRowCount()).isZero();
	}

	@Test
	void discountOf100IsAllowed() {
		service.addPersonalVersion(form(RateKind.BROKERAGE_DISCOUNT, "100", LocalDate.of(2026, 10, 1)));

		assertThat(effective(RateKind.BROKERAGE_DISCOUNT, TODAY)).isEqualByComparingTo("1");
	}

	@ParameterizedTest
	@ValueSource(strings = { "-1", "1.5", "1e2147483647" })
	void minFeeMustBeWholeNonNegative(String input) {
		assertThatThrownBy(() -> service.addPersonalVersion(
				form(RateKind.MIN_FEE_ODD_LOT, input, LocalDate.of(2026, 10, 1))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(personalRowCount()).isZero();
	}

	@Test
	void minFeeZeroAndTrailingZerosAllowed() {
		service.addPersonalVersion(form(RateKind.MIN_FEE_ODD_LOT, "0", LocalDate.of(2026, 10, 1)));
		service.addPersonalVersion(form(RateKind.MIN_FEE_BOARD_LOT, "20.00", LocalDate.of(2026, 10, 1)));

		assertThat(personalRowCount()).isEqualTo(2);
	}

	@Test
	void futurePersonalRateNotYetApplied() {
		service.addPersonalVersion(form(RateKind.MIN_FEE_BOARD_LOT, "1", LocalDate.of(2026, 12, 1)));

		assertThat(effective(RateKind.MIN_FEE_BOARD_LOT, TODAY)).isEqualByComparingTo("20");
		assertThat(effective(RateKind.MIN_FEE_BOARD_LOT, LocalDate.of(2026, 12, 1))).isEqualByComparingTo("1");
	}

	@ParameterizedTest
	@ValueSource(strings = { "1999-12-31", "+10000-01-01" })
	void effectiveDateOutOfRangeRejected(String date) {
		assertThatThrownBy(() -> service.addPersonalVersion(
				form(RateKind.MIN_FEE_BOARD_LOT, "1", LocalDate.parse(date))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// ---------- 設定頁表格 ----------

	@Test
	void tableShowsUnsetLabel() {
		assertThat(row(RateKind.BROKERAGE_DISCOUNT).displayValue()).isEqualTo("未設定");
		assertThat(row(RateKind.BROKERAGE_DISCOUNT).sourceLabel()).isEqualTo("—");
	}

	@Test
	void tableFormatsRatioAsPercentAndAmountAsDollars() {
		assertThat(row(RateKind.BROKERAGE_FEE_RATE).displayValue()).isEqualTo("0.1425%");
		assertThat(row(RateKind.NHI_SUPPLEMENT_THRESHOLD).displayValue()).isEqualTo("20,000 元");
		assertThat(row(RateKind.MIN_FEE_BOARD_LOT).displayValue()).isEqualTo("20 元");
	}

	/** 折扣存成比率 0.28，畫面同時顯示台灣習慣的「2.8 折」，避免誤讀成「打 28% 折」。 */
	@Test
	void discountShownWithZhe() {
		service.addPersonalVersion(form(RateKind.BROKERAGE_DISCOUNT, "28", LocalDate.of(2026, 10, 1)));

		assertThat(row(RateKind.BROKERAGE_DISCOUNT).displayValue()).isEqualTo("28%（2.8 折）");
	}

	/** 歷史版本要顯示備註；法定費率的法規出處寫在 seed 的備註裡。 */
	@Test
	void historyShowsNote() {
		service.addPersonalVersion(new RateSettingForm(RateKind.MIN_FEE_BOARD_LOT, new BigDecimal("1"),
				LocalDate.of(2026, 10, 1), "　國泰電子下單優惠　"));

		assertThat(row(RateKind.MIN_FEE_BOARD_LOT).history().get(0).note()).isEqualTo("國泰電子下單優惠");
		assertThat(row(RateKind.NHI_SUPPLEMENT_RATE).history().get(0).note()).contains("2.11%");
	}

	/** Service 自己也要擋超過 4 位小數，不能靜悄悄四捨五入（之後的 @RestController 不一定有表單檢查）。 */
	@Test
	void tooManyDecimalsRejectedByService() {
		assertThatThrownBy(() -> service.addPersonalVersion(
				form(RateKind.BROKERAGE_DISCOUNT, "28.12345", LocalDate.of(2026, 10, 1))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(personalRowCount()).isZero();
	}

	@Test
	void missingValueOrDateRejectedByService() {
		assertThatThrownBy(() -> service.addPersonalVersion(
				new RateSettingForm(RateKind.MIN_FEE_BOARD_LOT, null, LocalDate.of(2026, 10, 1), null)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.addPersonalVersion(
				new RateSettingForm(RateKind.MIN_FEE_BOARD_LOT, BigDecimal.ONE, null, null)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void tableShowsSourceLabels() {
		assertThat(row(RateKind.BROKERAGE_FEE_RATE).sourceLabel()).isEqualTo("法定");
		assertThat(row(RateKind.MIN_FEE_BOARD_LOT).sourceLabel()).isEqualTo("預設");

		service.addPersonalVersion(form(RateKind.MIN_FEE_BOARD_LOT, "1", LocalDate.of(2026, 10, 1)));

		assertThat(row(RateKind.MIN_FEE_BOARD_LOT).sourceLabel()).isEqualTo("個人");
		assertThat(row(RateKind.MIN_FEE_BOARD_LOT).displayValue()).isEqualTo("1 元");
	}

	/** 歷史版本：新的在上面，包含還沒生效的版本，但看不到別人的。 */
	@Test
	void historyNewestFirstAndOnlyMine() {
		service.addPersonalVersion(form(RateKind.MIN_FEE_BOARD_LOT, "1", LocalDate.of(2026, 10, 1)));
		loginAs("ben");
		service.addPersonalVersion(form(RateKind.MIN_FEE_BOARD_LOT, "5", LocalDate.of(2026, 9, 1)));
		loginAs("amy");

		assertThat(row(RateKind.MIN_FEE_BOARD_LOT).history())
			.extracting(RateRow::displayValue)
			.containsExactly("1 元", "20 元");
		assertThat(row(RateKind.SECURITIES_TAX_DAY_TRADE).history())
			.extracting(RateRow::effectiveDate)
			.containsExactly(LocalDate.of(2028, 1, 1), LocalDate.of(2017, 4, 28), LocalDate.of(2000, 1, 1));
	}

	private BigDecimal effective(RateKind kind, LocalDate on) {
		return service.findEffective(kind, on).orElseThrow();
	}

	private RateRow row(RateKind kind) {
		return service.currentTable().stream().filter(r -> r.kind() == kind).findFirst().orElseThrow();
	}

	private static RateSettingForm form(RateKind kind, String value, LocalDate date) {
		return new RateSettingForm(kind, new BigDecimal(value), date, null);
	}

	private int personalRowCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM rate_setting WHERE user_id IS NOT NULL", Integer.class);
	}

	private void loginAs(String username) {
		UserDetails details = userDetailsService.loadUserByUsername(username);
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	private Long idOf(String username) {
		return users.findByUsername(username).orElseThrow().getId();
	}

	private void createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
	}

}
