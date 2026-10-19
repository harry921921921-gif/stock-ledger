package com.sideproject.stockledger.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.dto.CashAccountForm;
import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.repository.CashAccountRepository;
import com.sideproject.stockledger.security.CurrentUser;

/**
 * 現金帳戶（spec 4.6）。方法參數故意沒有 userId：一律用 {@link CurrentUser#id()}，
 * 所以別人的帳戶在這裡等於「不存在」。
 */
@Service
public class CashAccountService {

	static final String DUPLICATE_NAME = "帳戶名稱已存在";

	/** V2 migration 裡的唯一限制名稱。 */
	private static final String UNIQUE_NAME_CONSTRAINT = "uk_cash_account_user_name";

	private final CashAccountRepository accounts;
	private final CurrentUser currentUser;
	private final Clock clock;

	public CashAccountService(CashAccountRepository accounts, CurrentUser currentUser, Clock clock) {
		this.accounts = accounts;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<CashAccount> listMine() {
		return accounts.findByUserIdOrderByIdAsc(currentUser.id());
	}

	@Transactional(readOnly = true)
	public CashAccount getMine(Long id) {
		return findMine(id);
	}

	@Transactional
	public CashAccount create(CashAccountForm form) {
		Long userId = currentUser.id();
		if (accounts.existsByUserIdAndName(userId, CashAccount.normalizeName(form.name()))) {
			throw new IllegalArgumentException(DUPLICATE_NAME);
		}
		try {
			return accounts.saveAndFlush(CashAccount.create(userId, form.name(), form.type(), form.institution(),
					LocalDateTime.now(clock)));
		}
		catch (DataIntegrityViolationException ex) {
			throw translateDuplicate(ex);
		}
	}

	@Transactional
	public CashAccount update(Long id, CashAccountForm form) {
		CashAccount account = findMine(id);
		if (accounts.existsByUserIdAndNameAndIdNot(account.getUserId(), CashAccount.normalizeName(form.name()), id)) {
			throw new IllegalArgumentException(DUPLICATE_NAME);
		}
		account.change(form.name(), form.type(), form.institution());
		try {
			accounts.flush();
		}
		catch (DataIntegrityViolationException ex) {
			throw translateDuplicate(ex);
		}
		return account;
	}

	/**
	 * 上面的 existsBy 檢查擋得住一般情況；連點兩次「儲存」或兩個分頁同時送出時，兩個請求可能同時通過檢查，
	 * 這時由資料庫的唯一限制擋下第二筆。把它轉成同一句「帳戶名稱已存在」，而不是 500 錯誤頁。
	 * 其他資料庫錯誤照原樣丟出，不要誤報成名稱重複。
	 */
	private static RuntimeException translateDuplicate(DataIntegrityViolationException ex) {
		String message = ex.getMostSpecificCause().getMessage();
		// MySQL 訊息：Duplicate entry '<userId>-<名稱>' for key 'cash_account.uk_cash_account_user_name'
		// 中間夾著使用者輸入的名稱，所以只比對結尾，避免名稱裡剛好含有限制名稱而誤判
		if (message != null && message.endsWith("for key 'cash_account." + UNIQUE_NAME_CONSTRAINT + "'")) {
			return new IllegalArgumentException(DUPLICATE_NAME, ex);
		}
		return ex;
	}

	private CashAccount findMine(Long id) {
		return accounts.findByIdAndUserId(id, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這個帳戶"));
	}

}
