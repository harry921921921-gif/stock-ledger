package com.sideproject.stockledger.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.sideproject.stockledger.entity.RateKind.Scope;
import com.sideproject.stockledger.entity.RateKind.Unit;

/** 費率種類的分類決定了「誰能改」（法定 vs 個人）和「畫面怎麼顯示」（百分比 vs 元）。 */
class RateKindTest {

	@Test
	void legalKindsAreSharedAndCannotBeEditedByUsers() {
		assertThat(kindsWith(Scope.LEGAL)).containsExactlyInAnyOrder(
				RateKind.BROKERAGE_FEE_RATE,
				RateKind.SECURITIES_TAX_STOCK,
				RateKind.SECURITIES_TAX_ETF,
				RateKind.SECURITIES_TAX_BOND_ETF,
				RateKind.SECURITIES_TAX_DAY_TRADE,
				RateKind.NHI_SUPPLEMENT_RATE,
				RateKind.NHI_SUPPLEMENT_THRESHOLD);
	}

	@Test
	void personalKinds() {
		assertThat(kindsWith(Scope.PERSONAL)).containsExactlyInAnyOrder(
				RateKind.BROKERAGE_DISCOUNT,
				RateKind.MIN_FEE_BOARD_LOT,
				RateKind.MIN_FEE_ODD_LOT);
	}

	@Test
	void amountKindsAreShownInDollars() {
		assertThat(Arrays.stream(RateKind.values()).filter(k -> k.unit() == Unit.AMOUNT)).containsExactlyInAnyOrder(
				RateKind.NHI_SUPPLEMENT_THRESHOLD,
				RateKind.MIN_FEE_BOARD_LOT,
				RateKind.MIN_FEE_ODD_LOT);
	}

	/** 資料庫 kind 欄位是 VARCHAR(40)（Task 6），名稱不能超過。 */
	@Test
	void namesFitDatabaseColumn() {
		assertThat(RateKind.values()).allSatisfy(k -> assertThat(k.name().length()).isLessThanOrEqualTo(40));
	}

	@Test
	void everyKindHasChineseLabel() {
		assertThat(RateKind.values()).allSatisfy(k -> assertThat(k.label()).isNotBlank());
	}

	private static RateKind[] kindsWith(Scope scope) {
		return Arrays.stream(RateKind.values()).filter(k -> k.scope() == scope).toArray(RateKind[]::new);
	}

}
