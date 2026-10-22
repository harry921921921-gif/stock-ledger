package com.sideproject.stockledger.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

import com.sideproject.stockledger.calculator.CashEvent;

/**
 * 一筆現金事件（spec 4.6、6 CashMovement、DR-032）。只存原始輸入；餘額與餘額調整由事件推算。
 * 不硬刪除：作廢用 voided 旗標。資料表另有兩個「產生欄位」讓資料庫保證唯一性，這裡不對應。
 */
@Entity
@Table(name = "cash_movement")
@DynamicUpdate
public class CashMovement {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private CashMovementType type;

	@Column(name = "movement_date", nullable = false)
	private LocalDate movementDate;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Column(name = "to_account_id")
	private Long toAccountId;

	@Column(precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(name = "actual_balance", precision = 19, scale = 4)
	private BigDecimal actualBalance;

	@Column(length = 100)
	private String note;

	@Column(nullable = false)
	private boolean voided;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected CashMovement() {
		// JPA 需要
	}

	/** 存入、支出、互轉、初始現金餘額。toAccountId 只有互轉才有。 */
	public static CashMovement ofAmount(Long userId, CashMovementType type, LocalDate date, Long accountId,
			Long toAccountId, BigDecimal amount, String note, LocalDateTime now) {
		if (type == CashMovementType.RECONCILE) {
			throw new IllegalArgumentException("對帳請用 reconcile()");
		}
		CashMovement m = new CashMovement();
		m.userId = userId;
		m.type = type;
		m.movementDate = date;
		m.accountId = accountId;
		m.toAccountId = type == CashMovementType.TRANSFER ? toAccountId : null;
		m.amount = amount;
		m.note = note;
		m.createdAt = now;
		return m;
	}

	/** 對帳：存使用者輸入的實際餘額，不存差額。 */
	public static CashMovement reconcile(Long userId, LocalDate date, Long accountId, BigDecimal actualBalance,
			LocalDateTime now) {
		CashMovement m = new CashMovement();
		m.userId = userId;
		m.type = CashMovementType.RECONCILE;
		m.movementDate = date;
		m.accountId = accountId;
		m.actualBalance = actualBalance;
		m.createdAt = now;
		return m;
	}

	/** 修改存入、支出、互轉、初始現金餘額（類型不能改；呼叫端先寫修改紀錄）。 */
	public void change(LocalDate date, Long newAccountId, Long newToAccountId, BigDecimal newAmount, String newNote) {
		requireNotVoided();
		if (type == CashMovementType.RECONCILE) {
			throw new IllegalStateException("對帳請用 changeReconcile()");
		}
		this.movementDate = date;
		this.accountId = newAccountId;
		this.toAccountId = type == CashMovementType.TRANSFER ? newToAccountId : null;
		this.amount = newAmount;
		this.note = newNote;
	}

	/** 修改對帳：改的是當時輸入的實際餘額與日期。 */
	public void changeReconcile(LocalDate date, BigDecimal newActualBalance) {
		requireNotVoided();
		if (type != CashMovementType.RECONCILE) {
			throw new IllegalStateException("只有對帳可以改實際餘額");
		}
		this.movementDate = date;
		this.actualBalance = newActualBalance;
	}

	/** 作廢：不硬刪除，之後不計入任何金額（spec 4.9-1）。 */
	public void markVoided() {
		requireNotVoided();
		this.voided = true;
	}

	private void requireNotVoided() {
		if (voided) {
			throw new IllegalStateException("已作廢的異動不能再修改");
		}
	}

	/** 交給 CashLedgerCalculator 計算用。 */
	public CashEvent toEvent() {
		return new CashEvent(id, type, movementDate, accountId, toAccountId, amount, actualBalance);
	}

	/** 這筆事件有沒有牽涉到這個帳戶（互轉的轉出與轉入都算）。 */
	public boolean involves(Long account) {
		return accountId.equals(account) || account.equals(toAccountId);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public CashMovementType getType() {
		return type;
	}

	public LocalDate getMovementDate() {
		return movementDate;
	}

	public Long getAccountId() {
		return accountId;
	}

	public Long getToAccountId() {
		return toAccountId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public BigDecimal getActualBalance() {
		return actualBalance;
	}

	public String getNote() {
		return note;
	}

	public boolean isVoided() {
		return voided;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
