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

import com.sideproject.stockledger.FakeTwseConfig;
import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.NewPledgeLine;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.RepaymentMethod;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 新增借款時記質押股票（設計文件 2026-11-04-pledged-stocks 4.1、6）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestcontainersConfig.class, FakeTwseConfig.class })
class PledgedLoanCreateTest {

	private static final LocalDate PLEDGE_DAY = LocalDate.of(2026, 7, 28);

	@Autowired
	private LoanService loans;

	@Autowired
	private TradeService trades;

	@Autowired
	private CashAccountService accounts;

	@Autowired
	private FakeTwseConfig.FakeTwseClient twse;

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

	private Long settlement;

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
		jdbc.update("DELETE FROM stock_price");
		jdbc.update("DELETE FROM dividend");
		jdbc.update("DELETE FROM stock");
		twse.failure = null;
		twse.monthly = "{}";
		twse.monthlyCalls = 0;
		createIfMissing("pl-amy");
		loginAs("pl-amy");
		settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES "
				+ "('00646', '元大S&P500', 'ETF', 1, NOW()), ('00662', '富邦NASDAQ', 'ETF', 1, NOW())");
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	/** 兩列新的質押股票：各記一筆期初持股（不扣錢、沒有資金來源）並設成擔保。 */
	@Test
	void createWithNewPledgesRecordsOpeningAndCollateral() {
		Loan loan = loans.create(pledgeForm(List.of(line("00646", 3000L, "75.15"), line("00662", 2000L, "115.70"))));

		assertThat(jdbc.queryForList("SELECT CONCAT(s.code, '|', t.type, '|', t.trade_date, '|', t.shares, '|', "
				+ "t.amount, '|', COALESCE(t.account_id, 'none')) FROM trade t JOIN stock s ON s.id = t.stock_id "
				+ "ORDER BY s.code", String.class))
			.containsExactly("00646|OPENING|2026-07-28|3000|225450.0000|none", "00662|OPENING|2026-07-28|2000|231400.0000|none");
		assertThat(jdbc.queryForList("SELECT CONCAT(s.code, '|', c.shares) FROM pledge_collateral c "
				+ "JOIN stock s ON s.id = c.stock_id WHERE c.loan_id = ? ORDER BY s.code", String.class, loan.getId()))
			.containsExactly("00646|3000", "00662|2000");
		// 只有撥款一筆現金異動：期初持股不扣任何帳戶的錢
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement WHERE trade_id IS NOT NULL", Integer.class))
			.isZero();
		assertThat(jdbc.queryForList("SELECT type FROM cash_movement", String.class))
			.containsExactly("LOAN_DISBURSEMENT");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_allocation", Integer.class)).isZero();
	}

	/** 這檔已經有一筆更晚的買進（DR-055，記的是過去的狀態）：照樣再記一筆期初持股，擔保只算這次質押的股數。 */
	@Test
	void existingStockRowAddsAnotherOpening() {
		trades.create(new TradeForm(TradeType.BUY, PLEDGE_DAY.plusDays(1), "00646", settlement, 1000L,
				new BigDecimal("75"), null, null, null, List.of()));

		loans.validate(pledgeForm(List.of(line("00646", 3000L, "75.15"))));
		Loan loan = loans.create(pledgeForm(List.of(line("00662", 2000L, "115.70"), line("00646", 3000L, "75.15"))));

		assertThat(jdbc.queryForList("SELECT CONCAT(t.type, '|', t.shares) FROM trade t JOIN stock s ON s.id = t.stock_id "
				+ "WHERE s.code = '00646' ORDER BY t.trade_date", String.class))
			.containsExactly("OPENING|3000", "BUY|1000");
		assertThat(jdbc.queryForList("SELECT CONCAT(s.code, '|', c.shares) FROM pledge_collateral c "
				+ "JOIN stock s ON s.id = c.stock_id WHERE c.loan_id = ? ORDER BY s.code", String.class, loan.getId()))
			.containsExactly("00646|3000", "00662|2000");
	}

	/** 同一檔分兩批質押給兩筆借款：各自一筆期初持股、各自的擔保。 */
	@Test
	void sameStockPledgedToTwoLoans() {
		Loan first = loans.create(pledgeForm(List.of(line("00646", 3000L, "75.15"))));
		Loan second = loans.create(pledgeForm(List.of(new NewPledgeLine("00646", 4000L, PLEDGE_DAY.plusDays(3),
				new BigDecimal("80"), null))));

		assertThat(jdbc.queryForList("SELECT CONCAT(t.trade_date, '|', t.shares, '|', t.amount) FROM trade t "
				+ "ORDER BY t.trade_date", String.class))
			.containsExactly("2026-07-28|3000|225450.0000", "2026-07-31|4000|320000.0000");
		assertThat(jdbc.queryForObject("SELECT shares FROM pledge_collateral WHERE loan_id = ?", Long.class,
				first.getId())).isEqualTo(3000L);
		assertThat(jdbc.queryForObject("SELECT shares FROM pledge_collateral WHERE loan_id = ?", Long.class,
				second.getId())).isEqualTo(4000L);
	}

	/** 同一筆借款同一檔填兩列（不同日期、價格）：兩筆期初持股，擔保是兩列加總。 */
	@Test
	void sameStockTwoRowsAddUp() {
		Loan loan = loans.create(pledgeForm(List.of(line("00646", 3000L, "75.15"),
				new NewPledgeLine(" 00646 ", 1000L, PLEDGE_DAY.minusDays(7), new BigDecimal("70"), null))));

		assertThat(count("trade")).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT shares FROM pledge_collateral WHERE loan_id = ?", Long.class,
				loan.getId())).isEqualTo(4000L);
	}

	@Test
	void rowChecksUseLoanMessages() {
		assertThatThrownBy(() -> loans.validate(pledgeForm(List.of(line("9999", 3000L, "75.15")))))
			.isInstanceOf(LoanValidationException.class).hasMessage("找不到這個上市股票代號：9999");
		assertThatThrownBy(() -> loans.validate(pledgeForm(List.of(line("00646", 0L, "75.15")))))
			.isInstanceOf(LoanValidationException.class).hasMessage("請輸入股數（正整數）");
		assertThatThrownBy(() -> loans.validate(pledgeForm(List.of(line("00646", 3000L, null)))))
			.isInstanceOf(LoanValidationException.class).hasMessage("請輸入價格");
		assertThatThrownBy(() -> loans.validate(pledgeForm(List.of(line("00646", 3000L, "0")))))
			.isInstanceOf(LoanValidationException.class).hasMessage("請輸入價格");
		assertThatThrownBy(() -> loans.validate(pledgeForm(List.of(new NewPledgeLine("00646", 3000L, null,
				new BigDecimal("75.15"), null)))))
			.isInstanceOf(LoanValidationException.class).hasMessage("請選擇質押日期");
		LoanForm credit = withType(pledgeForm(List.of(line("00646", 3000L, "75.15"))), LoanType.CREDIT);
		assertThatThrownBy(() -> loans.validate(credit))
			.isInstanceOf(LoanValidationException.class).hasMessage("只有股票質押要選擔保股票");
		// 只有日期的空列略過
		loans.validate(pledgeForm(List.of(new NewPledgeLine(null, null, PLEDGE_DAY, null, null))));
	}

	/** 價格空白、代號與日期都有的列：帶入證交所那天的收盤價並註明來源。 */
	@Test
	void fillPricesUsesClose() {
		twse.monthly = TwseParserTest.MONTHLY;

		List<NewPledgeLine> filled = loans.fillPrices(List.of(line("00646", 3000L, null),
				line("00662", 2000L, "115.70"), new NewPledgeLine(null, 1000L, PLEDGE_DAY, null, null)));

		assertThat(filled.get(0).price()).isEqualByComparingTo("75.15");
		assertThat(filled.get(0).priceNote()).isEqualTo("證交所 2026-07-28 收盤價");
		assertThat(filled.get(0).shares()).isEqualTo(3000L);
		assertThat(filled.get(1)).isEqualTo(line("00662", 2000L, "115.70"));
		assertThat(filled.get(2)).isEqualTo(new NewPledgeLine(null, 1000L, PLEDGE_DAY, null, null));
		assertThat(twse.monthlyCalls).isEqualTo(1);
	}

	/** 同一檔同一個月的兩個假日：只問證交所一次。 */
	@Test
	void fillPricesFetchesEachMonthOnce() {
		twse.monthly = TwseParserTest.MONTHLY;

		List<NewPledgeLine> filled = loans.fillPrices(List.of(
				new NewPledgeLine("00646", 3000L, LocalDate.of(2026, 7, 25), null, null),
				new NewPledgeLine("00646", 2000L, LocalDate.of(2026, 7, 26), null, null)));

		assertThat(filled.get(0).price()).isNull();
		assertThat(filled.get(1).price()).isNull();
		assertThat(twse.monthlyCalls).isEqualTo(1);
	}

	@Test
	void fillPricesHolidayLeavesBlank() {
		twse.monthly = TwseParserTest.MONTHLY;

		List<NewPledgeLine> filled = loans.fillPrices(
				List.of(new NewPledgeLine("00646", 3000L, LocalDate.of(2026, 7, 26), null, null)));

		assertThat(filled.get(0).price()).isNull();
		assertThat(filled.get(0).priceNote()).isEqualTo("那天沒有收盤價（休市或還沒有資料），請自己填");
	}

	/** 日期晚於今天或早於 2000-01-01 的列：不問證交所、不留抓價紀錄，原樣回傳。 */
	@Test
	void fillPricesSkipsDatesOutOfRange() {
		twse.monthly = TwseParserTest.MONTHLY;
		int logsBefore = count("price_update_log");
		List<NewPledgeLine> rows = List.of(
				new NewPledgeLine("00646", 3000L, LocalDate.now(clock).plusDays(1), null, null),
				new NewPledgeLine("00646", 3000L, LocalDate.of(1999, 12, 31), null, null));

		assertThat(loans.fillPrices(rows)).isEqualTo(rows);
		assertThat(twse.monthlyCalls).isZero();
		assertThat(count("price_update_log")).isEqualTo(logsBefore);
	}

	/**
	 * 借款和第一筆期初持股都寫進去之後，第二列才在 TradeService 失敗（價格超過上限，check 不擋）：
	 * 整筆退回，借款、交易、擔保、現金異動都沒有留下。
	 */
	@Test
	void failureAfterFirstOpeningRollsBackEverything() {
		int loansBefore = count("loan");
		int tradesBefore = count("trade");
		int collateralBefore = count("pledge_collateral");
		int movementsBefore = count("cash_movement");

		assertThatThrownBy(() -> loans.create(pledgeForm(List.of(line("00646", 3000L, "75.15"),
				line("00662", 2000L, "100000000")))))
			.isInstanceOf(LoanValidationException.class)
			.hasMessageStartingWith("00662：");

		assertThat(count("loan")).isEqualTo(loansBefore);
		assertThat(count("trade")).isEqualTo(tradesBefore);
		assertThat(count("pledge_collateral")).isEqualTo(collateralBefore);
		assertThat(count("cash_movement")).isEqualTo(movementsBefore);
	}

	/** 作廢有擔保股票的借款：成功訊息要提醒期初持股沒有刪（回傳 true）；沒擔保的回傳 false。 */
	@Test
	void voidReportsCollateral() {
		Loan pledged = loans.create(pledgeForm(List.of(line("00646", 3000L, "75.15"))));
		Loan plain = loans.create(pledgeForm(List.of()));

		assertThat(loans.voidLoan(pledged.getId(), "打錯")).isTrue();
		assertThat(loans.voidLoan(plain.getId(), "打錯")).isFalse();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade WHERE voided = FALSE", Integer.class)).isEqualTo(1);
	}

	// ---------- 小工具 ----------

	private static NewPledgeLine line(String code, Long shares, String price) {
		return new NewPledgeLine(code, shares, PLEDGE_DAY, price == null ? null : new BigDecimal(price), null);
	}

	private LoanForm pledgeForm(List<NewPledgeLine> rows) {
		return new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, PLEDGE_DAY,
				new BigDecimal("500000"), new BigDecimal("3"), null, settlement, null, null, null,
				PLEDGE_DAY.plusYears(1), RepaymentMethod.BULLET, null, null, List.of(), null, null, rows);
	}

	private static LoanForm withType(LoanForm f, LoanType type) {
		return new LoanForm(f.name(), type, f.bank(), f.origin(), f.originalAmount(), f.startDate(),
				f.startPrincipal(), f.annualRatePercent(), f.disburseAccountId(), f.payAccountId(), f.payDay(),
				f.remainingPeriods(), f.firstPeriod(), f.dueDate(), f.repaymentMethod(), f.monthlyPayment(), f.fee(),
				f.collateral(), f.warnPercent(), f.callPercent(), f.newPledges());
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private void createIfMissing(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
	}

	private void loginAs(String username) {
		UserDetails details = userDetailsService.loadUserByUsername(username);
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
	}

}
