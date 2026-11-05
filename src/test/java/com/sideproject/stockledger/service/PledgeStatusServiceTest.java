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
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.LoanCard;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.LoanMovementForm;
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

/**
 * 質押狀態由事件推算（設計文件 2026-11-04-pledged-stocks 第 6 節）：剩餘本金（到今天）＝ 0 的借款，
 * 它的擔保列不算質押中、不擋賣出、不算進其他質押的擔保合計；作廢那筆還本後自動恢復。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class PledgeStatusServiceTest {

	private static final String SELL_BLOCKED = "這檔有 1,000 股設定為質押擔保，這樣持股只剩 0 股；請先到資金來源頁調整擔保股數";

	@Autowired
	private PledgeStatusService pledgeStatus;

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
	private Long stockId;
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
		createIfMissing("ps-amy");
		createIfMissing("ps-ben");
		loginAs("ps-amy");
		today = LocalDate.now(clock);
		settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0056', '元大高股息', "
				+ "'ETF', 4, NOW())");
		stockId = jdbc.queryForObject("SELECT id FROM stock WHERE code = '0056'", Long.class);
		jdbc.update("INSERT INTO stock_price (stock_id, trade_date, close_price) VALUES (?, ?, 60)", stockId, today);
		pledge = pledge("玉山質押");
		trades.create(new TradeForm(TradeType.OPENING, today.minusDays(10), "0056", null, 1000L,
				new BigDecimal("30"), null, null, null, List.of()));
		loans.setCollateral(pledge.getId(), "0056", 1000L);
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void outstandingPledgeIsActive() {
		assertThat(pledgeStatus.activePledgeLoanIds()).containsExactly(pledge.getId());
		assertThat(pledgeStatus.pledgedSharesByStock()).isEqualTo(Map.of(stockId, 1000L));
		assertThat(pledgeStatus.pledgedSharesOtherThan(pledge.getId(), stockId)).isZero();
	}

	/** 只還一部分：還在質押中。 */
	@Test
	void partlyRepaidPledgeStaysActive() {
		loans.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("100000"), settlement));

		assertThat(pledgeStatus.activePledgeLoanIds()).containsExactly(pledge.getId());
	}

	/** 質押 1,000 股、全部還清 → 不再是質押中，賣 1,000 股成功；擔保列不刪。 */
	@Test
	void repaidLoanDoesNotBlockSell() {
		loans.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("400000"), settlement));

		assertThat(pledgeStatus.activePledgeLoanIds()).isEmpty();
		assertThat(pledgeStatus.pledgedSharesByStock()).isEmpty();
		trades.create(sell(1000L));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pledge_collateral WHERE loan_id = ?", Long.class,
				pledge.getId())).isEqualTo(1L);
	}

	/** 再作廢那筆還本 → 剩餘本金又不是 0，擔保自動恢復，賣出被擋（原訊息）。 */
	@Test
	void pledgeComesBackWhenRepaymentVoided() {
		Long repayment = loans.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("400000"), settlement))
			.getId();

		cashMovements.voidMovement(repayment, "記錯");

		assertThat(pledgeStatus.activePledgeLoanIds()).containsExactly(pledge.getId());
		assertThatThrownBy(() -> trades.create(sell(1000L))).hasMessage(SELL_BLOCKED);
	}

	/** 已還清借款的擔保不算進其他質押的擔保合計（設定擔保時）。 */
	@Test
	void repaidCollateralNotCountedForOtherLoans() {
		Loan second = pledge("第二筆質押");
		loans.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("400000"), settlement));

		assertThat(pledgeStatus.pledgedSharesOtherThan(second.getId(), stockId)).isZero();
		assertThat(loans.collateralOfOtherLoans(second.getId())).isEmpty();
		loans.setCollateral(second.getId(), "0056", 1000L);
		assertThat(pledgeStatus.pledgedSharesOtherThan(pledge.getId(), stockId)).isEqualTo(1000L);
	}

	/** 新撥款還沒有撥款紀錄（本金 0、沒有還本）：不是還清，還在質押中，擋賣出、卡片不顯示已還清。 */
	@Test
	void newPledgeWithoutDisbursementStaysPledged() {
		Loan fresh = freshPledgeHoldingAllShares();
		jdbc.update("DELETE FROM cash_movement WHERE loan_id = ?", fresh.getId());

		assertNotRepaid(fresh);
	}

	/** 撥款日記在未來（今天本金還是 0）：一樣還在質押中。 */
	@Test
	void futureDatedDisbursementStaysPledged() {
		Loan fresh = freshPledgeHoldingAllShares();
		jdbc.update("UPDATE cash_movement SET movement_date = ? WHERE loan_id = ? AND type = 'LOAN_DISBURSEMENT'",
				today.plusDays(3), fresh.getId());
		jdbc.update("DELETE FROM cash_movement WHERE loan_id = ? AND type <> 'LOAN_DISBURSEMENT'", fresh.getId());

		assertNotRepaid(fresh);
	}

	/** 還清 → 賣掉全部擔保股票 → 作廢那筆還本：又回到質押中但股票已經賣了 → 擋下，什麼都不變。 */
	@Test
	void voidingRepaymentAfterSellingCollateralIsBlocked() {
		Long repayment = loans.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("400000"), settlement))
			.getId();
		trades.create(sell(1000L));

		assertThatThrownBy(() -> cashMovements.voidMovement(repayment, "記錯"))
			.isInstanceOf(CashValidationException.class)
			.hasMessage(returned("這筆還本作廢／修改後"));
		assertThatThrownBy(() -> cashMovements.editLoanMovement(repayment,
				new LoanMovementForm(today, new BigDecimal("300000")), "改金額"))
			.isInstanceOf(CashValidationException.class)
			.hasMessage(returned("這筆還本作廢／修改後"));

		assertThat(jdbc.queryForObject("SELECT voided FROM cash_movement WHERE id = ?", Boolean.class, repayment))
			.isFalse();
		assertThat(jdbc.queryForObject("SELECT amount FROM cash_movement WHERE id = ?", BigDecimal.class, repayment))
			.isEqualByComparingTo("400000");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement_revision WHERE cash_movement_id = ?", Long.class,
				repayment)).isZero();
		assertThat(pledgeStatus.activePledgeLoanIds()).isEmpty();
	}

	/** 還清 → 賣掉擔保股票 → 再動撥：一樣又回到質押中 → 擋下，不記撥款。 */
	@Test
	void redrawAfterSellingCollateralIsBlocked() {
		loans.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("400000"), settlement));
		trades.create(sell(1000L));

		assertThatThrownBy(() -> loans.redraw(pledge.getId(), new PrincipalForm(today, new BigDecimal("100000"),
				settlement)))
			.isInstanceOf(LoanValidationException.class)
			.hasMessage(returned("再動撥後"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movement WHERE loan_id = ? AND type = "
				+ "'LOAN_DISBURSEMENT'", Long.class, pledge.getId())).isZero();
	}

	/** 只看登入者自己的借款。 */
	@Test
	void perUser() {
		loginAs("ps-ben");

		assertThat(pledgeStatus.activePledgeLoanIds()).isEmpty();
		assertThat(pledgeStatus.pledgedSharesByStock()).isEmpty();
	}

	private static String returned(String what) {
		return what + "，「玉山質押」又回到質押中，0056 擔保 1,000 股超過目前持股 0 股；請先到資金來源頁調整擔保股數";
	}

	/** 拿掉第一筆質押的擔保，新記一筆今天撥款的新撥款質押，押全部 1,000 股。 */
	private Loan freshPledgeHoldingAllShares() {
		loans.setCollateral(pledge.getId(), "0056", 0L);
		Loan fresh = loans.create(new LoanForm("新撥款質押", LoanType.PLEDGE, "玉山銀行", LoanOrigin.NEW,
				new BigDecimal("200000"), today, new BigDecimal("200000"), new BigDecimal("3"), settlement, settlement,
				25, null, null, null));
		loans.setCollateral(fresh.getId(), "0056", 1000L);
		return fresh;
	}

	/** 本金 0 但沒有還本：還在質押中、擋賣出、卡片不是已還清。 */
	private void assertNotRepaid(Loan loan) {
		assertThat(pledgeStatus.activePledgeLoanIds()).contains(loan.getId());
		assertThatThrownBy(() -> trades.create(sell(1000L))).hasMessage(SELL_BLOCKED);
		LoanCard card = loans.fundingView().loans().stream().filter(c -> c.id().equals(loan.getId())).findFirst()
			.orElseThrow();
		assertThat(card.remaining()).isEqualByComparingTo("0");
		assertThat(card.paidOff()).isFalse();
		assertThat(card.collateralReleased()).isFalse();
	}

	private TradeForm sell(long shares) {
		return new TradeForm(TradeType.SELL, today, "0056", settlement, shares, new BigDecimal("60"), null, null, null,
				List.of());
	}

	private Loan pledge(String name) {
		return loans.create(new LoanForm(name, LoanType.PLEDGE, "玉山銀行", LoanOrigin.EXISTING, new BigDecimal("400000"),
				today.minusDays(1), new BigDecimal("400000"), new BigDecimal("3"), null, settlement, 25, null, null,
				null));
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
