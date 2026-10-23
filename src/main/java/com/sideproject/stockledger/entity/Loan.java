package com.sideproject.stockledger.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一筆借款（設計文件 2026-10-22-funding-sources、spec 6 Loan）。這是「設定」：修改直接更新、不寫修改紀錄。
 * 撥款、還本、利息是現金異動；剩餘本金、是否還清都由事件推算，不存在這裡。
 * 類型與方式建立後不能改；起點（起算日、起算本金、剩餘期數、第一期）只有既有借款、還沒有任何異動時能改（設計文件 11-5）；
 * 扣款日與帳戶改了從當月起生效（postingFrom）。
 */
@Entity
@Table(name = "loan")
public class Loan {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(nullable = false, length = 50)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "loan_type", nullable = false, length = 10, updatable = false)
	private LoanType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "repayment_method", nullable = false, length = 20, updatable = false)
	private RepaymentMethod repaymentMethod;

	@Column(nullable = false, length = 50)
	private String bank;

	@Column(name = "original_amount", nullable = false, precision = 19, scale = 4)
	private BigDecimal originalAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private LoanOrigin origin;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "start_principal", nullable = false, precision = 19, scale = 4)
	private BigDecimal startPrincipal;

	@Column(name = "disburse_account_id", updatable = false)
	private Long disburseAccountId;

	@Column(name = "pay_account_id", nullable = false)
	private Long payAccountId;

	@Column(name = "pay_day", nullable = false)
	private int payDay;

	@Column(name = "remaining_periods")
	private Integer remainingPeriods;

	/** 那個月的 1 號。 */
	@Column(name = "first_period")
	private LocalDate firstPeriod;

	@Column(name = "due_date")
	private LocalDate dueDate;

	/** 那個月的 1 號：早於這個月份的期數不再自動產生。 */
	@Column(name = "posting_from", nullable = false)
	private LocalDate postingFrom;

	/** 作廢：不硬刪除；之後不出現在資金來源頁、不再自動記帳、不能再操作。 */
	@Column(nullable = false)
	private boolean voided;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected Loan() {
		// JPA 需要
	}

	/** V11 以前的呼叫方式：還款方式照類型對應（信貸每月固定還款、質押每月繳息）。 */
	public static Loan create(Long userId, String name, LoanType type, String bank, BigDecimal originalAmount,
			LoanOrigin origin, LocalDate startDate, BigDecimal startPrincipal, Long disburseAccountId,
			Long payAccountId, int payDay, Integer remainingPeriods, YearMonth firstPeriod, LocalDate dueDate,
			LocalDateTime now) {
		return create(userId, name, type, RepaymentMethod.legacyOf(type), bank, originalAmount, origin, startDate,
				startPrincipal, disburseAccountId, payAccountId, payDay, remainingPeriods, firstPeriod, dueDate, now);
	}

	/** 檢查由 LoanService 負責（訊息要中文）；這裡只保證還款方式相關的欄位一致，資料庫另有 CHECK（ck_loan_method）。 */
	public static Loan create(Long userId, String name, LoanType type, RepaymentMethod method, String bank,
			BigDecimal originalAmount, LoanOrigin origin, LocalDate startDate, BigDecimal startPrincipal,
			Long disburseAccountId, Long payAccountId, int payDay, Integer remainingPeriods, YearMonth firstPeriod,
			LocalDate dueDate, LocalDateTime now) {
		Loan loan = new Loan();
		loan.userId = userId;
		loan.type = type;
		loan.repaymentMethod = method;
		loan.origin = origin;
		loan.startDate = startDate;
		loan.startPrincipal = startPrincipal;
		loan.disburseAccountId = origin == LoanOrigin.NEW ? disburseAccountId : null;
		boolean monthly = method == RepaymentMethod.MONTHLY_FIXED;
		loan.remainingPeriods = monthly ? remainingPeriods : null;
		loan.firstPeriod = monthly && firstPeriod != null ? firstPeriod.atDay(1) : null;
		loan.postingFrom = YearMonth.from(startDate).atDay(1);
		loan.createdAt = now;
		loan.name = CashAccount.normalizeName(name);
		loan.bank = CashAccount.normalizeName(bank);
		loan.originalAmount = originalAmount;
		loan.payAccountId = payAccountId;
		loan.payDay = payDay;
		loan.dueDate = monthly ? null : dueDate;
		return loan;
	}

	/**
	 * 修改基本資料。扣款日或扣款帳戶改了，記帳起點移到 currentMonth：之前沒記到的期數不用新設定補記（同 DR-037）。
	 */
	public void change(String name, String bank, BigDecimal originalAmount, Long payAccountId, int payDay,
			LocalDate dueDate, YearMonth currentMonth) {
		boolean scheduleChanged = this.payDay != payDay || !Objects.equals(this.payAccountId, payAccountId);
		if (scheduleChanged && currentMonth.atDay(1).isAfter(postingFrom)) {
			this.postingFrom = currentMonth.atDay(1);
		}
		this.name = CashAccount.normalizeName(name);
		this.bank = CashAccount.normalizeName(bank);
		this.originalAmount = originalAmount;
		this.payAccountId = payAccountId;
		this.payDay = payDay;
		this.dueDate = repaymentMethod == RepaymentMethod.MONTHLY_FIXED ? null : dueDate;
	}

	/** 改起點：只有既有借款、還沒有任何異動時呼叫（由 LoanService 檢查）。記帳起點跟著移到新的起算月份。 */
	public void changeStart(LocalDate startDate, BigDecimal startPrincipal, Integer remainingPeriods,
			YearMonth firstPeriod) {
		if (origin != LoanOrigin.EXISTING) {
			throw new IllegalStateException("新撥款的起點由撥款異動決定");
		}
		this.startDate = startDate;
		this.startPrincipal = startPrincipal;
		this.remainingPeriods = type == LoanType.CREDIT ? remainingPeriods : null;
		this.firstPeriod = type == LoanType.CREDIT && firstPeriod != null ? firstPeriod.atDay(1) : null;
		this.postingFrom = YearMonth.from(startDate).atDay(1);
	}

	/** 作廢這筆借款（它的現金異動由 LoanService 先一一作廢、寫修改紀錄）。 */
	public void markVoided() {
		if (voided) {
			throw new IllegalStateException("已經作廢");
		}
		this.voided = true;
	}

	public boolean isVoided() {
		return voided;
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

	public LoanType getType() {
		return type;
	}

	public RepaymentMethod getRepaymentMethod() {
		return repaymentMethod;
	}

	public String getBank() {
		return bank;
	}

	public BigDecimal getOriginalAmount() {
		return originalAmount;
	}

	public LoanOrigin getOrigin() {
		return origin;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public BigDecimal getStartPrincipal() {
		return startPrincipal;
	}

	public Long getDisburseAccountId() {
		return disburseAccountId;
	}

	public Long getPayAccountId() {
		return payAccountId;
	}

	public int getPayDay() {
		return payDay;
	}

	public Integer getRemainingPeriods() {
		return remainingPeriods;
	}

	public YearMonth getFirstPeriod() {
		return firstPeriod == null ? null : YearMonth.from(firstPeriod);
	}

	public LocalDate getDueDate() {
		return dueDate;
	}

	public YearMonth getPostingFrom() {
		return YearMonth.from(postingFrom);
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
