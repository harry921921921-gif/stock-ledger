package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 解析證交所 OpenAPI 的回應（spec 7.1、DR-004、DR-024）。只轉換格式，不判斷要不要寫入。
 * <ul>
 * <li>日期是民國年：1151021 ＝ 2026-10-21（年份 3 位或 2 位）。</li>
 * <li>數字可能有千分位逗號；空白、「--」代表沒有資料。</li>
 * </ul>
 */
final class TwseParser {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int ROC_OFFSET = 1911;

	private TwseParser() {
	}

	/** 一檔股票一天的收盤資料；沒成交時 close 為 null。 */
	record DailyQuote(String code, String name, LocalDate date, BigDecimal close) {
	}

	/** 一筆除權除息預告；還沒公告的金額為 null。 */
	record DividendNotice(String code, String name, LocalDate exDate, BigDecimal cashPerShare, BigDecimal stockRatio) {
	}

	/** STOCK_DAY_ALL。 */
	static List<DailyQuote> parseDailyQuotes(String json) {
		List<DailyQuote> result = new ArrayList<>();
		for (JsonNode row : rows(json)) {
			result.add(new DailyQuote(text(row, "Code"), text(row, "Name"), rocDate(text(row, "Date")),
					number(text(row, "ClosingPrice"))));
		}
		return result;
	}

	/** STOCK_DAY（個股月資料）：stat 不是 OK 回空；收盤價（第 7 欄）不是數字的日子略過。 */
	static List<DailyQuote> parseMonthlyQuotes(String code, String json) {
		JsonNode root;
		try {
			root = JSON.readTree(json);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("回應不是 JSON", ex);
		}
		List<DailyQuote> result = new ArrayList<>();
		if (root == null || !"OK".equals(text(root, "stat")) || root.get("data") == null
				|| !root.get("data").isArray()) {
			return result;
		}
		for (JsonNode row : root.get("data")) {
			if (!row.isArray() || row.size() < 7) {
				continue;
			}
			BigDecimal close = number(row.get(6).asString());
			if (close == null) {
				continue;
			}
			try {
				result.add(new DailyQuote(code, null, rocDate(row.get(0).asString().replace("/", "")), close));
			}
			catch (IllegalArgumentException ex) {
				// 日期壞掉的那一列略過，不影響整個月
			}
		}
		return result;
	}

	/** TWT48U_ALL。 */
	static List<DividendNotice> parseDividendNotices(String json) {
		List<DividendNotice> result = new ArrayList<>();
		for (JsonNode row : rows(json)) {
			result.add(new DividendNotice(text(row, "Code"), text(row, "Name"), rocDate(text(row, "Date")),
					number(text(row, "CashDividend")), number(text(row, "StockDividendRatio"))));
		}
		return result;
	}

	/** 民國年日期（yyyMMdd 或 yyMMdd）。 */
	static LocalDate rocDate(String value) {
		if (value == null || !value.matches("\\d{6,7}")) {
			throw new IllegalArgumentException("日期格式不對：" + value);
		}
		int split = value.length() - 4;
		try {
			return LocalDate.of(Integer.parseInt(value.substring(0, split)) + ROC_OFFSET,
					Integer.parseInt(value.substring(split, split + 2)), Integer.parseInt(value.substring(split + 2)));
		}
		catch (DateTimeException ex) {
			throw new IllegalArgumentException("日期不存在：" + value, ex);
		}
	}

	/** 去掉千分位；空白、「--」、「-」回傳 null。 */
	static BigDecimal number(String value) {
		if (value == null) {
			return null;
		}
		String cleaned = value.replace(",", "").strip();
		if (cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '-')) {
			return null;
		}
		try {
			return new BigDecimal(cleaned);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Iterable<JsonNode> rows(String json) {
		JsonNode root;
		try {
			root = JSON.readTree(json);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("回應不是 JSON", ex);
		}
		if (root == null || !root.isArray()) {
			throw new IllegalArgumentException("回應不是陣列");
		}
		return root;
	}

	private static String text(JsonNode row, String field) {
		JsonNode node = row.get(field);
		return node == null || node.isNull() ? null : node.asString();
	}

}
