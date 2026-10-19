package com.sideproject.stockledger.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 現金帳戶：證券交割戶或銀行活存（spec 4.6、6 CashAccount）。餘額由現金異動推算，不存在這裡。 */
@Entity
@Table(name = "cash_account")
public class CashAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 擁有者。建立後不可更改，所以沒有 setter。 */
	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(nullable = false, length = 50)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CashAccountType type;

	@Column(nullable = false, length = 50)
	private String institution;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected CashAccount() {
		// JPA 需要
	}

	public static CashAccount create(Long userId, String name, CashAccountType type, String institution,
			LocalDateTime now) {
		CashAccount account = new CashAccount();
		account.userId = userId;
		account.createdAt = now;
		account.change(name, type, institution);
		return account;
	}

	public void change(String name, CashAccountType type, String institution) {
		this.name = normalizeName(name);
		this.type = type;
		this.institution = normalizeName(institution);
	}

	/**
	 * 名稱比對規則：去掉前後空白。建立、修改、檢查重複都要經過這裡。
	 * 用 strip() 不用 trim()：trim() 去不掉注音輸入法常打出的全形空白（U+3000）。
	 */
	public static String normalizeName(String raw) {
		return raw == null ? null : raw.strip();
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getName() {
		return name;
	}

	public CashAccountType getType() {
		return type;
	}

	public String getInstitution() {
		return institution;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
