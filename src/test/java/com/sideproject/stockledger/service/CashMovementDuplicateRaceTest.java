package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.dto.OpeningForm;
import com.sideproject.stockledger.dto.ReconcileForm;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/**
 * 連點兩次時，兩個請求可能同時通過「已經有初始現金餘額／這天已經對帳」的檢查，第二筆才被資料庫唯一限制擋下。
 * 讓檢查假裝回答「沒有」，直接撞上唯一限制；預期仍是中文訊息，不是 500。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CashMovementDuplicateRaceTest {

	@Autowired
	private CashMovementService service;

	@Autowired
	private CashAccountService accounts;

	@MockitoSpyBean
	private CashMovementRepository movements;

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

	private Long bank;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		if (!users.existsByUsername("race-cash")) {
			users.save(AppUser.create("race-cash", encoder.encode("password123"), "race-cash", Role.USER,
					LocalDateTime.now(clock)));
		}
		UserDetails details = userDetailsService.loadUserByUsername("race-cash");
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
		bank = accounts.create(new CashAccountForm("薪資帳戶", CashAccountType.BANK_DEMAND, "國泰世華")).getId();
		today = LocalDate.now(clock);
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void openingRaceShowsChineseMessage() {
		service.setOpening(bank, new OpeningForm(today, new BigDecimal("1000")));
		doReturn(Optional.empty()).when(movements).findByUserIdAndAccountIdAndTypeAndVoidedFalse(any(), any(), any());

		assertThatThrownBy(() -> service.setOpening(bank, new OpeningForm(today, new BigDecimal("1000"))))
			.isInstanceOf(CashValidationException.class)
			.hasMessage("這個帳戶已經有初始現金餘額");
	}

	/**
	 * 兩個請求同時作廢同一筆：讀取時要鎖住那一列，第二個才會看到「已作廢」，
	 * 否則兩個都會寫一筆 VOID 修改紀錄（審核 Important）。
	 */
	@Test
	void concurrentVoidWritesOneRevision() throws Exception {
		Long id = service.reconcile(bank, new ReconcileForm(today, new BigDecimal("900"))).getId();
		var auth = SecurityContextHolder.getContext().getAuthentication();
		var start = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.Callable<Boolean> voidIt = () -> {
			SecurityContextHolder.getContext().setAuthentication(auth);
			start.await();
			try {
				service.voidMovement(id, null);
				return true;
			}
			catch (CashValidationException ex) {
				return false;
			}
			finally {
				SecurityContextHolder.clearContext();
			}
		};
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var first = pool.submit(voidIt);
			var second = pool.submit(voidIt);
			start.countDown();
			int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);

			org.assertj.core.api.Assertions.assertThat(successes).isEqualTo(1);
			org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM cash_movement_revision WHERE cash_movement_id = ?", Integer.class, id))
				.isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void reconcileRaceShowsChineseMessage() {
		service.reconcile(bank, new ReconcileForm(today, new BigDecimal("900")));
		doReturn(false).when(movements)
			.existsByUserIdAndAccountIdAndTypeAndMovementDateAndVoidedFalse(any(), any(), any(), any());

		assertThatThrownBy(() -> service.reconcile(bank, new ReconcileForm(today, new BigDecimal("800"))))
			.isInstanceOf(CashValidationException.class)
			.hasMessage("這個帳戶這一天已經輸入過實際餘額");
	}

}
