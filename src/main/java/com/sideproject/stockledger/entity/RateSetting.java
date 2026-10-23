package com.sideproject.stockledger.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.sideproject.stockledger.calculator.RateCandidate;

/**
 * 費率的一個版本（spec 4.5、6 RateSetting）。只新增、不修改：所有欄位建立後都不能改（spec 4.5-2）。
 * userId 為 null 代表法定費率或全域預設。
 */
@Entity
@Table(name = "rate_setting")
public class RateSetting {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", updatable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40, updatable = false)
	private RateKind kind;

	@Column(nullable = false, precision = 19, scale = 6, updatable = false)
	private BigDecimal value;

	@Column(name = "effective_date", nullable = false, updatable = false)
	private LocalDate effectiveDate;

	@Column(length = 200, updatable = false)
	private String note;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/** 借款年利率綁定的借款；其他種類為 null（V9）。 */
	@Column(name = "loan_id", updatable = false)
	private Long loanId;

	protected RateSetting() {
		// JPA 需要
	}

	/** 使用者自己的版本。法定費率只由 Flyway seed 寫入，程式不會建立 userId 為 null 的列。 */
	public static RateSetting personal(Long userId, RateKind kind, BigDecimal value, LocalDate effectiveDate,
			String note, LocalDateTime now) {
		Objects.requireNonNull(userId, "個人費率一定要有 userId；全域列只由 Flyway seed 寫入");
		if (kind.scope() != RateKind.Scope.PERSONAL) {
			throw new IllegalArgumentException("法定費率不能修改");
		}
		RateSetting rate = new RateSetting();
		rate.userId = userId;
		rate.kind = kind;
		rate.value = value;
		rate.effectiveDate = effectiveDate;
		rate.note = note;
		rate.createdAt = now;
		return rate;
	}

	/** 某一筆借款的年利率版本（0.025 = 2.5%）。只新增、不修改。 */
	public static RateSetting loanRate(Long userId, Long loanId, BigDecimal annualRate, LocalDate effectiveDate,
			LocalDateTime now) {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(loanId, "loanId");
		RateSetting rate = new RateSetting();
		rate.userId = userId;
		rate.loanId = loanId;
		rate.kind = RateKind.LOAN_ANNUAL_RATE;
		rate.value = annualRate;
		rate.effectiveDate = effectiveDate;
		rate.createdAt = now;
		return rate;
	}

	/** 某一筆每月固定還款借款的銀行每月扣款金額版本（整數元）。只新增、不修改（spec 4.8a、DR-048）。 */
	public static RateSetting loanPayment(Long userId, Long loanId, BigDecimal amount, LocalDate effectiveDate,
			LocalDateTime now) {
		RateSetting rate = loanRate(userId, loanId, amount, effectiveDate, now);
		rate.kind = RateKind.LOAN_MONTHLY_PAYMENT;
		return rate;
	}

	/** 某一筆質押的維持率提醒線或追繳線版本（1.5 = 150%）。只新增、不修改。 */
	public static RateSetting loanThreshold(Long userId, Long loanId, RateKind kind, BigDecimal ratio,
			LocalDate effectiveDate, LocalDateTime now) {
		if (kind != RateKind.MAINTENANCE_WARN && kind != RateKind.MAINTENANCE_CALL) {
			throw new IllegalArgumentException("kind 要是維持率門檻");
		}
		RateSetting rate = loanRate(userId, loanId, ratio, effectiveDate, now);
		rate.kind = kind;
		return rate;
	}

	public RateCandidate toCandidate() {
		return new RateCandidate(id, userId, effectiveDate, value);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public RateKind getKind() {
		return kind;
	}

	public BigDecimal getValue() {
		return value;
	}

	public LocalDate getEffectiveDate() {
		return effectiveDate;
	}

	public String getNote() {
		return note;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public Long getLoanId() {
		return loanId;
	}

}
