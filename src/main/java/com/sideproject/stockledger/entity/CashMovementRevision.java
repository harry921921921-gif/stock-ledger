package com.sideproject.stockledger.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 現金事件的修改紀錄（spec 4.9、DR-033）：每個改到的欄位一列；作廢也寫一列（field = voided）。
 * 只新增、不修改：所有欄位建立後都不能改。
 */
@Entity
@Table(name = "cash_movement_revision")
public class CashMovementRevision {

	public static final String EDIT = "EDIT";
	public static final String VOID = "VOID";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "cash_movement_id", nullable = false, updatable = false)
	private Long cashMovementId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "revised_at", nullable = false, updatable = false)
	private LocalDateTime revisedAt;

	@Column(nullable = false, length = 10, updatable = false)
	private String action;

	@Column(nullable = false, length = 30, updatable = false)
	private String field;

	@Column(name = "old_value", length = 200, updatable = false)
	private String oldValue;

	@Column(name = "new_value", length = 200, updatable = false)
	private String newValue;

	@Column(length = 200, updatable = false)
	private String reason;

	protected CashMovementRevision() {
		// JPA 需要
	}

	public static CashMovementRevision of(Long cashMovementId, Long userId, LocalDateTime now, String action,
			String field, String oldValue, String newValue, String reason) {
		CashMovementRevision r = new CashMovementRevision();
		r.cashMovementId = cashMovementId;
		r.userId = userId;
		r.revisedAt = now;
		r.action = action;
		r.field = field;
		r.oldValue = oldValue;
		r.newValue = newValue;
		r.reason = reason;
		return r;
	}

	public Long getId() {
		return id;
	}

	public Long getCashMovementId() {
		return cashMovementId;
	}

	public String getAction() {
		return action;
	}

	public String getField() {
		return field;
	}

	public String getOldValue() {
		return oldValue;
	}

	public String getNewValue() {
		return newValue;
	}

	public String getReason() {
		return reason;
	}

	public LocalDateTime getRevisedAt() {
		return revisedAt;
	}

}
