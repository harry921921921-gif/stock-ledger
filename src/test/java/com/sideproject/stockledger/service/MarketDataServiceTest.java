package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.sideproject.stockledger.FakeTwseConfig;
import com.sideproject.stockledger.TestcontainersConfig;

/** 證交所資料寫入資料庫（spec 7.1）：新增或更新股票清單、收盤價不重複、除權息補上金額、每次留紀錄。 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestcontainersConfig.class, FakeTwseConfig.class })
class MarketDataServiceTest {

	@Autowired
	private MarketDataService service;

	@Autowired
	private FakeTwseConfig.FakeTwseClient twse;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private org.springframework.transaction.support.TransactionTemplate outer;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM source_allocation");
		jdbc.update("DELETE FROM trade_revision");
		jdbc.update("DELETE FROM trade");
		jdbc.update("DELETE FROM pledge_collateral");
		jdbc.update("DELETE FROM stock_price");
		jdbc.update("DELETE FROM dividend");
		jdbc.update("DELETE FROM price_update_log");
		jdbc.update("DELETE FROM stock");
		twse.daily = TwseParserTest.DAILY;
		twse.dividends = TwseParserTest.DIVIDENDS;
		twse.failure = null;
	}

	@Test
	void refreshPricesCreatesStocksAndPrices() {
		int written = service.refreshPrices();

		assertThat(written).isEqualTo(2);
		assertThat(jdbc.queryForList("SELECT code, kind FROM stock ORDER BY code"))
			.extracting(r -> r.get("code") + ":" + r.get("kind"))
			.containsExactly("0056:ETF", "2330:STOCK", "9999:STOCK");
		assertThat(jdbc.queryForObject("SELECT close_price FROM stock_price p JOIN stock s ON s.id = p.stock_id "
				+ "WHERE s.code = '2330'", java.math.BigDecimal.class)).isEqualByComparingTo("2420");
		assertThat(service.latestPriceDate()).contains(LocalDate.of(2026, 10, 21));
		assertThat(jdbc.queryForMap("SELECT kind, row_count, result, data_date FROM price_update_log"))
			.containsEntry("kind", "PRICES")
			.containsEntry("row_count", 2)
			.containsEntry("result", "成功");
	}

	@Test
	void closeOnUsesStoredPrice() {
		service.refreshPrices();
		twse.monthlyCalls = 0;

		assertThat(service.closeOn("2330", LocalDate.of(2026, 10, 21))).hasValueSatisfying(
				v -> assertThat(v).isEqualByComparingTo("2420"));
		assertThat(twse.monthlyCalls).isZero();
	}

	@Test
	void closeOnFetchesMonthAndStores() {
		service.refreshPrices();
		twse.monthly = TwseParserTest.MONTHLY;

		assertThat(service.closeOn("0056", LocalDate.of(2026, 7, 28))).hasValueSatisfying(
				v -> assertThat(v).isEqualByComparingTo("75.15"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_price p JOIN stock s ON s.id = p.stock_id "
				+ "WHERE s.code = '0056' AND p.trade_date BETWEEN '2026-07-01' AND '2026-07-31'", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_update_log WHERE kind = 'HISTORY' "
				+ "AND result = '成功'", Integer.class)).isEqualTo(1);
	}

	@Test
	void closeOnHolidayIsEmpty() {
		service.refreshPrices();
		twse.monthly = TwseParserTest.MONTHLY;

		assertThat(service.closeOn("0056", LocalDate.of(2026, 7, 26))).isEmpty();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_price WHERE trade_date = '2026-07-26'",
				Integer.class)).isZero();
	}

	@Test
	void closeOnUnknownStockIsEmptyWithoutFetch() {
		assertThat(service.closeOn("1234", LocalDate.of(2026, 7, 28))).isEmpty();
		assertThat(twse.monthlyCalls).isZero();
	}

	@Test
	void closeOnNetworkErrorIsEmptyAndLogged() {
		service.refreshPrices();
		twse.failure = new IllegalStateException("連不上");

		assertThat(service.closeOn("0056", LocalDate.of(2026, 7, 28))).isEmpty();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_update_log WHERE kind = 'HISTORY' "
				+ "AND result LIKE '失敗%'", Integer.class)).isEqualTo(1);
	}

	/** 價格超出欄位範圍：存不進去，但抓到的價格仍回傳，失敗紀錄要留下來。 */
	@Test
	void closeOnSaveFailureStillReturnsPriceAndLogsFailure() {
		service.refreshPrices();
		twse.monthly = TwseParserTest.MONTHLY.replace("\"75.15\",\"-1.05\"", "\"999,999,999.00\",\"-1.05\"");

		assertThat(service.closeOn("0056", LocalDate.of(2026, 7, 28))).hasValueSatisfying(
				v -> assertThat(v).isEqualByComparingTo("999999999"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_price WHERE trade_date = '2026-07-28'",
				Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_update_log WHERE kind = 'HISTORY' "
				+ "AND result LIKE '失敗%'", Integer.class)).isEqualTo(1);
	}

	/** 在呼叫端的交易裡呼叫：不管成功或失敗，呼叫端自己的寫入都還能 commit。 */
	@Test
	void closeOnInsideOuterTransactionDoesNotBreakIt() {
		service.refreshPrices();
		twse.monthly = TwseParserTest.MONTHLY.replace("\"75.15\",\"-1.05\"", "\"999,999,999.00\",\"-1.05\"");

		outer.executeWithoutResult(status -> {
			assertThat(service.closeOn("0056", LocalDate.of(2026, 7, 28))).isPresent();
			twse.monthly = TwseParserTest.MONTHLY;
			assertThat(service.closeOn("0056", LocalDate.of(2026, 7, 27))).isPresent();
			jdbc.update("INSERT INTO price_update_log (run_at, kind, row_count, result) "
					+ "VALUES (NOW(), 'PRICES', 0, '外層寫入')");
		});

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_update_log WHERE result = '外層寫入'",
				Integer.class)).isEqualTo(1);
	}

	/** 同一天再抓一次不重複寫入；名稱改了就更新。 */
	@Test
	void refreshTwiceDoesNotDuplicate() {
		service.refreshPrices();
		twse.daily = TwseParserTest.DAILY.replace("元大高股息", "元大台灣高股息");

		assertThat(service.refreshPrices()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_price", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT name FROM stock WHERE code = '0056'", String.class))
			.isEqualTo("元大台灣高股息");
	}

	/** 抓不到（網路、格式錯）只記紀錄，不丟例外。 */
	@Test
	void failureIsLogged() {
		twse.failure = new IllegalStateException("連不上");

		assertThat(service.refreshPrices()).isZero();
		assertThat(jdbc.queryForObject("SELECT result FROM price_update_log", String.class)).startsWith("失敗");

		twse.failure = null;
		twse.daily = "<html>302</html>";
		service.refreshPrices();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_update_log WHERE result LIKE '失敗%'", Integer.class))
			.isEqualTo(2);
	}

	/** 除權息：新增；之後公告了金額就補上（空白不蓋掉已有的值）。 */
	@Test
	void dividendsUpsert() {
		service.refreshPrices();
		service.refreshDividends();
		assertThat(jdbc.queryForObject("SELECT cash_per_share FROM dividend d JOIN stock s ON s.id = d.stock_id "
				+ "WHERE s.code = '0056'", java.math.BigDecimal.class)).isEqualByComparingTo("1.72");
		assertThat(jdbc.queryForObject("SELECT cash_per_share FROM dividend d JOIN stock s ON s.id = d.stock_id "
				+ "WHERE s.code = '00400A'", java.math.BigDecimal.class)).isNull();

		twse.dividends = TwseParserTest.DIVIDENDS.replace("\"CashDividend\":\"\"", "\"CashDividend\":\"0.25\"")
			.replace("\"CashDividend\":\"1.720000\"", "\"CashDividend\":\"\"");
		service.refreshDividends();

		assertThat(jdbc.queryForObject("SELECT cash_per_share FROM dividend d JOIN stock s ON s.id = d.stock_id "
				+ "WHERE s.code = '00400A'", java.math.BigDecimal.class)).isEqualByComparingTo("0.25");
		assertThat(jdbc.queryForObject("SELECT cash_per_share FROM dividend d JOIN stock s ON s.id = d.stock_id "
				+ "WHERE s.code = '0056'", java.math.BigDecimal.class)).isEqualByComparingTo("1.72");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dividend", Integer.class)).isEqualTo(2);
	}

}
