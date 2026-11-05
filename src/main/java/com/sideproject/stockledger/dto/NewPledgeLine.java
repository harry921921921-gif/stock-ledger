package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * 新增股票質押時，一檔「還沒記在系統裡的質押股票」（設計文件 2026-11-04-pledged-stocks 4.1、6）。
 * 存檔時記成期初持股（日期＝質押日期、平均成本＝價格、不扣任何帳戶的錢），並設成這筆借款的擔保股數。
 * 代號、股數、價格都空白的列略過（畫面上多出來的空列）。
 *
 * @param date      質押日期
 * @param price     每股價格（期初持股的平均成本）；預設那天收盤價，可改
 * @param priceNote 價格旁的說明，由 LoanService.fillPrices 填（例如「證交所 2026-07-28 收盤價」）；畫面不送回
 */
public record NewPledgeLine(
		String stockCode,
		Long shares,
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
		BigDecimal price,
		String priceNote) {

	public NewPledgeLine {
		stockCode = stockCode == null || stockCode.isBlank() ? null : stockCode.strip().toUpperCase();
	}

	/** 畫面上「＋ 再加一檔」加的空列。 */
	public static NewPledgeLine blank() {
		return new NewPledgeLine(null, null, null, null, null);
	}

	/** 有填任何一個欄位（代號、股數、價格）的列才算數；只有日期的列當成空列。 */
	public boolean filled() {
		return stockCode != null || shares != null || price != null;
	}

}
