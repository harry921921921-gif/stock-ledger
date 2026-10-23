package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 手續費與證交稅（spec 5.1、4.2-7、DR-009）。純 Java、不依賴 Spring。
 * <ul>
 * <li>成交金額 ＝ 股數 × 價格，無條件捨去到元（我的判斷：零股會有角）。</li>
 * <li>手續費 ＝ max(floor(成交金額 × 手續費率 × 折扣), 最低手續費)；股數不是 1,000 的倍數就是零股，用零股最低手續費。</li>
 * <li>證交稅 ＝ floor(賣出成交金額 × 稅率)。</li>
 * </ul>
 */
public final class FeeCalculator {

	private static final long BOARD_LOT = 1000;

	private FeeCalculator() {
	}

	/**
	 * 交易日適用的費率。
	 *
	 * @param feeRate        手續費率上限（0.001425）
	 * @param discount       折扣（2.8 折 ＝ 0.28；沒有折扣 ＝ 1）
	 * @param minFeeBoardLot 整股最低手續費（元）
	 * @param minFeeOddLot   零股最低手續費（元）
	 * @param taxRate        證交稅率（依商品）
	 */
	public record Rates(BigDecimal feeRate, BigDecimal discount, BigDecimal minFeeBoardLot, BigDecimal minFeeOddLot,
			BigDecimal taxRate) {

		public Rates {
			Objects.requireNonNull(feeRate, "feeRate");
			Objects.requireNonNull(discount, "discount");
			Objects.requireNonNull(minFeeBoardLot, "minFeeBoardLot");
			Objects.requireNonNull(minFeeOddLot, "minFeeOddLot");
			Objects.requireNonNull(taxRate, "taxRate");
		}

	}

	public static BigDecimal tradeAmount(long shares, BigDecimal price) {
		return price.multiply(BigDecimal.valueOf(shares)).setScale(0, RoundingMode.DOWN);
	}

	public static boolean isOddLot(long shares) {
		return shares % BOARD_LOT != 0;
	}

	public static BigDecimal fee(BigDecimal tradeAmount, long shares, Rates rates) {
		BigDecimal fee = tradeAmount.multiply(rates.feeRate()).multiply(rates.discount()).setScale(0, RoundingMode.DOWN);
		BigDecimal minimum = isOddLot(shares) ? rates.minFeeOddLot() : rates.minFeeBoardLot();
		return fee.max(minimum);
	}

	public static BigDecimal tax(BigDecimal tradeAmount, Rates rates) {
		return tradeAmount.multiply(rates.taxRate()).setScale(0, RoundingMode.DOWN);
	}

	/** 賣出的預估入帳 ＝ 成交金額 − 手續費 − 證交稅（沒有融資）。 */
	public static BigDecimal sellProceeds(BigDecimal tradeAmount, BigDecimal fee, BigDecimal tax) {
		return tradeAmount.subtract(fee).subtract(tax);
	}

}
