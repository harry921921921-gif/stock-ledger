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
import com.sideproject.stockledger.dto.AllocationLine;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.LoanCard;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.PrincipalForm;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 資金來源頁接上股票（設計文件 2026-10-23-stocks 第 6、9 點；spec 4.1、5.4、5.5）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class FundingStocksTest {

	@Autowired
	private TradeService trades;

	@Autowired
	private LoanService loans;

	@Autowired
	private CashMovementService cashMovements;

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
	private Loan pledge;

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
		createIfMissing("fs-amy");
		createIfMissing("fs-ben");
		loginAs("fs-amy");
		today = LocalDate.now(clock);
		settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0056', '元大高股息', "
				+ "'ETF', 4, NOW())");
		Long etf = jdbc.queryForObject("SELECT id FROM stock WHERE code = '0056'", Long.class);
		jdbc.update("INSERT INTO stock_price (stock_id, trade_date, close_price) VALUES (?, ?, 60)",
				etf, today);
		pledge = loans.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING,
				new BigDecimal("400000"), today.minusDays(1), new BigDecimal("400000"), new BigDecimal("3"), null,
				settlement, 25, null, null, null));
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	/** 尚未投入由事件推算：40 萬借款買了 5 萬的股票 → 尚未投入 35 萬；「錢現在在哪」列出持股。 */
	@Test
	void undeployedAndWhereTheMoneyIs() {
		trades.create(buyWithLoan(50000));

		LoanCard card = card();
		assertThat(card.undeployed()).isEqualByComparingTo("350000");
		assertThat(card.deployedIn()).singleElement().satisfies(line -> {
			assertThat(line.label()).isEqualTo("0056 元大高股息");
			assertThat(line.amount()).isEqualByComparingTo("50000");
		});
	}

	/**
	 * 借款淨賺 ＝ 借款出資部分的帳面損益＋已實現損益＋配息 − 已付利息（spec 5.4）。
	 * 1000 股成本 58,833、市值 60,000；借款出 50,000 → 帳面 60,000 × 50,000 ÷ 58,833 − 50,000 ＝ 991.79 → 992。
	 */
	@Test
	void netGain() {
		trades.create(buyWithLoan(50000));
		BigDecimal paidInterest = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM cash_movement "
				+ "WHERE loan_id = ? AND type = 'LOAN_INTEREST' AND voided = FALSE", BigDecimal.class, pledge.getId());

		assertThat(card().netGain()).isEqualByComparingTo(new BigDecimal("992").subtract(paidInterest));
	}

	/** 同一檔還有很便宜的期初持股（自有）：借款淨賺只看借款買到的股數，不分到那一批的漲幅。 */
	@Test
	void netGainIgnoresOtherLots() {
		trades.create(new TradeForm(TradeType.OPENING, today.minusDays(10), "0056", null, 1000L,
				new BigDecimal("30"), null, null, null, List.of()));
		trades.create(buyWithLoan(50000));
		BigDecimal paidInterest = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM cash_movement "
				+ "WHERE loan_id = ? AND type = 'LOAN_INTEREST' AND voided = FALSE", BigDecimal.class, pledge.getId());

		assertThat(card().netGain()).isEqualByComparingTo(new BigDecimal("992").subtract(paidInterest));
	}

	/** 擔保股票與維持率：1000 股 × 60 ＝ 60,000 ÷ 400,000 ＝ 15% → 低於追繳線（預設 150%、130%）。 */
	@Test
	void collateralAndMaintenance() {
		trades.create(buyWithLoan(0));

		loans.setCollateral(pledge.getId(), "0056", 1000L);

		LoanCard card = card();
		assertThat(card.collateral()).singleElement().satisfies(line -> {
			assertThat(line.label()).isEqualTo("0056 元大高股息");
			assertThat(line.shares()).isEqualTo(1000L);
			assertThat(line.marketValue()).isEqualByComparingTo("60000");
		});
		assertThat(card.maintenancePercent()).isEqualByComparingTo("15.00");
		assertThat(card.maintenanceLevel()).isEqualTo("CALL");
		assertThat(card.warnPercent()).isEqualByComparingTo("150");
		assertThat(card.callPercent()).isEqualByComparingTo("130");
	}

	/** 自訂門檻（依借款各自設定）；擔保股數 0 ＝ 拿掉；不能超過持有股數。 */
	@Test
	void thresholdsAndCollateralRules() {
		trades.create(buyWithLoan(0));

		loans.setThresholds(pledge.getId(), new BigDecimal("10"), new BigDecimal("5"));
		loans.setCollateral(pledge.getId(), "0056", 1000L);
		assertThat(card().maintenanceLevel()).isEqualTo("OK");
		assertThat(card().warnPercent()).isEqualByComparingTo("10");

		assertThatThrownBy(() -> loans.setCollateral(pledge.getId(), "0056", 1001L))
			.hasMessage("擔保股數不能超過持有股數（1,000 股）");
		assertThatThrownBy(() -> loans.setThresholds(pledge.getId(), new BigDecimal("120"), new BigDecimal("130")))
			.hasMessage("提醒線要高於追繳線");

		loans.setCollateral(pledge.getId(), "0056", 0L);
		assertThat(card().collateral()).isEmpty();
		assertThat(card().maintenancePercent()).isNull();
	}

	/** 有股票用到的借款不能作廢；別人的借款看不到也改不到。 */
	@Test
	void guards() {
		trades.create(buyWithLoan(50000));

		assertThatThrownBy(() -> loans.voidLoan(pledge.getId(), "記錯"))
			.hasMessageContaining("還有股票交易用到這筆借款");

		loginAs("fs-ben");
		assertThatThrownBy(() -> loans.setCollateral(pledge.getId(), "0056", 1L))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> loans.setThresholds(pledge.getId(), new BigDecimal("150"), new BigDecimal("130")))
			.isInstanceOf(NotFoundException.class);
	}

	/** 審查 C1：股票自動記的現金異動不能單獨作廢，要到股票頁作廢交易。 */
	@Test
	void tradeCashMovementCannotBeVoidedAlone() {
		Long trade = trades.create(buyWithLoan(0)).getId();
		Long movement = jdbc.queryForObject("SELECT id FROM cash_movement WHERE trade_id = ?", Long.class, trade);

		assertThatThrownBy(() -> cashMovements.voidMovement(movement, "試試"))
			.hasMessage("這筆是股票交易自動記的，請到股票頁作廢那筆交易");
	}

	/** 審查 I2：借款起算日（昨天）以前就拿這筆借款去買，即使最後加總沒超過也不行。 */
	@Test
	void cannotSpendLoanBeforeItExists() {
		assertThatThrownBy(() -> trades.create(new TradeForm(TradeType.BUY, today.minusDays(5), "0056", settlement,
				1000L, new BigDecimal("58.75"), null, null, null,
				List.of(new AllocationLine(pledge.getId(), new BigDecimal("50000"))))))
			.hasMessage("交易日 " + today.minusDays(5) + " 早於「玉山質押」的起算日 " + today.minusDays(1)
					+ "；起算日以前系統裡還沒有這筆借款的錢");
	}

	/** 審查 I3：從全部異動頁作廢已經拿去買股票的再動撥，會讓借款出的錢超過借到的 → 擋下。 */
	@Test
	void voidingSpentRedrawIsRejected() {
		loans.redraw(pledge.getId(), new PrincipalForm(today, new BigDecimal("100000"), settlement));
		trades.create(new TradeForm(TradeType.BUY, today, "0056", settlement, 8000L, new BigDecimal("58.75"), null,
				null, null, List.of(new AllocationLine(pledge.getId(), new BigDecimal("450000")))));
		Long redraw = jdbc.queryForObject("SELECT id FROM cash_movement WHERE loan_id = ? AND type = "
				+ "'LOAN_DISBURSEMENT'", Long.class, pledge.getId());

		assertThatThrownBy(() -> cashMovements.voidMovement(redraw, "打錯"))
			.hasMessageContaining("買股票用掉的錢會超過那時已經借到的錢");
	}

	/** 審查 I4：兩筆質押的擔保合計不能超過持股；擔保中的股票不能賣到不夠。 */
	@Test
	void collateralTotalsAcrossPledgesAndSells() {
		trades.create(buyWithLoan(0));
		Loan second = loans.create(new LoanForm("第二筆質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING,
				new BigDecimal("100000"), today.minusDays(1), new BigDecimal("100000"), new BigDecimal("3"), null,
				settlement, 25, null, null, null));
		loans.setCollateral(pledge.getId(), "0056", 600L);

		assertThatThrownBy(() -> loans.setCollateral(second.getId(), "0056", 500L))
			.hasMessage("擔保股數加上其他質押的 600 股，超過持有股數（1,000 股）");
		assertThatThrownBy(() -> trades.create(new TradeForm(TradeType.SELL, today, "0056", settlement, 500L,
				new BigDecimal("60"), null, null, null, List.of())))
			.hasMessage("這檔有 600 股設定為質押擔保，這樣持股只剩 500 股；請先到資金來源頁調整擔保股數");
	}

	private LoanCard card() {
		return loans.fundingView().loans().stream().filter(c -> c.id().equals(pledge.getId())).findFirst().orElseThrow();
	}

	private TradeForm buyWithLoan(long loanAmount) {
		List<AllocationLine> lines = loanAmount == 0 ? List.of()
				: List.of(new AllocationLine(pledge.getId(), BigDecimal.valueOf(loanAmount)));
		return new TradeForm(TradeType.BUY, today, "0056", settlement, 1000L, new BigDecimal("58.75"), null, null,
				null, lines);
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
