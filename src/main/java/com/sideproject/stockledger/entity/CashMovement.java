package com.sideproject.stockledger.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

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
 * 不硬刪除：作廢用 voided 旗標。資料表另有三個「產生欄位」讓資料庫保證唯一性，這裡不對應。
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

	/** 固定收支自動產生（或每月自己填）時，屬於哪個項目；其他異動為 null（V8）。 */
	@Column(name = "recurring_item_id", updatable = false)
	private Long recurringItemId;

	/** 屬於哪一期：該期所在月份的 1 號。固定收支一定有；借款自動記的還本與利息也有（V9）。 */
	@Column(name = "period", updatable = false)
	private LocalDate period;

	/** 借款撥款、還本、利息屬於哪一筆借款；其他類型為 null（V9）。 */
	@Column(name = "loan_id", updatable = false)
	private Long loanId;

	/** 買進、賣出股票：自動記這筆的股票交易；其他類型為 null（V10）。 */
	@Column(name = "trade_id", updatable = false)
	private Long tradeId;

	/** 配息：哪一檔股票；其他類型為 null（V10）。 */
	@Column(name = "stock_id", updatable = false)
	private Long stockId;

	/** 配息：二代健保扣繳額（實際入帳金額已經扣掉）；其他類型為 null（V10）。 */
	@Column(name = "nhi_amount", precision = 19, scale = 4)
	private BigDecimal nhiAmount;

	protected CashMovement() {
		// JPA 需要
	}

	/** 股票交易自動記的現金異動：買進從交割戶扣（成交金額＋手續費）、賣出存入實際入帳金額。備註是股票名稱。 */
	public static CashMovement ofTrade(Trade trade, CashMovementType type, String note, LocalDateTime now) {
		if (!type.isTrade()) {
			throw new IllegalArgumentException("只有買進、賣出股票可以屬於交易");
		}
		CashMovement m = ofAmount(trade.getUserId(), type, trade.getTradeDate(), trade.getAccountId(), null,
				trade.getAmount(), note, now);
		m.tradeId = trade.getId();
		return m;
	}

	/** 配息入帳：實際入帳金額與二代健保扣繳額。備註是股票名稱。 */
	public static CashMovement ofDividend(Long userId, LocalDate date, Long accountId, Long stockId, BigDecimal amount,
			BigDecimal nhi, String note, LocalDateTime now) {
		CashMovement m = ofAmount(userId, CashMovementType.DIVIDEND, date, accountId, null, amount, note, now);
		m.stockId = stockId;
		m.nhiAmount = nhi;
		return m;
	}

	/** 交易修改時，跟著改它自動記的那筆現金異動（呼叫端先寫修改紀錄）。 */
	public void followTrade(Trade trade) {
		change(trade.getTradeDate(), trade.getAccountId(), null, trade.getAmount(), getNote());
	}

	/** 修改配息的二代健保扣繳額。 */
	public void changeNhi(BigDecimal nhi) {
		this.nhiAmount = nhi;
	}

	/**
	 * 借款的撥款、還本或利息。period 是自動記帳的期別（使用者自己記的還本、動撥為 null）；備註是借款名稱。
	 */
	public static CashMovement ofLoan(Loan loan, CashMovementType type, LocalDate date, Long accountId,
			BigDecimal amount, YearMonth period, LocalDateTime now) {
		if (!type.isLoan()) {
			throw new IllegalArgumentException("只有借款撥款、還本、利息可以屬於借款");
		}
		CashMovement m = ofAmount(loan.getUserId(), type, date, accountId, null, amount, loan.getName(), now);
		m.loanId = loan.getId();
		m.period = period == null ? null : period.atDay(1);
		return m;
	}

	/**
	 * 固定項目某一期的異動：收入是存入、支出是支出，帳戶是項目的帳戶，備註是項目名稱。
	 * 和手動記的一樣可以修改金額、日期或作廢；所屬項目與期別不會改。
	 */
	public static CashMovement ofRecurring(RecurringItem item, LocalDate date, BigDecimal amount, YearMonth period,
			LocalDateTime now) {
		CashMovement m = ofAmount(item.getUserId(), item.getDirection().movementType(), date, item.getAccountId(), null,
				amount, item.getName(), now);
		m.recurringItemId = item.getId();
		m.period = period.atDay(1);
		return m;
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

	public Long getRecurringItemId() {
		return recurringItemId;
	}

	public YearMonth getPeriod() {
		return period == null ? null : YearMonth.from(period);
	}

	public Long getLoanId() {
		return loanId;
	}

	public Long getTradeId() {
		return tradeId;
	}

	public Long getStockId() {
		return stockId;
	}

	public BigDecimal getNhiAmount() {
		return nhiAmount;
	}

}
