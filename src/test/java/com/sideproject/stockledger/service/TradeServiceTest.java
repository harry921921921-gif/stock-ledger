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
import com.sideproject.stockledger.dto.DividendForm;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.StocksView;
import com.sideproject.stockledger.dto.TradeForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.entity.Trade;
import com.sideproject.stockledger.entity.TradeType;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 股票交易（設計文件 2026-10-23-stocks 4、5；spec 4.1、4.2、4.4、4.7、5.1）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class TradeServiceTest {

	@Autowired
	private TradeService service;

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
	private Long settlement;
	private Long etf;

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
		createIfMissing("tr-amy");
		createIfMissing("tr-ben");
		loginAs("tr-amy");
		today = LocalDate.now(clock);
		settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0056', '元大高股息', "
				+ "'ETF', 4, NOW())");
		etf = jdbc.queryForObject("SELECT id FROM stock WHERE code = '0056'", Long.class);
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	/** 買進：手續費自動算（沒設定折扣 → 不打折）；自動從交割戶扣成交金額＋手續費。 */
	@Test
	void buyRecordsCashMovement() {
		Trade buy = service.create(buy(today, 1000, "58.75", null, List.of()));

		// 58,750 × 0.1425% ＝ 83.71 → 83；總成本 58,833
		assertThat(buy.getFee()).isEqualByComparingTo("83");
		assertThat(buy.getAmount()).isEqualByComparingTo("58833");
		assertThat(jdbc.queryForMap("SELECT type, amount, account_id, note FROM cash_movement WHERE trade_id = ?",
				buy.getId()))
			.containsEntry("type", "STOCK_BUY")
			.containsEntry("account_id", settlement)
			.containsEntry("note", "元大高股息");
	}

	/** 有設定 2.8 折：58,750 × 0.1425% × 0.28 ＝ 23.44 → 23。手續費也可以自己填。 */
	@Test
	void discountAndManualFee() {
		jdbc.update("INSERT INTO rate_setting (user_id, kind, value, effective_date, created_at) SELECT id, "
				+ "'BROKERAGE_DISCOUNT', 0.28, '2000-01-01', NOW() FROM app_user WHERE username = 'tr-amy'");

		assertThat(service.create(buy(today, 1000, "58.75", null, List.of())).getFee()).isEqualByComparingTo("23");
		assertThat(service.create(buy(today, 1000, "58.75", "20", List.of())).getFee()).isEqualByComparingTo("20");
	}

	/** 賣出：手續費、證交稅（ETF 0.1%）自動算；實際入帳沒填就用預估入帳；自動存入交割戶。 */
	@Test
	void sellDefaultsAndRecordsCash() {
		service.create(buy(today, 1000, "58.75", null, List.of()));

		Trade sell = service.create(new TradeForm(TradeType.SELL, today, "0056", settlement, 500L,
				new BigDecimal("60"), null, null, null, List.of()));

		// 30,000 × 0.1425% ＝ 42.75 → 42；證交稅 30；預估入帳 29,928
		assertThat(sell.getFee()).isEqualByComparingTo("42");
		assertThat(sell.getTax()).isEqualByComparingTo("30");
		assertThat(sell.getAmount()).isEqualByComparingTo("29928");
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE trade_id = ? AND type = 'STOCK_SELL'",
				BigDecimal.class, sell.getId())).isEqualByComparingTo("29928");
	}

	/**
	 * 審查 I5：修改賣出把 500 股改成 250 股，手續費、證交稅、實際入帳還是舊值 → 依新股數重算。
	 * 15,000 × 0.1425% ＝ 21.375 → 21；證交稅 15；入帳 14,964。自己改過的值則照用。
	 */
	@Test
	void editSellRecomputesStaleFees() {
		service.create(buy(today, 1000, "58.75", null, List.of()));
		Trade sell = service.create(new TradeForm(TradeType.SELL, today, "0056", settlement, 500L,
				new BigDecimal("60"), null, null, null, List.of()));

		Trade edited = service.edit(sell.getId(), new TradeForm(TradeType.SELL, today, "0056", settlement, 250L,
				new BigDecimal("60"), new BigDecimal("42"), new BigDecimal("30"), new BigDecimal("29928"), List.of()),
				null);
		assertThat(edited.getFee()).isEqualByComparingTo("21");
		assertThat(edited.getTax()).isEqualByComparingTo("15");
		assertThat(edited.getAmount()).isEqualByComparingTo("14964");

		Trade manual = service.edit(sell.getId(), new TradeForm(TradeType.SELL, today, "0056", settlement, 300L,
				new BigDecimal("60"), new BigDecimal("20"), new BigDecimal("15"), new BigDecimal("17900"), List.of()),
				null);
		assertThat(manual.getFee()).isEqualByComparingTo("20");
		assertThat(manual.getAmount()).isEqualByComparingTo("17900");
	}

	@Test
	void sellMoreThanHeldRejected() {
		service.create(buy(today, 1000, "58.75", null, List.of()));

		assertThatThrownBy(() -> service.create(new TradeForm(TradeType.SELL, today, "0056", settlement, 1001L,
				new BigDecimal("60"), null, null, null, List.of()))).isInstanceOf(TradeValidationException.class)
			.hasMessage("賣出股數超過持有股數");
	}

	/** 期初持股：總成本 ＝ 股數 × 平均成本（含手續費，四捨五入到元）；每檔最多一筆、要早於其他交易。 */
	@Test
	void openingRules() {
		Trade opening = service.create(new TradeForm(TradeType.OPENING, today.minusDays(10), "0056", null, 15000L,
				new BigDecimal("33.333"), null, null, null, List.of()));
		assertThat(opening.getAmount()).isEqualByComparingTo("499995");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement", Integer.class)).isZero();

		assertThatThrownBy(() -> service.create(new TradeForm(TradeType.OPENING, today.minusDays(20), "0056", null,
				1L, BigDecimal.ONE, null, null, null, List.of()))).hasMessage("這檔股票已經有期初持股");
		service.create(buy(today.minusDays(5), 1000, "58.75", null, List.of()));
		Long other = jdbc.queryForObject("SELECT id FROM stock WHERE code = '0056'", Long.class);
		assertThat(other).isEqualTo(etf);
	}

	@Test
	void openingMustBeBeforeOtherTrades() {
		service.create(buy(today.minusDays(5), 1000, "58.75", null, List.of()));

		assertThatThrownBy(() -> service.create(new TradeForm(TradeType.OPENING, today, "0056", null, 1000L,
				new BigDecimal("50"), null, null, null, List.of()))).hasMessage("期初持股要早於這檔股票的所有交易");
	}

	@Test
	void unknownStockRejected() {
		assertThatThrownBy(() -> service.create(new TradeForm(TradeType.BUY, today, "9999", settlement, 1000L,
				BigDecimal.TEN, null, null, null, List.of()))).hasMessage("找不到這個上市股票代號：9999");
	}

	/** 資金來源：借款出的錢不能超過它的尚未投入；加總不能超過總成本（spec 4.1-2）。 */
	@Test
	void allocationRules() {
		Loan pledge = loans.create(new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING,
				new BigDecimal("400000"), today.minusDays(30), new BigDecimal("400000"), new BigDecimal("3"), null,
				settlement, 25, null, null, null));

		Trade buy = service.create(buy(today, 1000, "58.75", null,
				List.of(new AllocationLine(pledge.getId(), new BigDecimal("50000")))));
		assertThat(jdbc.queryForObject("SELECT amount FROM source_allocation WHERE trade_id = ?", BigDecimal.class,
				buy.getId())).isEqualByComparingTo("50000");

		assertThatThrownBy(() -> service.create(buy(today, 1000, "58.75", null,
				List.of(new AllocationLine(pledge.getId(), new BigDecimal("60000"))))))
			.hasMessage("資金來源加總不能超過總成本 58,833 元");
		assertThatThrownBy(() -> service.create(new TradeForm(TradeType.OPENING, today.minusDays(20), "0056", null,
				20000L, new BigDecimal("50"), null, null, null,
				List.of(new AllocationLine(pledge.getId(), new BigDecimal("400000"))))))
			.hasMessageContaining("超過它的尚未投入");
	}

	/** 修改交易：寫修改紀錄，自動記的現金異動跟著改。 */
	@Test
	void editWritesRevisionsAndFollowsCash() {
		Trade buy = service.create(buy(today, 1000, "58.75", null, List.of()));

		service.edit(buy.getId(), buy(today, 1000, "58.70", "83", List.of()), "價格打錯");

		assertThat(jdbc.queryForList("SELECT field FROM trade_revision WHERE trade_id = ?", String.class, buy.getId()))
			.contains("price", "amount");
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE trade_id = ? AND voided = FALSE",
				BigDecimal.class, buy.getId())).isEqualByComparingTo("58783");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement_revision", Integer.class)).isPositive();
	}

	/** 作廢交易：連同自動記的現金異動一起作廢。 */
	@Test
	void voidTradeVoidsCash() {
		Trade buy = service.create(buy(today, 1000, "58.75", null, List.of()));

		service.voidTrade(buy.getId(), "記錯");

		assertThat(jdbc.queryForObject("SELECT voided FROM trade WHERE id = ?", Boolean.class, buy.getId())).isTrue();
		assertThat(jdbc.queryForObject("SELECT voided FROM cash_movement WHERE trade_id = ?", Boolean.class,
				buy.getId())).isTrue();
		assertThat(service.view().holdings()).isEmpty();
	}

	/**
	 * 配息：已經除息、還沒記入帳的會列成建議（應領金額、二代健保）；記了實際入帳後就不再建議，累計配息增加。
	 */
	@Test
	void dividendSuggestionAndRecord() {
		service.create(new TradeForm(TradeType.OPENING, today.minusDays(30), "0056", null, 15000L,
				new BigDecimal("33"), null, null, null, List.of()));
		jdbc.update("INSERT INTO dividend (stock_id, ex_date, cash_per_share, updated_at) VALUES (?, ?, 1.72, NOW())",
				etf, today.minusDays(1));

		StocksView before = service.view();
		assertThat(before.dividendSuggestions()).singleElement().satisfies(s -> {
			assertThat(s.shares()).isEqualTo(15000);
			assertThat(s.gross()).isEqualByComparingTo("25800");
			assertThat(s.nhi()).isEqualByComparingTo("544");
			assertThat(s.net()).isEqualByComparingTo("25256");
		});

		service.recordDividend(new DividendForm(today, settlement, "0056", new BigDecimal("25256"),
				new BigDecimal("544")));

		StocksView after = service.view();
		assertThat(after.dividendSuggestions()).isEmpty();
		assertThat(after.holdings().get(0).dividends()).isEqualByComparingTo("25256");
		assertThat(jdbc.queryForMap("SELECT type, nhi_amount FROM cash_movement")).containsEntry("type", "DIVIDEND");
	}

	/** 持股表：平均成本、現價、市值、帳面損益、報酬率、預估年配息（spec 5.4、5.6）。 */
	@Test
	void holdingsView() {
		service.create(buy(today.minusDays(3), 1000, "50", "20", List.of()));
		jdbc.update("INSERT INTO stock_price (stock_id, trade_date, close_price) VALUES (?, ?, 58.75)", etf,
				today.minusDays(1));
		jdbc.update("INSERT INTO dividend (stock_id, ex_date, cash_per_share, updated_at) VALUES (?, ?, 1.72, NOW())",
				etf, today.minusDays(60));

		StocksView v = service.view();

		var h = v.holdings().get(0);
		assertThat(h.shares()).isEqualTo(1000);
		assertThat(h.averageCost()).isEqualByComparingTo("50.02");
		assertThat(h.price()).isEqualByComparingTo("58.75");
		assertThat(h.marketValue()).isEqualByComparingTo("58750");
		assertThat(h.unrealized()).isEqualByComparingTo("8730");
		assertThat(h.returnPercent()).isEqualByComparingTo("17.45");
		// 1.72 × 4 次 × 1,000 股
		assertThat(h.annualDividend()).isEqualByComparingTo("6880");
		assertThat(v.marketValue()).isEqualByComparingTo("58750");
		assertThat(v.priceDate()).isEqualTo(today.minusDays(1));
	}

	@Test
	void otherUsersTradeIsNotFound() {
		Trade buy = service.create(buy(today, 1000, "58.75", null, List.of()));
		loginAs("tr-ben");

		assertThat(service.view().holdings()).isEmpty();
		assertThatThrownBy(() -> service.find(buy.getId())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.voidTrade(buy.getId(), null)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.create(buy(today, 1000, "58.75", null, List.of())))
			.isInstanceOf(NotFoundException.class);
	}

	private TradeForm buy(LocalDate date, long shares, String price, String fee, List<AllocationLine> allocations) {
		return new TradeForm(TradeType.BUY, date, "0056", settlement, shares, new BigDecimal(price),
				fee == null ? null : new BigDecimal(fee), null, null, allocations);
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
