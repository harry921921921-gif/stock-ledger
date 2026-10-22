package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 某個月份的借款利息（本月淨資產變動的「利息」一行，DR-008 應計制、設計文件 6、11-2）。純 Java、不依賴 Spring。
 * <ul>
 * <li>信貸：扣款日在這個月的那幾期的利息（已記的用實際金額，攤還表已經處理）。</li>
 * <li>質押：已經繳息的期間用實際金額，按「餘額 × 利率 × 天數」的比例分到各月；還沒繳息的天數用應計數，
 * 本月只算到今天（含）。</li>
 * </ul>
 * 回傳值保留 10 位，顯示時再四捨五入到元。
 */
public final class LoanAccrualCalculator {

	private static final int SCALE = 10;

	private LoanAccrualCalculator() {
	}

	/** 質押的一筆實際繳息：繳息日與沒作廢的金額。 */
	public record Payment(LocalDate date, BigDecimal amount) {
	}

	public static BigDecimal creditMonth(List<Installment> schedule, YearMonth month) {
		Objects.requireNonNull(schedule, "schedule");
		return schedule.stream()
			.filter(i -> YearMonth.from(i.dueDate()).equals(month))
			.map(Installment::interest)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/**
	 * @param start    起算日（第一個計息期間的起點）
	 * @param payments 沒作廢的繳息
	 * @param asOf     今天：還沒繳息的天數只算到這一天（含）
	 */
	public static BigDecimal pledgeMonth(BigDecimal startBalance, List<PrincipalChange> changes,
			List<RateChange> rates, LocalDate start, List<Payment> payments, YearMonth month, LocalDate asOf) {
		Objects.requireNonNull(payments, "payments");
		LocalDate monthStart = month.atDay(1);
		LocalDate monthEnd = month.atEndOfMonth().plusDays(1);
		BigDecimal total = BigDecimal.ZERO;
		LocalDate from = start;
		for (Payment p : payments.stream().sorted(Comparator.comparing(Payment::date)).toList()) {
			if (!p.date().isAfter(from)) {
				// 同一天繳了兩次（例如還清當天）：金額算在那一天的月份
				if (YearMonth.from(p.date()).equals(month)) {
					total = total.add(p.amount());
				}
				continue;
			}
			total = total.add(share(startBalance, changes, rates, from, p.date(), p.amount(), monthStart, monthEnd));
			from = p.date();
		}
		// 還沒繳息的天數：從最後一次繳息到今天（含）
		LocalDate tailEnd = min(asOf.plusDays(1), monthEnd);
		LocalDate tailStart = max(from, monthStart);
		if (tailStart.isBefore(tailEnd)) {
			total = total.add(DailyInterestCalculator.interest(startBalance, changes, rates, tailStart, tailEnd));
		}
		return total.setScale(SCALE, RoundingMode.HALF_UP);
	}

	/** 實付金額 × （這個期間落在這個月的「餘額 × 利率」）÷（整個期間的「餘額 × 利率」）。 */
	private static BigDecimal share(BigDecimal startBalance, List<PrincipalChange> changes, List<RateChange> rates,
			LocalDate from, LocalDate to, BigDecimal paid, LocalDate monthStart, LocalDate monthEnd) {
		LocalDate overlapStart = max(from, monthStart);
		LocalDate overlapEnd = min(to, monthEnd);
		if (!overlapStart.isBefore(overlapEnd)) {
			return BigDecimal.ZERO;
		}
		BigDecimal whole = DailyInterestCalculator.balanceRateSum(startBalance, changes, rates, from, to);
		if (whole.signum() == 0) {
			// 期間內餘額都是 0 還有繳息（例如使用者補記）：整筆算在繳息日的月份
			return to.isBefore(monthEnd) && !to.isBefore(monthStart) ? paid : BigDecimal.ZERO;
		}
		BigDecimal part = DailyInterestCalculator.balanceRateSum(startBalance, changes, rates, overlapStart, overlapEnd);
		return paid.multiply(part).divide(whole, SCALE, RoundingMode.HALF_UP);
	}

	private static LocalDate max(LocalDate a, LocalDate b) {
		return a.isAfter(b) ? a : b;
	}

	private static LocalDate min(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}

}
