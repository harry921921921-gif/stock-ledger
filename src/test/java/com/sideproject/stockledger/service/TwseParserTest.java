package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 解析證交所 OpenAPI 的回應（格式依 2026-10-23 實際抓到的資料）。 */
class TwseParserTest {

	static final String DAILY = """
			[
			{"Date":"1151021","Code":"0056","Name":"元大高股息","TradeVolume":"54408829","TradeValue":"3184917786","OpeningPrice":"58.15","HighestPrice":"58.75","LowestPrice":"58.15","ClosingPrice":"58.75","Change":"1.3000","Transaction":"34359"},
			{"Date":"1151021","Code":"2330","Name":"台積電","TradeVolume":"27095005","TradeValue":"65515856247","OpeningPrice":"2425.00","HighestPrice":"2435.00","LowestPrice":"2400.00","ClosingPrice":"2,420.00","Change":"15.0000","Transaction":"101401"},
			{"Date":"1151021","Code":"9999","Name":"沒成交","TradeVolume":"0","TradeValue":"0","OpeningPrice":"","HighestPrice":"","LowestPrice":"","ClosingPrice":"--","Change":"0","Transaction":"0"}
			]
			""";

	static final String DIVIDENDS = """
			[
			{"Date":"1151022","Code":"0056","Name":"元大高股息","Exdividend":"息","StockDividendRatio":"","SubscriptionRatio":"","SubscriptionPricePerShare":"","CashDividend":"1.720000","SharesOffered":"","SharesEmpOwner":"","SharesholderOwner":"","StockHoldingRatio":""},
			{"Date":"1151106","Code":"00400A","Name":"主動國泰動能高息","Exdividend":"息","StockDividendRatio":"","SubscriptionRatio":"","SubscriptionPricePerShare":"","CashDividend":"","SharesOffered":"","SharesEmpOwner":"","SharesholderOwner":"","StockHoldingRatio":""}
			]
			""";

	@Test
	void rocDate() {
		assertThat(TwseParser.rocDate("1151021")).isEqualTo(LocalDate.of(2026, 10, 21));
		assertThat(TwseParser.rocDate("991231")).isEqualTo(LocalDate.of(2010, 12, 31));
		assertThatThrownBy(() -> TwseParser.rocDate("abc")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void dailyQuotes() {
		List<TwseParser.DailyQuote> quotes = TwseParser.parseDailyQuotes(DAILY);

		assertThat(quotes).hasSize(3);
		assertThat(quotes.get(0)).isEqualTo(new TwseParser.DailyQuote("0056", "元大高股息", LocalDate.of(2026, 10, 21),
				new java.math.BigDecimal("58.75")));
		// 千分位逗號要去掉
		assertThat(quotes.get(1).close()).isEqualByComparingTo("2420");
		// 沒成交（--）收盤價是空的
		assertThat(quotes.get(2).close()).isNull();
	}

	@Test
	void dividendNotices() {
		List<TwseParser.DividendNotice> notices = TwseParser.parseDividendNotices(DIVIDENDS);

		assertThat(notices).hasSize(2);
		assertThat(notices.get(0).exDate()).isEqualTo(LocalDate.of(2026, 10, 22));
		assertThat(notices.get(0).cashPerShare()).isEqualByComparingTo("1.72");
		assertThat(notices.get(0).stockRatio()).isNull();
		// 還沒公告金額
		assertThat(notices.get(1).cashPerShare()).isNull();
	}

	@Test
	void badJsonThrows() {
		assertThatThrownBy(() -> TwseParser.parseDailyQuotes("<html>302 Found</html>"))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
