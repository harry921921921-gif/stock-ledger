package com.sideproject.stockledger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;

import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.sideproject.stockledger.TestcontainersConfig;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Role;

/** V10：股票、交易、資金來源分攤、擔保品的規則由資料庫本身保證（設計文件 2026-10-23-stocks 6）。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class StockSchemaTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private Clock clock;

	private Long userId;
	private Long otherUserId;
	private Long account;
	private Long stock;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM cash_movement_revision");
		jdbc.update("DELETE FROM cash_movement");
		jdbc.update("DELETE FROM source_allocation");
		jdbc.update("DELETE FROM trade_revision");
		jdbc.update("DELETE FROM trade");
		jdbc.update("DELETE FROM pledge_collateral");
		jdbc.update("DELETE FROM rate_setting WHERE loan_id IS NOT NULL");
		jdbc.update("DELETE FROM loan");
		jdbc.update("DELETE FROM recurring_item");
		jdbc.update("DELETE FROM cash_account");
		jdbc.update("DELETE FROM stock_price");
		jdbc.update("DELETE FROM dividend");
		jdbc.update("DELETE FROM stock");
		userId = user("st-schema");
		otherUserId = user("st-schema-other");
		account = insertAccount(userId, "交割戶");
		jdbc.update("INSERT INTO stock (code, name, kind, updated_at) VALUES ('0056', '元大高股息', 'ETF', NOW())");
		stock = jdbc.queryForObject("SELECT id FROM stock WHERE code = '0056'", Long.class);
	}

	@Test
	void stockCodeUniqueAndKind() {
		assertThatThrownBy(() -> jdbc.update("INSERT INTO stock (code, name, kind, updated_at) VALUES ('0056', 'x', "
				+ "'ETF', NOW())")).isInstanceOf(DataAccessException.class).hasMessageContaining("uk_stock_code");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO stock (code, name, kind, updated_at) VALUES ('9999', 'x', "
				+ "'FUND', NOW())")).satisfies(violates("ck_stock_kind"));
	}

	@Test
	void priceAndDividendUniquePerDay() {
		jdbc.update("INSERT INTO stock_price (stock_id, trade_date, close_price) VALUES (?, '2026-10-21', 58.75)", stock);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO stock_price (stock_id, trade_date, close_price) "
				+ "VALUES (?, '2026-10-21', 59)", stock)).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("uk_stock_price_day");
		jdbc.update("INSERT INTO dividend (stock_id, ex_date, cash_per_share, updated_at) VALUES (?, '2026-10-22', 1.72, "
				+ "NOW())", stock);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO dividend (stock_id, ex_date, updated_at) VALUES (?, "
				+ "'2026-10-22', NOW())", stock)).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("uk_dividend_ex_date");
	}

	@Test
	void tradeShapeByType() {
		assertThatCode(() -> trade("OPENING", null, 1000, null, null, null, "50000")).doesNotThrowAnyException();
		assertThatCode(() -> trade("BUY", account, 1000, "58.75", "23", null, "58773")).doesNotThrowAnyException();
		assertThatCode(() -> trade("SELL", account, 500, "60", "20", "30", "29950")).doesNotThrowAnyException();
		assertThatCode(() -> trade("STOCK_DIVIDEND", null, 50, null, null, null, "0")).doesNotThrowAnyException();
		assertThatThrownBy(() -> trade("BUY", null, 1000, "58.75", "23", null, "58773"))
			.satisfies(violates("ck_trade_shape"));
		assertThatThrownBy(() -> trade("SELL", account, 500, "60", "20", null, "29950"))
			.satisfies(violates("ck_trade_shape"));
		assertThatThrownBy(() -> trade("BUY", account, 0, "58.75", "23", null, "58773"))
			.satisfies(violates("ck_trade_amounts"));
		assertThatThrownBy(() -> trade("STOCK_DIVIDEND", null, 50, null, null, null, "10"))
			.satisfies(violates("ck_trade_shape"));
	}

	/** 一筆交易最多一筆沒作廢的現金異動；買賣的現金異動一定有交易，配息一定有股票。 */
	@Test
	void tradeCashMovementRules() {
		Long buy = trade("BUY", account, 1000, "58.75", "23", null, "58773");
		movement("STOCK_BUY", buy, null, null);
		assertThatThrownBy(() -> movement("STOCK_BUY", buy, null, null)).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("uk_cash_movement_trade");
		assertThatThrownBy(() -> movement("STOCK_BUY", null, null, null)).satisfies(violates("ck_cash_movement_stock"));
		assertThatThrownBy(() -> movement("DIVIDEND", null, null, "100")).satisfies(violates("ck_cash_movement_stock"));
		assertThatCode(() -> movement("DIVIDEND", null, stock, "544")).doesNotThrowAnyException();
		assertThatThrownBy(() -> movement("DEPOSIT", null, null, "1")).satisfies(violates("ck_cash_movement_stock"));
	}

	/** 資金來源分攤只能指向自己的交易與借款。 */
	@Test
	void allocationBelongsToOwner() {
		Long buy = trade("BUY", account, 1000, "58.75", "23", null, "58773");
		Long othersAccount = insertAccount(otherUserId, "別人的");
		jdbc.update("INSERT INTO loan (user_id, name, loan_type, bank, original_amount, origin, start_date, "
				+ "start_principal, pay_account_id, pay_day, posting_from, created_at) VALUES (?, '別人的', 'PLEDGE', "
				+ "'銀行', 1000, 'EXISTING', '2026-10-01', 1000, ?, 25, '2026-10-01', NOW())", otherUserId, othersAccount);
		Long othersLoan = jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);

		assertThatThrownBy(() -> jdbc.update("INSERT INTO source_allocation (trade_id, user_id, loan_id, amount) "
				+ "VALUES (?, ?, ?, 1000)", buy, userId, othersLoan)).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("fk_source_allocation_loan");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO source_allocation (trade_id, user_id, loan_id, amount) "
				+ "VALUES (?, ?, NULL, 1000)", buy, userId)).satisfies(violates("ck_source_allocation"));
	}

	@Test
	void collateralUniquePerLoanAndStock() {
		jdbc.update("INSERT INTO loan (user_id, name, loan_type, bank, original_amount, origin, start_date, "
				+ "start_principal, pay_account_id, pay_day, posting_from, created_at) VALUES (?, '質押', 'PLEDGE', "
				+ "'銀行', 1000, 'EXISTING', '2026-10-01', 1000, ?, 25, '2026-10-01', NOW())", userId, account);
		Long loan = jdbc.queryForObject("SELECT MAX(id) FROM loan", Long.class);
		jdbc.update("INSERT INTO pledge_collateral (user_id, loan_id, stock_id, shares) VALUES (?, ?, ?, 15000)", userId,
				loan, stock);

		assertThatThrownBy(() -> jdbc.update("INSERT INTO pledge_collateral (user_id, loan_id, stock_id, shares) "
				+ "VALUES (?, ?, ?, 1)", userId, loan, stock)).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("uk_pledge_collateral");
		assertThatCode(() -> jdbc.update("INSERT INTO rate_setting (user_id, kind, value, effective_date, created_at, "
				+ "loan_id) VALUES (?, 'MAINTENANCE_WARN', 1.5, '2026-10-01', NOW(), ?)", userId, loan))
			.doesNotThrowAnyException();
	}

	private static ThrowingConsumer<Throwable> violates(String constraint) {
		return ex -> assertThat(ex).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
	}

	private Long trade(String type, Long acc, long shares, String price, String fee, String tax, String amount) {
		jdbc.update("INSERT INTO trade (user_id, type, trade_date, stock_id, account_id, shares, price, fee, tax, amount, "
				+ "voided, created_at) VALUES (?, ?, '2026-10-21', ?, ?, ?, ?, ?, ?, ?, FALSE, NOW())", userId, type, stock,
				acc, shares, price == null ? null : new java.math.BigDecimal(price),
				fee == null ? null : new java.math.BigDecimal(fee), tax == null ? null : new java.math.BigDecimal(tax),
				new java.math.BigDecimal(amount));
		return jdbc.queryForObject("SELECT MAX(id) FROM trade", Long.class);
	}

	private void movement(String type, Long tradeId, Long stockId, String nhi) {
		jdbc.update("INSERT INTO cash_movement (user_id, type, movement_date, account_id, amount, voided, created_at, "
				+ "trade_id, stock_id, nhi_amount) VALUES (?, ?, '2026-10-21', ?, 1000, FALSE, NOW(), ?, ?, ?)", userId,
				type, account, tradeId, stockId, nhi == null ? null : new java.math.BigDecimal(nhi));
	}

	private Long user(String username) {
		if (!users.existsByUsername(username)) {
			users.save(AppUser.create(username, encoder.encode("password123"), username, Role.USER,
					LocalDateTime.now(clock)));
		}
		return users.findByUsername(username).orElseThrow().getId();
	}

	private Long insertAccount(Long user, String name) {
		jdbc.update("INSERT INTO cash_account (user_id, name, type, institution, created_at) "
				+ "VALUES (?, ?, 'SETTLEMENT', '玉山證券', NOW())", user, name);
		return jdbc.queryForObject("SELECT id FROM cash_account WHERE user_id = ? AND name = ?", Long.class, user, name);
	}

}
