package com.sideproject.stockledger.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 上市股票清單（共用資料，spec 6 Stock）：來自證交所 STOCK_DAY_ALL；年配息次數由使用者手動填。 */
@Entity
@Table(name = "stock")
public class Stock {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 10, updatable = false)
	private String code;

	@Column(nullable = false, length = 50)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private StockKind kind;

	@Column(name = "dividends_per_year")
	private Integer dividendsPerYear;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected Stock() {
		// JPA 需要
	}

	public static Stock create(String code, String name, LocalDateTime now) {
		Stock s = new Stock();
		s.code = code;
		s.name = name;
		s.kind = StockKind.fromCode(code);
		s.updatedAt = now;
		return s;
	}

	/** 證交所的名稱改了（例如公司改名）就更新。 */
	public void rename(String newName, LocalDateTime now) {
		if (!newName.equals(name)) {
			this.name = newName;
			this.updatedAt = now;
		}
	}

	public void setDividendsPerYear(Integer times, LocalDateTime now) {
		this.dividendsPerYear = times;
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public StockKind getKind() {
		return kind;
	}

	public Integer getDividendsPerYear() {
		return dividendsPerYear;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

}
