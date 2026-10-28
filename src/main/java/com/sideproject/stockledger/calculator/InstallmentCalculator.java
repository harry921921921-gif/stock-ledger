package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 信貸本息平均攤還（spec 4.8、5.3、DR-018、設計文件 4、11-1、11-4）。純 Java、不依賴 Spring。
 * <ul>
 * <li>月付金 ＝ P × r(1+r)^n ÷ ((1+r)^n − 1)，r ＝ 年利率 ÷ 12；四捨五入到元。</li>
 * <li>每期利息 ＝ 期初剩餘本金 × 月利率，無條件進位到元；本金 ＝ 月付金 − 利息；最後一期本金 ＝ 剩餘本金。</li>
 * <li>月付金平常固定，只在利率變動或提前還本後重算（剩餘本金、剩餘期數、當時利率）。</li>
 * <li>已經記下的期數用實際金額；差額留到最後一期。</li>
 * </ul>
 */
public final class InstallmentCalculator {

	private static final int SCALE = 10;
	private static final BigDecimal MONTHS = BigDecimal.valueOf(12);

	private InstallmentCalculator() {
	}

	/**
	 * 某一期實際記下的本金與利息（沒作廢的異動加總）。作廢的期數傳 (0, 0)：也算已經過去，本金留到最後一期。
	 * 其中一項是 null 表示那一項照公式算（修改借款時只保留使用者手改過的那一項，DR-051）。
	 */
	public record Posted(BigDecimal principal, BigDecimal interest) {
	}

	/**
	 * @param startPrincipal   起算時的剩餘本金
	 * @param remainingPeriods 起算時的剩餘期數
	 * @param firstPeriod      第一期扣款的月份
	 * @param payDay           每月扣款日（1～31，沒有那天用月底）
	 * @param rates            利率版本（至少一個生效日 ≤ 第一期扣款日）
	 * @param prepayments      提前還本（delta 為負），日期早於某期扣款日的，從那一期起生效
	 * @param posted           已經記下的期數
	 * @param payments         銀行每月扣款金額的版本（DR-048）；空清單＝照公式算月付金（DR-018）
	 */
	public record Input(BigDecimal startPrincipal, int remainingPeriods, YearMonth firstPeriod, int payDay,
			List<RateChange> rates, List<PrincipalChange> prepayments, Map<YearMonth, Posted> posted,
			List<PaymentChange> payments) {

		/** V11 以前的借款：沒有銀行扣款版本，月付金照公式算。 */
		public Input(BigDecimal startPrincipal, int remainingPeriods, YearMonth firstPeriod, int payDay,
				List<RateChange> rates, List<PrincipalChange> prepayments, Map<YearMonth, Posted> posted) {
			this(startPrincipal, remainingPeriods, firstPeriod, payDay, rates, prepayments, posted, List.of());
		}

		public Input {
			Objects.requireNonNull(startPrincipal, "startPrincipal");
			Objects.requireNonNull(firstPeriod, "firstPeriod");
			Objects.requireNonNull(rates, "rates");
			Objects.requireNonNull(prepayments, "prepayments");
			Objects.requireNonNull(posted, "posted");
			Objects.requireNonNull(payments, "payments");
			if (remainingPeriods < 1) {
				throw new IllegalArgumentException("剩餘期數至少 1 期");
			}
			if (payDay < 1 || payDay > 31) {
				throw new IllegalArgumentException("扣款日要在 1～31");
			}
		}

	}

	/** 月付金，四捨五入到元。利率 0 時本金平均分攤。 */
	public static BigDecimal monthlyPayment(BigDecimal principal, BigDecimal annualRate, int periods) {
		if (annualRate.signum() == 0) {
			return principal.divide(BigDecimal.valueOf(periods), 0, RoundingMode.HALF_UP);
		}
		BigDecimal r = monthlyRate(annualRate);
		BigDecimal growth = BigDecimal.ONE.add(r).pow(periods, MathContext.DECIMAL128);
		return principal.multiply(r)
			.multiply(growth)
			.divide(growth.subtract(BigDecimal.ONE), SCALE, RoundingMode.HALF_UP)
			.setScale(0, RoundingMode.HALF_UP);
	}

	/**
	 * 一期的利息 ＝ 剩餘本金 × 年利率 ÷ 12，無條件進位到元（DR-009）。先乘後除：先把月利率四捨五入再乘，
	 * 例如 3.5% ÷ 12 ＝ 0.0029166667，24 萬的利息會變成 700.000008 而多進位 1 元。
	 */
	public static BigDecimal monthlyInterest(BigDecimal remaining, BigDecimal annualRate) {
		return remaining.multiply(annualRate).divide(MONTHS, 0, RoundingMode.UP);
	}

	/** 從第一期到還清（或期數用完）的攤還表。全部提前還清時之後沒有期數。 */
	public static List<Installment> schedule(Input in) {
		List<Installment> result = new ArrayList<>();
		BigDecimal remaining = in.startPrincipal();
		BigDecimal payment = null;
		BigDecimal currentRate = null;
		LocalDate previousDue = null;
		int total = in.remainingPeriods();
		for (int number = 1; number <= total; number++) {
			YearMonth period = in.firstPeriod().plusMonths(number - 1L);
			LocalDate due = period.atDay(Math.min(in.payDay(), period.lengthOfMonth()));
			boolean recalc = payment == null;
			// 上一期扣款日（含）之後、這一期扣款日之前的提前還本
			for (PrincipalChange c : in.prepayments()) {
				boolean afterPrevious = previousDue == null || !c.date().isBefore(previousDue);
				if (afterPrevious && c.date().isBefore(due)) {
					remaining = remaining.add(c.delta());
					recalc = true;
				}
			}
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal rate = RateChange.rateOn(in.rates(), due);
			if (currentRate != null && rate.compareTo(currentRate) != 0) {
				recalc = true;
			}
			currentRate = rate;
			int left = total - number + 1;
			BigDecimal interest = monthlyInterest(remaining, rate);
			Optional<BigDecimal> bank = PaymentChange.amountOn(in.payments(), due);
			if (bank.isPresent()) {
				// 以銀行實際扣款為準（DR-048）：只用利率拆出利息，其餘是本金
				payment = bank.get();
				if (left > 1 && payment.compareTo(interest) < 0) {
					throw new IllegalArgumentException("每月扣款 " + payment.stripTrailingZeros().toPlainString() + " 元比當期利息 "
							+ interest.toPlainString() + " 元少");
				}
			}
			else if (recalc) {
				payment = monthlyPayment(remaining, rate, left);
			}
			BigDecimal principal = left == 1 ? remaining : payment.subtract(interest).min(remaining);
			Posted posted = in.posted().get(period);
			if (posted != null && posted.principal() != null) {
				principal = posted.principal();
			}
			if (posted != null && posted.interest() != null) {
				interest = posted.interest();
			}
			remaining = remaining.subtract(principal);
			result.add(new Installment(number, period, due, principal, interest, remaining));
			previousDue = due;
		}
		return result;
	}

	private static BigDecimal monthlyRate(BigDecimal annualRate) {
		return annualRate.divide(MONTHS, SCALE, RoundingMode.HALF_UP);
	}

}
