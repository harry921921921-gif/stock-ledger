package com.sideproject.stockledger.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 股價與除權息抓取紀錄（共用資料，spec 6 PriceUpdateLog、7.1-6）。只新增。 */
@Entity
@Table(name = "price_update_log")
public class PriceUpdateLog {

	public static final String PRICES = "PRICES";
	public static final String DIVIDENDS = "DIVIDENDS";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "run_at", nullable = false, updatable = false)
	private LocalDateTime runAt;

	@Column(nullable = false, length = 20, updatable = false)
	private String kind;

	@Column(name = "data_date", updatable = false)
	private LocalDate dataDate;

	@Column(name = "row_count", nullable = false, updatable = false)
	private int rowCount;

	@Column(nullable = false, length = 200, updatable = false)
	private String result;

	protected PriceUpdateLog() {
		// JPA 需要
	}

	public static PriceUpdateLog of(LocalDateTime runAt, String kind, LocalDate dataDate, int rowCount, String result) {
		PriceUpdateLog log = new PriceUpdateLog();
		log.runAt = runAt;
		log.kind = kind;
		log.dataDate = dataDate;
		log.rowCount = rowCount;
		log.result = result.length() > 200 ? result.substring(0, 200) : result;
		return log;
	}

	public LocalDateTime getRunAt() {
		return runAt;
	}

	public String getKind() {
		return kind;
	}

	public LocalDate getDataDate() {
		return dataDate;
	}

	public int getRowCount() {
		return rowCount;
	}

	public String getResult() {
		return result;
	}

}
