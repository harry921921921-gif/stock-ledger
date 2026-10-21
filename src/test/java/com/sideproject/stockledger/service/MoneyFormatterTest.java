package com.sideproject.stockledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** 全站金額格式：千分位、「−」負號、需要時加「+」（審核：正數的餘額調整原本看不出是增加）。 */
class MoneyFormatterTest {

	private final MoneyFormatter money = new MoneyFormatter();

	@Test
	void plainUsesThousandsSeparatorAndRealMinus() {
		assertThat(money.plain(new BigDecimal("20000.0000"))).isEqualTo("20,000");
		assertThat(money.plain(new BigDecimal("-26111"))).isEqualTo("−26,111");
		assertThat(money.plain(BigDecimal.ZERO)).isEqualTo("0");
	}

	@Test
	void signedAddsPlusOnlyForPositive() {
		assertThat(money.signed(new BigDecimal("200"))).isEqualTo("+200");
		assertThat(money.signed(new BigDecimal("-1200"))).isEqualTo("−1,200");
		assertThat(money.signed(BigDecimal.ZERO)).isEqualTo("0");
	}

	@Test
	void nullShowsDash() {
		assertThat(money.plain(null)).isEqualTo("—");
		assertThat(money.signed(null)).isEqualTo("—");
	}

}
