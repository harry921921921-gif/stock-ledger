package com.sideproject.stockledger.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.entity.Stock;
import com.sideproject.stockledger.repository.StockRepository;

/**
 * 股票的共用設定：年配息次數（證交所沒有這項資料；預估年配息用，spec 5.6）。
 * 所有使用者共用，所以只有管理者可以改（2026-10-23 使用者決定；同 UserAdminService 用方法層 @PreAuthorize）。
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class StockSettingService {

	private final StockRepository stocks;
	private final Clock clock;

	public StockSettingService(StockRepository stocks, Clock clock) {
		this.stocks = stocks;
		this.clock = clock;
	}

	/** 設定某檔股票一年配息幾次；null 表示清除（預估年配息顯示「—」）。 */
	@Transactional
	public Stock setDividendsPerYear(String code, Integer times) {
		String clean = code == null ? "" : code.strip().toUpperCase();
		Stock stock = stocks.findByCode(clean)
			.orElseThrow(() -> new TradeValidationException("找不到這個上市股票代號：" + clean));
		if (times != null && (times < 1 || times > 12)) {
			throw new TradeValidationException("年配息次數要在 1～12 之間");
		}
		stock.setDividendsPerYear(times, LocalDateTime.now(clock));
		return stock;
	}

}
