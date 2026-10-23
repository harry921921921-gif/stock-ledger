package com.sideproject.stockledger.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 每日收盤價（共用資料，spec 6 StockPrice）。只新增、不修改：盤後資料不會再變。 */
@Entity
@Table(name = "stock_price")
public class StockPrice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "stock_id", nullable = false, updatable = false)
	private Long stockId;

	@Column(name = "trade_date", nullable = false, updatable = false)
	private LocalDate tradeDate;

	@Column(name = "close_price", nullable = false, precision = 12, scale = 4, updatable = false)
	private BigDecimal closePrice;

	protected StockPrice() {
		// JPA 需要
	}

	public static StockPrice of(Long stockId, LocalDate tradeDate, BigDecimal closePrice) {
		StockPrice p = new StockPrice();
		p.stockId = stockId;
		p.tradeDate = tradeDate;
		p.closePrice = closePrice;
		return p;
	}

	public Long getId() {
		return id;
	}

	public Long getStockId() {
		return stockId;
	}

	public LocalDate getTradeDate() {
		return tradeDate;
	}

	public BigDecimal getClosePrice() {
		return closePrice;
	}

}
