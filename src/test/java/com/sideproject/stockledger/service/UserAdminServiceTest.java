package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.dto.NewUserForm;
import com.sideproject.stockledger.dto.ResetPasswordForm;
import com.sideproject.stockledger.dto.UserRow;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.security.AppUserDetailsService;

/** 使用者管理（spec 4.10-2）：只有管理者能建立帳號、重設密碼。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class UserAdminServiceTest {

	private static final String PASSWORD = "password123";

	@Autowired
	private UserAdminService service;

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
		createIfMissing("boss", Role.ADMIN);
		createIfMissing("amy", Role.USER);
		loginAs("boss");
	}

	@AfterEach
	void logout() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void adminCreatesUserWithHashedPassword() {
		service.create(new NewUserForm("ua-bob", "鮑伯", "bob-secret-1", Role.USER));

		AppUser bob = users.findByUsername("ua-bob").orElseThrow();
		assertThat(bob.getPasswordHash()).startsWith("{bcrypt}");
		assertThat(encoder.matches("bob-secret-1", bob.getPasswordHash())).isTrue();
		assertThat(bob.getDisplayName()).isEqualTo("鮑伯");
		assertThat(bob.getRole()).isEqualTo(Role.USER);
	}

	@Test
	void duplicateUsernameIgnoringCaseIsRejected() {
		assertThatThrownBy(() -> service.create(new NewUserForm("AMY ", "另一個艾咪", PASSWORD, Role.USER)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("帳號已存在");
	}

	/** 注音輸入法的全形空白也要去掉，否則 "ua-cathy　" 和 "ua-cathy" 會變成兩個帳號。 */
	@Test
	void fullWidthSpaceInUsernameIsStripped() {
		service.create(new NewUserForm("ua-cathy　", "凱西", PASSWORD, Role.USER));

		assertThat(users.existsByUsername("ua-cathy")).isTrue();
		assertThatThrownBy(() -> service.create(new NewUserForm("ua-cathy", "凱西 2", PASSWORD, Role.USER)))
			.hasMessage("帳號已存在");
	}

	/** BCrypt 只吃 72 bytes：25 個中文字是 75 bytes，字數沒超過 72 也要擋，否則會變 500 錯誤頁。 */
	@Test
	void passwordOver72BytesRejected() {
		String chinese25 = "密".repeat(25);

		assertThatThrownBy(() -> service.create(new NewUserForm("ua-long", "長密碼", chinese25, Role.USER)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("密碼太長");
		assertThat(users.existsByUsername("ua-long")).isFalse();
	}

	@Test
	void resetPasswordUnlocksUser() {
		Long id = service.create(new NewUserForm("ua-dave", "大衛", PASSWORD, Role.USER)).getId();
		jdbc.update("UPDATE app_user SET failed_login_count = 3, locked_until = ? WHERE id = ?",
				LocalDateTime.now(clock).plusMinutes(10), id);

		service.resetPassword(id, new ResetPasswordForm("new-secret-99"));

		AppUser dave = users.findByUsername("ua-dave").orElseThrow();
		assertThat(encoder.matches("new-secret-99", dave.getPasswordHash())).isTrue();
		assertThat(dave.getLockedUntil()).isNull();
		assertThat(dave.getFailedLoginCount()).isZero();
	}

	@Test
	void resetPasswordForUnknownUserIsNotFound() {
		assertThatThrownBy(() -> service.resetPassword(999_999L, new ResetPasswordForm("new-secret-99")))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void resetPasswordOver72BytesRejected() {
		Long id = service.create(new NewUserForm("ua-erin", "艾琳", PASSWORD, Role.USER)).getId();

		assertThatThrownBy(() -> service.resetPassword(id, new ResetPasswordForm("密".repeat(25))))
			.hasMessageContaining("密碼太長");
	}

	@Test
	void listShowsLockStatus() {
		Long id = service.create(new NewUserForm("ua-frank", "法蘭克", PASSWORD, Role.USER)).getId();
		jdbc.update("UPDATE app_user SET locked_until = ? WHERE id = ?", LocalDateTime.now(clock).plusMinutes(10), id);

		UserRow frank = service.listAll().stream().filter(u -> u.username().equals("ua-frank")).findFirst().orElseThrow();
		assertThat(frank.statusLabel()).isEqualTo("鎖定中");
		assertThat(frank.roleLabel()).isEqualTo("一般使用者");
		UserRow boss = service.listAll().stream().filter(u -> u.username().equals("boss")).findFirst().orElseThrow();
		assertThat(boss.statusLabel()).isEqualTo("正常");
		assertThat(boss.roleLabel()).isEqualTo("管理者");
	}

	/** 帳號中間有空白、不換行空白、控制字元時，登入時打不出來，直接拒絕（審核 M4）。 */
	@ParameterizedTest
	@ValueSource(strings = { "ua-bob smith", "ua-nbsp x", "ua-tab\tx", "ua-zw​x" })
	void usernameWithInvisibleOrSpaceCharactersRejected(String username) {
		assertThatThrownBy(() -> service.create(new NewUserForm(username, "名稱", PASSWORD, Role.USER)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("帳號不能有空白");
	}

	/** "İ" 轉小寫會變成 2 個字元：26 個字轉完是 52 個字，超過資料庫 50 字（審核 M3）。 */
	@Test
	void usernameTooLongAfterNormalizationRejected() {
		assertThatThrownBy(() -> service.create(new NewUserForm("İ".repeat(26), "名稱", PASSWORD, Role.USER)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("帳號最多 50 個字");
	}

	/** Service 自己也要擋（之後的 @RestController 不一定有表單檢查，審核 M5）。 */
	@Test
	void blankDisplayNameOrMissingRoleRejected() {
		assertThatThrownBy(() -> service.create(new NewUserForm("ua-blank", "　", PASSWORD, Role.USER)))
			.hasMessage("請輸入顯示名稱");
		assertThatThrownBy(() -> service.create(new NewUserForm("ua-norole", "名稱", PASSWORD, null)))
			.hasMessage("請選擇角色");
		assertThat(users.existsByUsername("ua-blank")).isFalse();
		assertThat(users.existsByUsername("ua-norole")).isFalse();
	}

	/** 網址擋一層（SecurityConfig），Service 方法再擋一層（@PreAuthorize）。 */
	@Test
	void nonAdminCallIsDenied() {
		loginAs("amy");

		assertThatThrownBy(() -> service.create(new NewUserForm("ua-hack", "駭客", PASSWORD, Role.ADMIN)))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.listAll()).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.resetPassword(1L, new ResetPasswordForm("new-secret-99")))
			.isInstanceOf(AccessDeniedException.class);
		assertThat(users.existsByUsername("ua-hack")).isFalse();
	}

	private void loginAs(String username) {
		UserDetails details = userDetailsService.loadUserByUsername(username);
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
	}

	private void createIfMissing(String username, Role role) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode(PASSWORD), username, role, LocalDateTime.now(clock)));
		}
	}

}
