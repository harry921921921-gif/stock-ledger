package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.sideproject.stockledger.calculator.InstallmentCalculator;
import com.sideproject.stockledger.calculator.PrincipalChange;
import com.sideproject.stockledger.calculator.PaymentChange;
import com.sideproject.stockledger.calculator.RateChange;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;

/**
 * 一筆借款推算所需的事實：利率版本與所有異動（含作廢的）。剩餘本金、攤還表、利息都從這裡推算，不另存。
 * 讀取由 LoanService／LoanPostingService 負責（都只用這筆借款自己的 user_id）。
 *
 * @param movements 這筆借款的所有異動，舊的在前（含作廢的）
 * @param payments  銀行每月扣款金額的版本（每月固定還款，DR-048）；V11 以前的借款是空的
 */
record LoanFacts(Loan loan, List<RateChange> rates, List<CashMovement> movements, List<PaymentChange> payments) {

	List<CashMovement> active() {
		return movements.stream().filter(m -> !m.isVoided()).toList();
	}

	/** 起點餘額：既有借款是起算本金；新撥款是 0（本金來自撥款異動，改了撥款金額本金就跟著改）。 */
	BigDecimal startBalance() {
		return loan.getOrigin() == LoanOrigin.EXISTING ? loan.getStartPrincipal() : BigDecimal.ZERO;
	}

	/** 所有本金變動：撥款為正、還本（含信貸每期的本金）為負。 */
	List<PrincipalChange> principalChanges() {
		return active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_DISBURSEMENT
					|| m.getType() == CashMovementType.LOAN_PRINCIPAL)
			.map(m -> new PrincipalChange(m.getMovementDate(),
					m.getType() == CashMovementType.LOAN_DISBURSEMENT ? m.getAmount() : m.getAmount().negate()))
			.toList();
	}

	/** 某一天（含當天的變動）的剩餘本金。 */
	BigDecimal remaining(LocalDate asOf) {
		return principalChanges().stream()
			.filter(c -> !c.date().isAfter(asOf))
			.map(PrincipalChange::delta)
			.reduce(startBalance(), BigDecimal::add);
	}

	/**
	 * 某一天已經還清：剩餘本金 ≤ 0，而且那天以前（含）有沒作廢的還本。只看本金是 0 不夠：
	 * 新撥款在撥款日以前（或撥款日記在未來）本金也是 0，但還沒借到、也還沒還（設計文件 2026-11-04-pledged-stocks 第 6 節）。
	 * 質押中、資金來源卡片的「已還清，擔保股票已解除質押」都用這一個判斷。
	 */
	boolean repaid(LocalDate asOf) {
		boolean anyRepayment = active().stream()
			.anyMatch(m -> m.getType() == CashMovementType.LOAN_PRINCIPAL && !m.getMovementDate().isAfter(asOf));
		return anyRepayment && remaining(asOf).signum() <= 0;
	}

	/** 剩餘本金在任何一天的最小值（從起點依日期加上每一筆變動）；小於 0 代表還本超過借款。 */
	BigDecimal minRemaining() {
		BigDecimal balance = startBalance();
		BigDecimal min = balance;
		for (PrincipalChange c : principalChanges().stream().sorted((a, b) -> a.date().compareTo(b.date())).toList()) {
			balance = balance.add(c.delta());
			min = min.min(balance);
		}
		return min;
	}

	/** 信貸攤還表的輸入：起算時的本金、提前還本（使用者自己記的還本，沒有期別）、已經記下的期數。 */
	InstallmentCalculator.Input installmentInput() {
		// 信貸不能再動撥：所有撥款都是起點本金（撥款日改成銀行實際入帳日、晚於起算日也一樣）
		BigDecimal start = startBalance().add(active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_DISBURSEMENT)
			.map(CashMovement::getAmount)
			.reduce(BigDecimal.ZERO, BigDecimal::add));
		List<PrincipalChange> prepayments = active().stream()
			.filter(m -> m.getPeriod() == null && m.getType() == CashMovementType.LOAN_PRINCIPAL)
			.map(m -> new PrincipalChange(m.getMovementDate(), m.getAmount().negate()))
			.toList();
		return new InstallmentCalculator.Input(start, loan.getRemainingPeriods(), loan.getFirstPeriod(),
				loan.getPayDay(), rates, prepayments, posted(), payments);
	}

	/**
	 * 已經記下的期數：沒作廢的本金與利息加總。一期只要有過異動（含作廢的）就算已經過去；
	 * 全部作廢的期數是 (0, 0)，本金留到最後一期（設計文件 11-1）。
	 */
	Map<YearMonth, InstallmentCalculator.Posted> posted() {
		Map<YearMonth, List<CashMovement>> byPeriod = movements.stream()
			.filter(m -> m.getPeriod() != null && (m.getType() == CashMovementType.LOAN_PRINCIPAL
					|| m.getType() == CashMovementType.LOAN_INTEREST))
			.collect(Collectors.groupingBy(CashMovement::getPeriod));
		Map<YearMonth, InstallmentCalculator.Posted> result = new HashMap<>();
		byPeriod.forEach((period, list) -> result.put(period, new InstallmentCalculator.Posted(
				sum(list, CashMovementType.LOAN_PRINCIPAL), sum(list, CashMovementType.LOAN_INTEREST))));
		return result;
	}

	/** 這一期這種類型是否已經產生過（含作廢的）：作廢後不再自動補。 */
	boolean exists(CashMovementType type, YearMonth period) {
		return movements.stream().anyMatch(m -> m.getType() == type && period.equals(m.getPeriod()));
	}

	/** 某一天以前（不含）最後一筆沒作廢的繳息日期；沒有就是起算日。質押計息期間的起點。 */
	LocalDate lastInterestPaidBefore(LocalDate day) {
		return active().stream()
			.filter(m -> m.getType() == CashMovementType.LOAN_INTEREST && m.getMovementDate().isBefore(day))
			.map(CashMovement::getMovementDate)
			.max(LocalDate::compareTo)
			.filter(d -> d.isAfter(loan.getStartDate()))
			.orElse(loan.getStartDate());
	}

	/** 本金變成 0 的日期（之後沒有再動撥）；還沒還清為 empty。 */
	Optional<LocalDate> payoffDate() {
		return payoffDate(startBalance(), principalChanges());
	}

	/** 同上，但本金變動由呼叫端給（修改借款時用「新合約應有的」本金變動，DR-051）。 */
	static Optional<LocalDate> payoffDate(BigDecimal startBalance, List<PrincipalChange> principalChanges) {
		List<PrincipalChange> changes = principalChanges.stream()
			.sorted((a, b) -> a.date().compareTo(b.date()))
			.toList();
		BigDecimal balance = startBalance;
		LocalDate payoff = null;
		for (PrincipalChange c : changes) {
			balance = balance.add(c.delta());
			payoff = balance.signum() <= 0 ? c.date() : null;
		}
		return Optional.ofNullable(payoff);
	}

	private static BigDecimal sum(List<CashMovement> list, CashMovementType type) {
		return list.stream()
			.filter(m -> m.getType() == type && !m.isVoided())
			.map(CashMovement::getAmount)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

}
