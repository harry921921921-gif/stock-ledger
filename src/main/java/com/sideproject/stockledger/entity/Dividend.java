package com.sideproject.stockledger.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 除權除息（共用資料，spec 6 Dividend、DR-024）：來自證交所 TWT48U_ALL 預告表。現金股利公告前可能是空的，
 * 之後再抓到就更新。API 沒有發放日：實際入帳由使用者記（配息入帳）。
 */
@Entity
@Table(name = "dividend")
public class Dividend {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "stock_id", nullable = false, updatable = false)
	private Long stockId;

	@Column(name = "ex_date", nullable = false, updatable = false)
	private LocalDate exDate;

	@Column(name = "cash_per_share", precision = 12, scale = 6)
	private BigDecimal cashPerShare;

	@Column(name = "stock_ratio", precision = 12, scale = 8)
	private BigDecimal stockRatio;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected Dividend() {
		// JPA 需要
	}

	public static Dividend of(Long stockId, LocalDate exDate, BigDecimal cashPerShare, BigDecimal stockRatio,
			LocalDateTime now) {
		Dividend d = new Dividend();
		d.stockId = stockId;
		d.exDate = exDate;
		d.update(cashPerShare, stockRatio, now);
		return d;
	}

	/** 公告後補上金額；已經有的值不會被空白蓋掉。 */
	public void update(BigDecimal cash, BigDecimal ratio, LocalDateTime now) {
		if (cash != null) {
			this.cashPerShare = cash;
		}
		if (ratio != null) {
			this.stockRatio = ratio;
		}
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Long getStockId() {
		return stockId;
	}

	public LocalDate getExDate() {
		return exDate;
	}

	public BigDecimal getCashPerShare() {
		return cashPerShare;
	}

	public BigDecimal getStockRatio() {
		return stockRatio;
	}

}
