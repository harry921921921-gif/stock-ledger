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

/**
 * 一筆股票交易事件（spec 6 Trade、設計文件 2026-10-23-stocks 4）：期初持股、買進、賣出、配股。
 * 只存原始輸入；持股、平均成本、損益由事件推算。不硬刪除：作廢用旗標；修改前寫 trade_revision。
 * amount：期初、買進是總成本（含手續費）；賣出是實際入帳金額；配股是 0。
 */
@Entity
@Table(name = "trade")
@DynamicUpdate
public class Trade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private TradeType type;

	@Column(name = "trade_date", nullable = false)
	private LocalDate tradeDate;

	@Column(name = "stock_id", nullable = false, updatable = false)
	private Long stockId;

	@Column(name = "account_id")
	private Long accountId;

	@Column(nullable = false)
	private long shares;

	@Column(precision = 12, scale = 4)
	private BigDecimal price;

	@Column(precision = 19, scale = 4)
	private BigDecimal fee;

	@Column(precision = 19, scale = 4)
	private BigDecimal tax;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(nullable = false)
	private boolean voided;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected Trade() {
		// JPA 需要
	}

	public static Trade create(Long userId, TradeType type, LocalDate date, Long stockId, Long accountId, long shares,
			BigDecimal price, BigDecimal fee, BigDecimal tax, BigDecimal amount, LocalDateTime now) {
		Trade t = new Trade();
		t.userId = userId;
		t.type = type;
		t.stockId = stockId;
		t.createdAt = now;
		t.change(date, accountId, shares, price, fee, tax, amount);
		return t;
	}

	/** 修改（類型與股票不能改；呼叫端先寫修改紀錄）。 */
	public void change(LocalDate date, Long newAccountId, long newShares, BigDecimal newPrice, BigDecimal newFee,
			BigDecimal newTax, BigDecimal newAmount) {
		if (voided) {
			throw new IllegalStateException("已作廢的交易不能再修改");
		}
		boolean cash = type == TradeType.BUY || type == TradeType.SELL;
		this.tradeDate = date;
		this.accountId = cash ? newAccountId : null;
		this.shares = newShares;
		this.price = cash ? newPrice : null;
		this.fee = cash ? newFee : null;
		this.tax = type == TradeType.SELL ? newTax : null;
		this.amount = type == TradeType.STOCK_DIVIDEND ? BigDecimal.ZERO : newAmount;
	}

	public void markVoided() {
		if (voided) {
			throw new IllegalStateException("已經作廢");
		}
		this.voided = true;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public TradeType getType() {
		return type;
	}

	public LocalDate getTradeDate() {
		return tradeDate;
	}

	public Long getStockId() {
		return stockId;
	}

	public Long getAccountId() {
		return accountId;
	}

	public long getShares() {
		return shares;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public BigDecimal getFee() {
		return fee;
	}

	public BigDecimal getTax() {
		return tax;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public boolean isVoided() {
		return voided;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
