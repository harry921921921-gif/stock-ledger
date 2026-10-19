package com.sideproject.stockledger.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

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
		return accounts.save(CashAccount.create(userId, form.name(), form.type(), form.institution(),
				LocalDateTime.now(clock)));
	}

	@Transactional
	public CashAccount update(Long id, CashAccountForm form) {
		CashAccount account = findMine(id);
		if (accounts.existsByUserIdAndNameAndIdNot(account.getUserId(), CashAccount.normalizeName(form.name()), id)) {
			throw new IllegalArgumentException(DUPLICATE_NAME);
		}
		account.change(form.name(), form.type(), form.institution());
		return account;
	}

	private CashAccount findMine(Long id) {
		return accounts.findByIdAndUserId(id, currentUser.id())
			.orElseThrow(() -> new NotFoundException("找不到這個帳戶"));
	}

}
