package com.sideproject.stockledger.service;

import java.time.YearMonth;

/**
 * 證交所 OpenAPI（DR-004、DR-024）。回傳原始 JSON 字串，解析交給 TwseParser。
 * 介面讓測試換成假的資料，不會真的連網路。
 */
public interface TwseClient {

	/** STOCK_DAY_ALL：上市個股日成交資訊（收盤價）。 */
	String dailyQuotesJson();

	/** STOCK_DAY：某檔股票某個月每天的成交資訊（歷史收盤價）。 */
	String monthlyQuotesJson(String code, YearMonth month);

	/** TWT48U_ALL：上市股票除權除息預告表。 */
	String dividendNoticesJson();

}
