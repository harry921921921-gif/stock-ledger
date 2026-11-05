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
import com.sideproject.stockledger.dto.LoanCard;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.NewPledgeLine;
import com.sideproject.stockledger.dto.SettleForm;
import com.sideproject.stockledger.dto.SettleForm.SettleSale;
import com.sideproject.stockledger.dto.SettlePreview;
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

/** 質押借款的還款／到期處理（設計文件 2026-11-04-pledged-stocks 6a、DR-054）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestcontainersConfig.class, FakeTwseConfig.class })
class LoanSettleTest {

	@Autowired
	private LoanService loans;

	@Autowired
	private TradeService trades;

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

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager transactionManager;

	private LocalDate today;
	private Long bank;
	private Long settlement;
	private Long stock00646;
	private Loan loan;

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
		createIfMissing("ls-amy");
		createIfMissing("ls-ben");
		loginAs("ls-amy");
		today = LocalDate.now(clock);
		bank = accounts.create(new CashAccountForm("土銀帳戶", CashAccountType.BANK_DEMAND, "土地銀行")).getId();
		settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES "
				+ "('00646', '元大S&P500', 'ETF', 1, NOW())");
		stock00646 = jdbc.queryForObject("SELECT id FROM stock WHERE code = '00646'", Long.class);
		// 10 萬到期一次還本金＋利息，擔保 00646 3,000 股（質押時記的期初持股）
		loan = loans.create(pledgeForm(RepaymentMethod.BULLET));
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	/** 現金還本 100,000＋利息：剩餘本金 0、卡片已還清、擔保股票解除質押。 */
	@Test
	void cashSettlePaysOff() {
		SettleForm d = loans.settleDefaults(loan.getId());
		assertThat(d.principal()).isEqualByComparingTo("100000");
		assertThat(d.interest()).isNotNull();
		assertThat(d.sales()).extracting(SettleSale::stockId).containsExactly(stock00646);

		loans.settle(loan.getId(), cash(d.principal(), d.interest()));

		assertThat(loans.remainingPrincipal(loans.find(loan.getId()), today)).isEqualByComparingTo("0");
		LoanCard card = card();
		assertThat(card.paidOff()).isTrue();
		assertThat(card.collateralReleased()).isTrue();
		assertThat(card.settle()).isFalse();
		assertThat(jdbc.queryForList("SELECT CONCAT(type, '|', account_id, '|', amount) FROM cash_movement "
				+ "WHERE loan_id = ? AND type <> 'LOAN_DISBURSEMENT' ORDER BY id", String.class, loan.getId()))
			.containsExactly("LOAN_PRINCIPAL|" + bank + "|100000.0000",
					"LOAN_INTEREST|" + bank + "|" + d.interest().setScale(4));
	}

	/** 賣 00646 1,000 股 × 76：一筆賣出、擔保少 1,000、同日一筆借款還本＝實際入帳、交割戶淨額 0。 */
	@Test
	void sellSettleRecordsSaleAndRepay() {
		loans.settle(loan.getId(), sell(1000L, "76", null));

		assertThat(jdbc.queryForList("SELECT CONCAT(type, '|', trade_date, '|', shares, '|', account_id) FROM trade "
				+ "WHERE type = 'SELL'", String.class))
			.containsExactly("SELL|" + today + "|1000|" + settlement);
		BigDecimal proceeds = jdbc.queryForObject("SELECT amount FROM trade WHERE type = 'SELL'", BigDecimal.class);
		assertThat(collateralShares()).isEqualTo(2000L);
		assertThat(jdbc.queryForList("SELECT CONCAT(movement_date, '|', account_id, '|', amount) FROM cash_movement "
				+ "WHERE loan_id = ? AND type = 'LOAN_PRINCIPAL'", String.class, loan.getId()))
			.containsExactly(today + "|" + settlement + "|" + proceeds);
		// 交割戶：賣出入帳一筆、還本扣一筆，金額相同
		assertThat(jdbc.queryForList("SELECT CONCAT(type, '|', amount) FROM cash_movement WHERE account_id = ? "
				+ "ORDER BY id", String.class, settlement))
			.containsExactly("STOCK_SELL|" + proceeds, "LOAN_PRINCIPAL|" + proceeds);
		assertThat(loans.remainingPrincipal(loans.find(loan.getId()), today))
			.isEqualByComparingTo(new BigDecimal("100000").subtract(proceeds));
		assertThat(card().paidOff()).isFalse();
	}

	/** 現金和賣股票一起：賣股還款＝實際入帳，現金補剩下的本金＋利息 → 還清。 */
	@Test
	void cashAndSellTogether() {
		BigDecimal proceeds = trades.quote(new TradeForm(TradeType.SELL, today, "00646", settlement, 1000L,
				new BigDecimal("76"), null, null, null, List.of())).amount();
		SettleForm d = loans.settleDefaults(loan.getId());
		BigDecimal rest = new BigDecimal("100000").subtract(proceeds);

		loans.settle(loan.getId(), new SettleForm(true, today, bank, rest, d.interest(), true, settlement,
				List.of(new SettleSale(stock00646, 1000L, new BigDecimal("76"), null, null, null)), null, false, null));

		assertThat(jdbc.queryForList("SELECT CONCAT(account_id, '|', amount) FROM cash_movement WHERE loan_id = ? "
				+ "AND type = 'LOAN_PRINCIPAL' ORDER BY id", String.class, loan.getId()))
			.containsExactly(settlement + "|" + proceeds.setScale(4), bank + "|" + rest.setScale(4));
		assertThat(loans.remainingPrincipal(loans.find(loan.getId()), today)).isEqualByComparingTo("0");
		assertThat(card().collateralReleased()).isTrue();
		assertThat(collateralShares()).isEqualTo(2000L);
	}

	/** 只展延：沒有任何現金異動，到期日改了，擔保維持。 */
	@Test
	void extendChangesDueDateOnly() {
		int movementsBefore = count("cash_movement");
		LocalDate newDue = today.plusYears(2);

		loans.settle(loan.getId(), extend(newDue));

		assertThat(count("cash_movement")).isEqualTo(movementsBefore);
		assertThat(jdbc.queryForObject("SELECT due_date FROM loan WHERE id = ?", java.sql.Date.class, loan.getId())
			.toLocalDate()).isEqualTo(newDue);
		assertThat(collateralShares()).isEqualTo(3000L);
	}

	/** 展延日要晚於原到期日。 */
	@Test
	void extendMustBeLaterThanDueDate() {
		assertThatThrownBy(() -> loans.settle(loan.getId(), extend(today.plusMonths(3))))
			.isInstanceOf(LoanValidationException.class)
			.hasMessageContaining("展延後的到期日要晚於今天和原到期日");
	}

	/** 還清時不能同時展延：整筆不存。 */
	@Test
	void paidOffCannotAlsoExtend() {
		SettleForm d = loans.settleDefaults(loan.getId());
		int movementsBefore = count("cash_movement");

		assertThatThrownBy(() -> loans.settle(loan.getId(), new SettleForm(true, today, bank, d.principal(),
				d.interest(), false, null, List.of(), null, true, today.plusYears(2))))
			.isInstanceOf(LoanValidationException.class)
			.hasMessage("已經還清，不需要展延");
		assertThat(count("cash_movement")).isEqualTo(movementsBefore);
	}

	/** 賣出股數超過擔保股數：整筆沒存（賣出、擔保、還本都沒有）。 */
	@Test
	void sellMoreThanPledgedRejected() {
		// 另外買 1,000 股：持股 4,000，擔保 3,000；賣 3,001 照賣出規則可以，但超過擔保
		trades.create(new TradeForm(TradeType.BUY, today, "00646", settlement, 1000L, new BigDecimal("75"), null,
				null, null, List.of()));
		int tradesBefore = count("trade");
		int movementsBefore = count("cash_movement");

		assertThatThrownBy(() -> loans.settle(loan.getId(), sell(3001L, "76", null)))
			.isInstanceOf(LoanValidationException.class)
			.hasMessage("賣出的股數超過擔保股數");
		assertThat(count("trade")).isEqualTo(tradesBefore);
		assertThat(count("cash_movement")).isEqualTo(movementsBefore);
		assertThat(collateralShares()).isEqualTo(3000L);
	}

	/** 後面的步驟失敗（賣股還款超過入帳）時，前面已經做的賣出與擔保調整也一起回滾。 */
	@Test
	void laterFailureRollsBackSale() {
		int tradesBefore = count("trade");
		int movementsBefore = count("cash_movement");

		assertThatThrownBy(() -> loans.settle(loan.getId(), sell(1000L, "76", new BigDecimal("90000"))))
			.isInstanceOf(LoanValidationException.class)
			.hasMessageContaining("賣股還款不能超過賣出實際入帳合計");
		assertThat(count("trade")).isEqualTo(tradesBefore);
		assertThat(count("cash_movement")).isEqualTo(movementsBefore);
		assertThat(collateralShares()).isEqualTo(3000L);
	}

	/** 至少勾一項。 */
	@Test
	void nothingCheckedRejected() {
		assertThatThrownBy(() -> loans.settle(loan.getId(), new SettleForm(false, today, bank, null, null, false,
				null, List.of(), null, false, null)))
			.isInstanceOf(LoanValidationException.class)
			.hasMessageContaining("請至少勾選一種");
	}

	/** 預覽：只驗證不存，算出手續費、證交稅、實際入帳與賣股還款，和試算一樣。 */
	@Test
	void previewSavesNothingAndShowsComputedValues() {
		TradeService.Priced quote = trades.quote(new TradeForm(TradeType.SELL, today, "00646", settlement, 1000L,
				new BigDecimal("76"), null, null, null, List.of()));
		int tradesBefore = count("trade");
		int movementsBefore = count("cash_movement");

		SettlePreview preview = loans.previewSettle(loan.getId(), sell(1000L, "76", null));

		assertThat(count("trade")).isEqualTo(tradesBefore);
		assertThat(count("cash_movement")).isEqualTo(movementsBefore);
		assertThat(collateralShares()).isEqualTo(3000L);
		SettleSale sale = preview.resolved().sales().get(0);
		assertThat(sale.fee()).isEqualByComparingTo(quote.fee());
		assertThat(sale.tax()).isEqualByComparingTo(quote.tax());
		assertThat(sale.amount()).isEqualByComparingTo(quote.amount());
		assertThat(preview.resolved().saleRepay()).isEqualByComparingTo(quote.amount());
		assertThat(preview.paidOff()).isFalse();
		assertThat(preview.remaining()).isEqualByComparingTo(new BigDecimal("100000").subtract(quote.amount()));
		assertThat(preview.rows()).anySatisfy(r -> assertThat(r.label()).contains("賣出 00646"));
	}

	/** 別人的借款：預設值、預覽、存檔都是 404。 */
	@Test
	void settleOthersLoanIsNotFound() {
		loginAs("ls-ben");
		assertThatThrownBy(() -> loans.settleDefaults(loan.getId())).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> loans.previewSettle(loan.getId(), extend(today.plusYears(2))))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> loans.settle(loan.getId(), extend(today.plusYears(2))))
			.isInstanceOf(NotFoundException.class);
	}

	/** 已經還清的質押、每月固定還款的信貸都不能做還款／到期處理。 */
	@Test
	void onlyUnpaidPledgesCanSettle() {
		SettleForm d = loans.settleDefaults(loan.getId());
		loans.settle(loan.getId(), cash(d.principal(), d.interest()));
		assertThatThrownBy(() -> loans.settleDefaults(loan.getId()))
			.isInstanceOf(LoanValidationException.class)
			.hasMessageContaining("已經還清");

		Loan credit = loans.create(new LoanForm("信貸", LoanType.CREDIT, "國泰", LoanOrigin.NEW, null,
				today.minusMonths(3), new BigDecimal("100000"), new BigDecimal("3"), null, bank, 5, 12, null, null,
				RepaymentMethod.MONTHLY_FIXED, new BigDecimal("8500"), null, List.of(), null, null, List.of()));
		assertThatThrownBy(() -> loans.settleDefaults(credit.getId())).isInstanceOf(LoanValidationException.class);
		assertThat(loans.fundingView().loans().stream().filter(c -> c.id().equals(credit.getId())).findFirst()
			.orElseThrow().settle()).isFalse();
	}

	/** 賣股票還清全部本金，現金還款只付利息（本金 0）：還清，記一筆借款利息。 */
	@Test
	void sellPaysOffAndCashPaysInterestOnly() {
		SettleForm d = loans.settleDefaults(loan.getId());

		loans.settle(loan.getId(), new SettleForm(true, today, bank, BigDecimal.ZERO, d.interest(), true, settlement,
				List.of(new SettleSale(stock00646, 1500L, new BigDecimal("76"), null, null, null)), null, false, null));

		assertThat(loans.remainingPrincipal(loans.find(loan.getId()), today)).isEqualByComparingTo("0");
		assertThat(card().paidOff()).isTrue();
		assertThat(jdbc.queryForList("SELECT CONCAT(account_id, '|', amount) FROM cash_movement WHERE loan_id = ? "
				+ "AND type = 'LOAN_INTEREST'", String.class, loan.getId()))
			.containsExactly(bank + "|" + d.interest().setScale(4));
		assertThat(jdbc.queryForList("SELECT CONCAT(account_id, '|', amount) FROM cash_movement WHERE loan_id = ? "
				+ "AND type = 'LOAN_PRINCIPAL'", String.class, loan.getId()))
			.containsExactly(settlement + "|100000.0000");
	}

	/** 現金還款本金和利息都是 0：沒有東西可記。 */
	@Test
	void cashWithNothingToPayRejected() {
		int movementsBefore = count("cash_movement");

		assertThatThrownBy(() -> loans.settle(loan.getId(), cash(BigDecimal.ZERO, BigDecimal.ZERO)))
			.isInstanceOf(LoanValidationException.class)
			.hasMessage(LoanService.NOTHING_TO_PAY);
		assertThatThrownBy(() -> loans.settle(loan.getId(), cash(null, null)))
			.isInstanceOf(LoanValidationException.class)
			.hasMessage(LoanService.NOTHING_TO_PAY);
		assertThat(count("cash_movement")).isEqualTo(movementsBefore);
	}

	/** 預覽會回滾整個交易：在別的交易裡呼叫直接擋下，不會連外層一起撤掉。 */
	@Test
	void previewInsideTransactionRejected() {
		var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> loans.previewSettle(loan.getId(),
				extend(today.plusYears(2)))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("只能在沒有交易的地方呼叫");
	}

	/** 每月繳息到期還本也適用（現金還本，不填利息）。 */
	@Test
	void interestOnlyPledgeCanSettle() {
		Loan io = loans.create(new LoanForm("每月繳息質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null,
				today.minusMonths(3), new BigDecimal("50000"), new BigDecimal("3"), null, bank, 25, null, null,
				today.plusMonths(9), RepaymentMethod.INTEREST_ONLY, null, null, List.of(), null, null, List.of()));
		SettleForm d = loans.settleDefaults(io.getId());
		assertThat(d.interest()).isNull();

		loans.settle(io.getId(), cash(d.principal(), null));

		assertThat(loans.remainingPrincipal(loans.find(io.getId()), today)).isEqualByComparingTo("0");
	}

	/** 到期一次還本金＋利息，只賣股票就還清：沒有記利息，預覽要提醒（不擋）。 */
	@Test
	void bulletSellOnlyPayoffWarnsNoInterest() {
		SettlePreview preview = loans.previewSettle(loan.getId(), sell(1500L, "76", null));

		assertThat(preview.paidOff()).isTrue();
		assertThat(preview.warnings()).containsExactly(LoanService.BULLET_NO_INTEREST_WARNING);
	}

	/** 賣股還清、現金只付利息：有記利息，不提醒。 */
	@Test
	void bulletSellWithInterestOnlyCashHasNoWarning() {
		SettleForm d = loans.settleDefaults(loan.getId());
		SettlePreview preview = loans.previewSettle(loan.getId(), new SettleForm(true, today, bank, BigDecimal.ZERO,
				d.interest(), true, settlement,
				List.of(new SettleSale(stock00646, 1500L, new BigDecimal("76"), null, null, null)), null, false, null));

		assertThat(preview.paidOff()).isTrue();
		assertThat(preview.warnings()).isEmpty();
	}

	/** 每月繳息到期還本本來就有記息，還清也不提醒。 */
	@Test
	void interestOnlyPayoffHasNoWarning() {
		Loan io = loans.create(new LoanForm("每月繳息質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null,
				today.minusMonths(3), new BigDecimal("50000"), new BigDecimal("3"), null, bank, 25, null, null,
				today.plusMonths(9), RepaymentMethod.INTEREST_ONLY, null, null, List.of(), null, null, List.of()));
		SettleForm d = loans.settleDefaults(io.getId());

		SettlePreview preview = loans.previewSettle(io.getId(), cash(d.principal(), null));

		assertThat(preview.paidOff()).isTrue();
		assertThat(preview.warnings()).isEmpty();
	}

	// ---------- 小工具 ----------

	private SettleForm cash(BigDecimal principal, BigDecimal interest) {
		return new SettleForm(true, today, bank, principal, interest, false, null, List.of(), null, false, null);
	}

	private SettleForm sell(Long shares, String price, BigDecimal saleRepay) {
		return new SettleForm(false, today, null, null, null, true, settlement,
				List.of(new SettleSale(stock00646, shares, new BigDecimal(price), null, null, null)), saleRepay, false,
				null);
	}

	private SettleForm extend(LocalDate due) {
		return new SettleForm(false, null, null, null, null, false, null, List.of(), null, true, due);
	}

	private LoanCard card() {
		return loans.fundingView().loans().stream().filter(c -> c.id().equals(loan.getId())).findFirst().orElseThrow();
	}

	private Long collateralShares() {
		return jdbc.queryForObject("SELECT COALESCE(SUM(shares), 0) FROM pledge_collateral WHERE loan_id = ?", Long.class,
				loan.getId());
	}

	private LoanForm pledgeForm(RepaymentMethod method) {
		LocalDate start = today.minusMonths(3);
		return new LoanForm("玉山質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW, null, start,
				new BigDecimal("100000"), new BigDecimal("3"), null, bank, null, null, null, today.plusMonths(9), method,
				null, null, List.of(), null, null,
				List.of(new NewPledgeLine("00646", 3000L, start, new BigDecimal("75.15"), null)));
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
