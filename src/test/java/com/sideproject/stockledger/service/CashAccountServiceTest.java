package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;

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
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashAccountType;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 現金帳戶的資料隔離：每個人只看得到、改得到自己的帳戶（spec 4.10-3）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CashAccountServiceTest {

	@Autowired
	private CashAccountService service;

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
		// 先刪參照帳戶的現金異動，否則外鍵會擋下
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		createIfMissing("amy");
		createIfMissing("ben");
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void createdAccountBelongsToCurrentUser() {
		loginAs("amy");

		service.create(form("國泰交割戶"));

		assertThat(service.listMine()).singleElement()
			.satisfies(a -> assertThat(a.getUserId()).isEqualTo(idOf("amy")));
	}

	@Test
	void otherUserCannotSeeAccounts() {
		loginAs("amy");
		service.create(form("國泰交割戶"));

		loginAs("ben");

		assertThat(service.listMine()).isEmpty();
	}

	@Test
	void otherUserCannotGetById() {
		loginAs("amy");
		Long amyAccountId = service.create(form("國泰交割戶")).getId();

		loginAs("ben");

		assertThatThrownBy(() -> service.getMine(amyAccountId)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void otherUserCannotUpdate() {
		loginAs("amy");
		Long amyAccountId = service.create(form("國泰交割戶")).getId();

		loginAs("ben");
		assertThatThrownBy(() -> service.update(amyAccountId, form("被 ben 改掉")))
			.isInstanceOf(NotFoundException.class);

		loginAs("amy");
		assertThat(service.getMine(amyAccountId).getName()).isEqualTo("國泰交割戶");
	}

	@Test
	void duplicateNameRejectedPerUser() {
		loginAs("amy");
		service.create(form("國泰交割戶"));

		assertThatThrownBy(() -> service.create(form("國泰交割戶")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("帳戶名稱已存在");

		loginAs("ben");
		service.create(form("國泰交割戶"));
		assertThat(service.listMine()).hasSize(1);
	}

	@Test
	void updateKeepingSameNameSucceeds() {
		loginAs("amy");
		Long id = service.create(form("國泰交割戶")).getId();

		service.update(id, new CashAccountForm("國泰交割戶", CashAccountType.SETTLEMENT, "國泰證券松江分公司"));

		assertThat(service.getMine(id).getInstitution()).isEqualTo("國泰證券松江分公司");
	}

	@Test
	void updateToAnotherExistingNameRejected() {
		loginAs("amy");
		service.create(form("國泰交割戶"));
		Long id = service.create(form("薪資帳戶")).getId();

		assertThatThrownBy(() -> service.update(id, form("國泰交割戶")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("帳戶名稱已存在");
	}

	@Test
	void namesAreTrimmed() {
		loginAs("amy");
		service.create(form("國泰交割戶"));

		assertThatThrownBy(() -> service.create(form("  國泰交割戶  ")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/** 注音輸入法常打出全形空白（U+3000）；trim() 去不掉它，要用 strip()（審核發現的問題）。 */
	@Test
	void fullWidthSpacesAreStripped() {
		loginAs("amy");
		service.create(form("國泰交割戶"));

		assertThatThrownBy(() -> service.create(form("國泰交割戶　")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("帳戶名稱已存在");
	}

	private CashAccountForm form(String name) {
		return new CashAccountForm(name, CashAccountType.SETTLEMENT, "國泰證券");
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
