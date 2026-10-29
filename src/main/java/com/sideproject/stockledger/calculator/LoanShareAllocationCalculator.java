package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 股票的資金來源按張數分配（設計文件 2026-10-25-onboarding 3.4）：每筆借款出的錢 ＝ 張數 ÷ 總張數 × 這筆交易的總成本。
 * 每筆借款四捨五入到元（HALF_UP），差額算自己的錢；全部張數都是借款（沒有自己的錢）時，最後一筆借款吸收差額，
 * 加起來剛好等於總成本。存進資料庫的仍是金額（DR-044），張數只是換算方式。純 Java、不依賴 Spring。
 */
public final class LoanShareAllocationCalculator {

	private static final long SHARES_PER_LOT = 1000;

	/** 中間值保留 10 位（CLAUDE.md 計算規則）。 */
	private static final int SCALE = 10;

	/** 張數上限：張數 × 1000 還放得進 long。 */
	private static final BigDecimal MAX_LOTS = BigDecimal.valueOf(Long.MAX_VALUE / SHARES_PER_LOT);

	private LoanShareAllocationCalculator() {
	}

	/**
	 * 各筆借款的張數換成金額（元）。先乘再除（張數 × 1000 × 總成本 ÷ 總股數），避免先除時比例被截到 10 位、總成本大時多進位一次。
	 *
	 * @param totalCost   這筆交易的總成本（買進含手續費；目前持有是平均成本 × 股數）
	 * @param totalShares 這筆的總股數，要大於 0
	 * @param loanLots    每筆借款的張數（照畫面順序）
	 */
	public static List<BigDecimal> amountsFromLots(BigDecimal totalCost, long totalShares, List<Long> loanLots) {
		Objects.requireNonNull(totalCost, "totalCost");
		Objects.requireNonNull(loanLots, "loanLots");
		if (totalShares <= 0) {
			throw new IllegalArgumentException("總股數要大於 0");
		}
		BigDecimal shares = BigDecimal.valueOf(totalShares);
		List<BigDecimal> amounts = new ArrayList<>();
		long loanShares = 0;
		for (Long lots : loanLots) {
			long s = Math.multiplyExact(Objects.requireNonNull(lots, "lots").longValue(), SHARES_PER_LOT);
			loanShares = Math.addExact(loanShares, s);
			amounts.add(BigDecimal.valueOf(s)
				.multiply(totalCost)
				.divide(shares, SCALE, RoundingMode.HALF_UP)
				.setScale(0, RoundingMode.HALF_UP));
		}
		if (!amounts.isEmpty() && loanShares == totalShares) {
			BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
			int last = amounts.size() - 1;
			amounts.set(last, amounts.get(last).add(totalCost.subtract(sum)));
		}
		return amounts;
	}

	/**
	 * 金額換回張數（金額 ÷ 總成本 × 總股數 ÷ 1000，四捨五入）。只有用這些張數再換一次金額（{@link #amountsFromLots}）
	 * 剛好等於原本的金額時才回傳，否則 empty（畫面改用填金額，不改動使用者的數字）。總成本或總股數不大於 0 也是 empty。
	 */
	public static Optional<List<Long>> lotsFromAmounts(BigDecimal totalCost, long totalShares,
			List<BigDecimal> amounts) {
		Objects.requireNonNull(totalCost, "totalCost");
		Objects.requireNonNull(amounts, "amounts");
		if (totalCost.signum() <= 0 || totalShares <= 0) {
			return Optional.empty();
		}
		BigDecimal divisor = totalCost.multiply(BigDecimal.valueOf(SHARES_PER_LOT));
		List<Long> lots = new ArrayList<>();
		for (BigDecimal amount : amounts) {
			BigDecimal lot = Objects.requireNonNull(amount, "amount")
				.multiply(BigDecimal.valueOf(totalShares))
				.divide(divisor, SCALE, RoundingMode.HALF_UP)
				.setScale(0, RoundingMode.HALF_UP);
			// 金額大到張數 × 1000 放不進 long：一定換不回來，回傳 empty（不丟 ArithmeticException）
			if (lot.abs().compareTo(MAX_LOTS) > 0) {
				return Optional.empty();
			}
			lots.add(lot.longValueExact());
		}
		List<BigDecimal> back;
		try {
			back = amountsFromLots(totalCost, totalShares, lots);
		}
		catch (ArithmeticException ex) { // 好幾筆大張數加起來超過 long
			return Optional.empty();
		}
		for (int i = 0; i < amounts.size(); i++) {
			if (back.get(i).compareTo(amounts.get(i)) != 0) {
				return Optional.empty();
			}
		}
		return Optional.of(List.copyOf(lots));
	}

}
