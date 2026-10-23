package com.sideproject.stockledger.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 期初持股或買進用了哪筆借款的錢（spec 4.1、6 SourceAllocation）。只存借款的部分，其餘是自有。
 * 修改交易的資金來源時整批換掉，修改紀錄寫在 trade_revision（field = allocation）。
 */
@Entity
@Table(name = "source_allocation")
public class SourceAllocation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trade_id", nullable = false, updatable = false)
	private Long tradeId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "loan_id", nullable = false, updatable = false)
	private Long loanId;

	@Column(nullable = false, precision = 19, scale = 4, updatable = false)
	private BigDecimal amount;

	protected SourceAllocation() {
		// JPA 需要
	}

	public static SourceAllocation of(Long tradeId, Long userId, Long loanId, BigDecimal amount) {
		SourceAllocation a = new SourceAllocation();
		a.tradeId = tradeId;
		a.userId = userId;
		a.loanId = loanId;
		a.amount = amount;
		return a;
	}

	public Long getId() {
		return id;
	}

	public Long getTradeId() {
		return tradeId;
	}

	public Long getLoanId() {
		return loanId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

}
