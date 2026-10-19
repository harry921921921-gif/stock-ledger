package com.sideproject.stockledger.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 「依生效日期取哪一筆費率」的規則（spec 4.5-1）：純 Java，不需要 Spring。 */
class EffectiveRateSelectorTest {

	private static final Long ALICE = 1L;

	@Test
	void picksLatestOnOrBeforeDate() {
		// 故意倒過來排：結果不能依賴清單順序
		List<RateCandidate> nhi = List.of(
				global(2L, "2021-01-01", "0.0211"),
				global(1L, "2016-01-01", "0.0191"));

		assertThat(valueOn(nhi, "2020-12-31")).isEqualByComparingTo("0.0191");
		assertThat(valueOn(nhi, "2021-01-01")).isEqualByComparingTo("0.0211");
	}

	@Test
	void futureRateNotYetEffective() {
		List<RateCandidate> future = List.of(global(1L, "2027-01-01", "0.001"));

		assertThat(EffectiveRateSelector.select(future, LocalDate.parse("2026-12-31"))).isEmpty();
	}

	/** 法規追溯修改時只要新增一列：同一天有兩筆，後來新增（id 較大）的優先。 */
	@Test
	void sameDateHigherIdWins() {
		List<RateCandidate> bondEtf = List.of(
				global(9L, "2027-01-01", "0"),
				global(5L, "2027-01-01", "0.001"));

		assertThat(valueOn(bondEtf, "2027-01-01")).isEqualByComparingTo("0");
	}

	@Test
	void userValueOverridesGlobal() {
		List<RateCandidate> minFee = List.of(
				global(1L, "2000-01-01", "20"),
				personal(2L, ALICE, "2026-10-01", "1"));

		assertThat(valueOn(minFee, "2026-10-19")).isEqualByComparingTo("1");
		assertThat(valueOn(minFee, "2026-09-30")).isEqualByComparingTo("20");
	}

	/** 個人設定一旦生效，就算之後全域預設有新版本，仍然用個人的值。 */
	@Test
	void userValueWinsEvenOverNewerGlobal() {
		List<RateCandidate> minFee = List.of(
				personal(1L, ALICE, "2026-10-01", "1"),
				global(2L, "2027-01-01", "25"));

		assertThat(valueOn(minFee, "2027-06-01")).isEqualByComparingTo("1");
	}

	@Test
	void userValueWinsOverGlobalOnSameDate() {
		List<RateCandidate> minFee = List.of(
				personal(1L, ALICE, "2026-10-01", "1"),
				global(2L, "2026-10-01", "20"));

		assertThat(valueOn(minFee, "2026-10-01")).isEqualByComparingTo("1");
	}

	/** 使用者同一天輸入兩次（例如打錯再改），以後輸入的為準。 */
	@Test
	void sameDatePersonalVersionsHigherIdWins() {
		List<RateCandidate> discount = List.of(
				personal(8L, ALICE, "2026-10-01", "0.28"),
				personal(3L, ALICE, "2026-10-01", "0.6"));

		assertThat(valueOn(discount, "2026-10-19")).isEqualByComparingTo("0.28");
	}

	@Test
	void emptyListReturnsEmpty() {
		assertThat(EffectiveRateSelector.select(List.of(), LocalDate.parse("2026-10-19"))).isEmpty();
	}

	@Test
	void returnsWholeCandidateSoCallerKnowsSource() {
		RateCandidate mine = personal(7L, ALICE, "2026-10-01", "1");

		assertThat(EffectiveRateSelector.select(List.of(global(1L, "2000-01-01", "20"), mine),
				LocalDate.parse("2026-10-19"))).contains(mine);
	}

	@Test
	void nullArgumentsRejected() {
		assertThatThrownBy(() -> EffectiveRateSelector.select(null, LocalDate.parse("2026-10-19")))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> EffectiveRateSelector.select(List.of(), null))
			.isInstanceOf(NullPointerException.class);
	}

	private static BigDecimal valueOn(List<RateCandidate> candidates, String on) {
		return EffectiveRateSelector.select(candidates, LocalDate.parse(on)).orElseThrow().value();
	}

	private static RateCandidate global(Long id, String date, String value) {
		return new RateCandidate(id, null, LocalDate.parse(date), new BigDecimal(value));
	}

	private static RateCandidate personal(Long id, Long userId, String date, String value) {
		return new RateCandidate(id, userId, LocalDate.parse(date), new BigDecimal(value));
	}

}
