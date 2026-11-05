package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.LoanForm;
import com.sideproject.stockledger.dto.PageNotice;
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

/** 頁面最上方的提醒（設計文件 2026-10-25-onboarding 3.3）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class NoticeServiceTest {

	private static final String ONBOARDING_TEXT = "第一次使用引導還有沒完成的步驟";

	@Autowired
	private NoticeService notices;

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

	private LocalDate today;
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
		jdbc.update("DELETE FROM rate_setting WHERE user_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		jdbc.update("DELETE FROM stock_price");
		jdbc.update("DELETE FROM dividend");
		jdbc.update("DELETE FROM stock");
		createIfMissing("nt-amy");
		jdbc.update("UPDATE app_user SET onboarding_step = 'DONE' WHERE username = 'nt-amy'");
		loginAs("nt-amy");
		today = LocalDate.now(clock);
		settlement = accounts.create(new CashAccountForm("玉山交割戶", CashAccountType.SETTLEMENT, "玉山證券")).getId();
		jdbc.update("INSERT INTO stock (code, name, kind, dividends_per_year, updated_at) VALUES ('0056', '元大高股息', "
				+ "'ETF', 4, NOW())");
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void noticeWhileOnboarding() {
		jdbc.update("UPDATE app_user SET onboarding_step = 'LOANS' WHERE username = 'nt-amy'");

		assertThat(notices.overviewNotices()).containsExactly(new PageNotice(ONBOARDING_TEXT, "/welcome/loans"));
		assertThat(notices.stockAndFundingNotices()).containsExactly(new PageNotice(ONBOARDING_TEXT, "/welcome/loans"));
	}

	@Test
	void noNoticeWhenDone() {
		assertThat(notices.overviewNotices()).isEmpty();
		assertThat(notices.stockAndFundingNotices()).isEmpty();
	}

	/** 沒有擔保股票的質押只在股票、資金來源頁提醒；總覽不放。 */
	@Test
	void pledgeWithoutCollateralNotice() {
		Loan pledge = pledge("玉山質押");

		assertThat(notices.stockAndFundingNotices()).containsExactly(
				new PageNotice("「玉山質押」還沒設定擔保股票，維持率無法計算", "/funding#loan-" + pledge.getId()));
		assertThat(notices.overviewNotices()).isEmpty();
	}

	@Test
	void creditLoanHasNoCollateralNotice() {
		loans.create(new LoanForm("土銀信貸", LoanType.CREDIT, "土地銀行", LoanOrigin.NEW, new BigDecimal("200000"),
				today, new BigDecimal("200000"), new BigDecimal("2.45"), settlement, settlement, 1, 84,
				java.time.YearMonth.from(today).plusMonths(1), null));

		assertThat(notices.stockAndFundingNotices()).isEmpty();
	}

	@Test
	void voidedPledgeHasNoNotice() {
		Loan pledge = pledge("玉山質押");

		loans.voidLoan(pledge.getId(), "輸入錯誤");

		assertThat(notices.stockAndFundingNotices()).isEmpty();
	}

	/** 已還清的質押不是質押中，不提醒設定擔保股票（設計文件 2026-11-04-pledged-stocks 第 6 節）。 */
	@Test
	void noticeSkipsRepaidPledge() {
		Loan pledge = pledge("玉山質押");
		assertThat(notices.stockAndFundingNotices()).hasSize(1);

		loans.repay(pledge.getId(), new PrincipalForm(today, new BigDecimal("400000"), settlement));

		assertThat(notices.stockAndFundingNotices()).isEmpty();
	}

	@Test
	void noticeGoneAfterCollateralSet() {
		Loan pledge = pledge("玉山質押");
		trades.create(new TradeForm(TradeType.OPENING, today.minusDays(10), "0056", null, 1000L,
				new BigDecimal("30"), null, null, null, List.of()));
		assertThat(notices.stockAndFundingNotices()).hasSize(1);

		loans.setCollateral(pledge.getId(), "0056", 1000L);

		assertThat(notices.stockAndFundingNotices()).isEmpty();
	}

	@Test
	void collateralNoticeIsPerUser() {
		pledge("玉山質押");
		createIfMissing("nt-ben");
		jdbc.update("UPDATE app_user SET onboarding_step = 'DONE' WHERE username = 'nt-ben'");

		loginAs("nt-ben");

		assertThat(notices.stockAndFundingNotices()).isEmpty();
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
