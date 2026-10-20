package com.sideproject.stockledger.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.dto.NewUserForm;
import com.sideproject.stockledger.dto.ResetPasswordForm;
import com.sideproject.stockledger.dto.UserRow;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.repository.AppUserRepository;

/**
 * 使用者管理（spec 4.10-2）：管理者建立帳號、重設密碼。
 * 網址層（SecurityConfig 的 /settings/users/**）和這裡的方法層（@PreAuthorize）都只允許管理者，
 * 之後加 @RestController 時就算忘了設定網址規則，也不會被一般使用者呼叫。
 * 使用者表本身是共用的（不帶 user_id），所以這裡不用 CurrentUser 過濾。
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminService {

	/** BCrypt 只處理前 72 bytes；新版 Spring Security 超過時會直接丟例外。 */
	private static final int BCRYPT_MAX_BYTES = 72;

	/** V1 migration 裡的唯一限制名稱。 */
	private static final String UNIQUE_USERNAME_CONSTRAINT = "uk_app_user_username";

	static final String DUPLICATE_USERNAME = "帳號已存在";

	/** app_user.username 與 display_name 都是 VARCHAR(50)（以字元計）。 */
	private static final int MAX_NAME_LENGTH = 50;

	private final AppUserRepository users;
	private final PasswordEncoder encoder;
	private final Clock clock;

	public UserAdminService(AppUserRepository users, PasswordEncoder encoder, Clock clock) {
		this.users = users;
		this.encoder = encoder;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<UserRow> listAll() {
		LocalDateTime now = LocalDateTime.now(clock);
		return users.findAll().stream()
			.sorted(Comparator.comparing(AppUser::getId))
			.map(u -> new UserRow(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole().label(),
					statusLabel(u, now)))
			.toList();
	}

	@Transactional
	public AppUser create(NewUserForm form) {
		String username = AppUser.normalizeUsername(form.username());
		if (username == null || username.isEmpty()) {
			throw new UserValidationException("請輸入帳號");
		}
		if (username.codePoints().anyMatch(UserAdminService::isSpaceOrInvisible)) {
			throw new UserValidationException("帳號不能有空白或看不見的字元");
		}
		// 用正規化後的長度檢查：少數字元轉小寫會變長（例如 "İ" 變 2 個字元）
		if (username.codePointCount(0, username.length()) > MAX_NAME_LENGTH) {
			throw new UserValidationException("帳號最多 50 個字");
		}
		String displayName = form.displayName() == null ? "" : form.displayName().strip();
		if (displayName.isEmpty()) {
			throw new UserValidationException("請輸入顯示名稱");
		}
		if (form.role() == null) {
			throw new UserValidationException("請選擇角色");
		}
		checkPasswordLength(form.password());
		if (users.existsByUsername(username)) {
			throw new UserValidationException(DUPLICATE_USERNAME);
		}
		try {
			return users.saveAndFlush(AppUser.create(username, encoder.encode(form.password()),
					displayName, form.role(), LocalDateTime.now(clock)));
		}
		catch (DataIntegrityViolationException ex) {
			// 連點兩次「建立」時，兩個請求可能同時通過上面的檢查，由資料庫唯一限制擋下第二筆
			String message = ex.getMostSpecificCause().getMessage();
			if (message != null && message.endsWith("for key 'app_user." + UNIQUE_USERNAME_CONSTRAINT + "'")) {
				throw new UserValidationException(DUPLICATE_USERNAME);
			}
			throw ex;
		}
	}

	/** 重設密碼並解除鎖定（使用者被鎖住、忘記密碼時由管理者處理）。 */
	@Transactional
	public AppUser resetPassword(Long userId, ResetPasswordForm form) {
		AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("找不到這個使用者"));
		checkPasswordLength(form.newPassword());
		user.changePassword(encoder.encode(form.newPassword()));
		return user;
	}

	/** 給畫面組訊息用，例如「重設 bob 的密碼失敗」。 */
	@Transactional(readOnly = true)
	public String usernameOf(Long userId) {
		return users.findById(userId).map(AppUser::getUsername)
			.orElseThrow(() -> new NotFoundException("找不到這個使用者"));
	}

	/** 空白（含全形、不換行空白）、控制字元、零寬字元：登入時打不出來或看不到，帳號裡不允許。 */
	private static boolean isSpaceOrInvisible(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
				|| Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT;
	}

	private static void checkPasswordLength(String password) {
		if (password == null || password.length() < 8) {
			throw new UserValidationException("密碼至少 8 個字");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new UserValidationException("密碼太長：最多 72 個英數字（中文字每個算 3 個）");
		}
	}

	private static String statusLabel(AppUser user, LocalDateTime now) {
		if (user.isDisabled()) {
			return "已停用";
		}
		return user.isLockedAt(now) ? "鎖定中" : "正常";
	}

}
