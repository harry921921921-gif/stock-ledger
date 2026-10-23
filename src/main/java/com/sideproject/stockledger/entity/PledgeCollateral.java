package com.sideproject.stockledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 質押的擔保股票（spec 6 PledgeCollateral、5.5）：每筆質押、每檔股票一列；股數可以改。 */
@Entity
@Table(name = "pledge_collateral")
public class PledgeCollateral {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "loan_id", nullable = false, updatable = false)
	private Long loanId;

	@Column(name = "stock_id", nullable = false, updatable = false)
	private Long stockId;

	@Column(nullable = false)
	private long shares;

	protected PledgeCollateral() {
		// JPA 需要
	}

	public static PledgeCollateral of(Long userId, Long loanId, Long stockId, long shares) {
		PledgeCollateral c = new PledgeCollateral();
		c.userId = userId;
		c.loanId = loanId;
		c.stockId = stockId;
		c.shares = shares;
		return c;
	}

	public void setShares(long shares) {
		this.shares = shares;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getLoanId() {
		return loanId;
	}

	public Long getStockId() {
		return stockId;
	}

	public long getShares() {
		return shares;
	}

}
