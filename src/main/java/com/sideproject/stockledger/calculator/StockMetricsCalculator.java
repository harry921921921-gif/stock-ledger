package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 股票的各種數字（spec 5.2、5.4、5.5、5.6）。純 Java、不依賴 Spring。
 * 比例都是百分比、HALF_UP 到 0.01；二代健保 HALF_UP 到元；股利無條件捨去到元。
 */
public final class StockMetricsCalculator {

	private static final int SCALE = 10;
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	/** 股票股利以面額每股 10 元計算二代健保（spec 4.2-5）。 */
	private static final BigDecimal FACE_VALUE = BigDecimal.TEN;

	private StockMetricsCalculator() {
	}

	/** 維持率狀態：正常、低於提醒線（黃）、低於追繳線（紅）。 */
	public enum Level {
		OK, WARN, CALL
	}

	public static BigDecimal marketValue(long shares, BigDecimal price) {
		return price.multiply(BigDecimal.valueOf(shares));
	}

	/** 股票報酬率 ＝ (市值 − 成本) ÷ 成本；成本 0 時為 empty。 */
	public static Optional<BigDecimal> returnPercent(BigDecimal marketValue, BigDecimal cost) {
		return percent(marketValue.subtract(cost), cost);
	}

	/** 含息總報酬率 ＝ (市值 ＋ 累計已領配息 − 成本) ÷ 成本。 */
	public static Optional<BigDecimal> totalReturnPercent(BigDecimal marketValue, BigDecimal dividends, BigDecimal cost) {
		return percent(marketValue.add(dividends).subtract(cost), cost);
	}

	/**
	 * 二代健保補充保費：單次股利（現金股利 ＋ 股票股利面額）達門檻才扣，四捨五入到元（spec 5.2）。
	 */
	public static BigDecimal nhi(BigDecimal cashDividend, long stockDividendShares, BigDecimal rate,
			BigDecimal threshold) {
		BigDecimal base = cashDividend.add(FACE_VALUE.multiply(BigDecimal.valueOf(stockDividendShares)));
		if (base.compareTo(threshold) < 0) {
			return BigDecimal.ZERO;
		}
		return base.multiply(rate).setScale(0, RoundingMode.HALF_UP);
	}

	/** 應領現金股利 ＝ 持有股數 × 每股現金股利，無條件捨去到元（我的判斷）。 */
	public static BigDecimal cashDividend(long shares, BigDecimal perShare) {
		return perShare.multiply(BigDecimal.valueOf(shares)).setScale(0, RoundingMode.DOWN);
	}

	/** 預估年配息 ＝ 最近一期每股現金股利 × 年配息次數 × 持有股數，四捨五入到元。 */
	public static BigDecimal annualDividend(BigDecimal lastPerShare, int timesPerYear, long shares) {
		return lastPerShare.multiply(BigDecimal.valueOf(timesPerYear))
			.multiply(BigDecimal.valueOf(shares))
			.setScale(0, RoundingMode.HALF_UP);
	}

	/** 質押維持率 ＝ 擔保股票市值 ÷ 借款餘額（%）；餘額 0 時為 empty。 */
	public static Optional<BigDecimal> maintenancePercent(BigDecimal collateralValue, BigDecimal loanBalance) {
		if (loanBalance.signum() <= 0) {
			return Optional.empty();
		}
		return Optional.of(collateralValue.multiply(HUNDRED).divide(loanBalance, 2, RoundingMode.HALF_UP));
	}

	public static Level level(BigDecimal ratioPercent, BigDecimal warnPercent, BigDecimal callPercent) {
		if (ratioPercent.compareTo(callPercent) < 0) {
			return Level.CALL;
		}
		if (ratioPercent.compareTo(warnPercent) < 0) {
			return Level.WARN;
		}
		return Level.OK;
	}

	private static Optional<BigDecimal> percent(BigDecimal gain, BigDecimal base) {
		if (base.signum() <= 0) {
			return Optional.empty();
		}
		return Optional.of(gain.multiply(HUNDRED).divide(base, SCALE, RoundingMode.HALF_UP).setScale(2,
				RoundingMode.HALF_UP));
	}

}
