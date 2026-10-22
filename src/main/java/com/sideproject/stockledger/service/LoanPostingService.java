package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sideproject.stockledger.calculator.DailyInterestCalculator;
import com.sideproject.stockledger.calculator.Installment;
import com.sideproject.stockledger.calculator.InstallmentCalculator;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.LoanRepository;

/**
 * 借款自動記帳（設計文件 4、5、11）：信貸每期扣款日記還本與利息兩筆；質押每月繳息日記一筆利息，還清當天記最後一段。
 * app 啟動時補記，每天 00:10（Asia/Taipei）再檢查一次。同一期只產生一次；作廢的期數不再自動補；不產生未來日期。
 * <p>
 * 這是系統排程、沒有登入者：逐一處理每個使用者的借款，只讀寫每筆借款自己的 user_id（DR-036 的例外）。
 */
@Service
public class LoanPostingService {

	private static final Logger log = LoggerFactory.getLogger(LoanPostingService.class);

	private final LoanRepository loans;
	private final CashMovementRepository movements;
	private final LoanFactsReader facts;
	private final TransactionTemplate transaction;
	private final Clock clock;
	private final boolean postOnStartup;

	public LoanPostingService(LoanRepository loans, CashMovementRepository movements, LoanFactsReader facts,
			TransactionTemplate transaction, Clock clock,
			@Value("${app.loan.post-on-startup:true}") boolean postOnStartup) {
		this.loans = loans;
		this.movements = movements;
		this.facts = facts;
		this.transaction = transaction;
		this.clock = clock;
		this.postOnStartup = postOnStartup;
	}

	/** 啟動時補記；整合測試關掉（app.loan.post-on-startup=false）。 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (postOnStartup) {
			postDue();
		}
	}

	@Scheduled(cron = "0 10 0 * * *", zone = "Asia/Taipei")
	public void daily() {
		postDue();
	}

	/** 所有使用者到期、還沒產生的期數。每筆借款各自一個交易，先鎖住再重新讀取；一筆出錯不影響其他。 */
	public int postDue() {
		LocalDate today = LocalDate.now(clock);
		int created = 0;
		for (Loan loan : loans.findAllForPosting()) {
			try {
				Integer n = transaction.execute(status -> loans.findForUpdate(loan.getId(), loan.getUserId())
					.map(fresh -> postDue(fresh, today))
					.orElse(0));
				created += n == null ? 0 : n;
			}
			catch (RuntimeException ex) {
				log.error("借款 {} 自動記帳失敗，下次執行會再試", loan.getId(), ex);
			}
		}
		if (created > 0) {
			log.info("借款自動記帳：產生 {} 筆", created);
		}
		return created;
	}

	/** 一筆借款到 today 為止到期、還沒產生的期數（新增借款、還本、修改時也會呼叫）。 */
	@Transactional
	public int postDue(Loan loan, LocalDate today) {
		return loan.getType() == LoanType.CREDIT ? postCredit(loan, today) : postPledge(loan, today);
	}

	/** 信貸：照攤還表，每期扣款日記「借款還本」與「借款利息」。 */
	private int postCredit(Loan loan, LocalDate today) {
		LoanFacts f = facts.read(loan);
		LocalDateTime now = LocalDateTime.now(clock);
		int created = 0;
		for (Installment i : InstallmentCalculator.schedule(f.installmentInput())) {
			if (i.dueDate().isAfter(today)) {
				break;
			}
			if (i.period().isBefore(loan.getPostingFrom())) {
				continue;
			}
			created += post(loan, f, CashMovementType.LOAN_PRINCIPAL, i.dueDate(), i.principal(), i.period(), now);
			created += post(loan, f, CashMovementType.LOAN_INTEREST, i.dueDate(), i.interest(), i.period(), now);
		}
		created += postCreditPayoff(loan, today, now);
		return created;
	}

	/**
	 * 信貸全部提前還清：還清當天按天數記最後一段利息（上一次扣款日到還清日，算頭不算尾、無條件進位），
	 * 期別是原本下一期的月份（使用者決定，2026-10-23）。
	 */
	private int postCreditPayoff(Loan loan, LocalDate today, LocalDateTime now) {
		LoanFacts f = facts.read(loan);
		Optional<LocalDate> payoff = f.payoffDate().filter(d -> !d.isAfter(today));
		if (payoff.isEmpty()) {
			return 0;
		}
		LocalDate day = payoff.get();
		LocalDate from = InstallmentCalculator.schedule(f.installmentInput()).stream()
			.map(Installment::dueDate)
			.filter(d -> !d.isAfter(day))
			.max(LocalDate::compareTo)
			.orElse(loan.getStartDate());
		YearMonth period = YearMonth.from(day);
		if (!period.atDay(Math.min(loan.getPayDay(), period.lengthOfMonth())).isAfter(day)) {
			period = period.plusMonths(1);
		}
		if (period.isBefore(loan.getPostingFrom())) {
			return 0;
		}
		BigDecimal interest = DailyInterestCalculator.roundUp(
				DailyInterestCalculator.interest(f.startBalance(), f.principalChanges(), f.rates(), from, day));
		return post(loan, f, CashMovementType.LOAN_INTEREST, day, interest, period, now);
	}

	/**
	 * 質押：每月繳息日記一筆「借款利息」＝ 上一筆實際繳息日（第一期為起算日）到這次繳息日的利息（算頭不算尾、無條件進位）。
	 * 還清的那一期改在還清當天記（期別仍是那一期），之後餘額 0、不再繳息。
	 */
	private int postPledge(Loan loan, LocalDate today) {
		LocalDateTime now = LocalDateTime.now(clock);
		int created = 0;
		YearMonth period = YearMonth.from(loan.getStartDate());
		// 多看一個月：還清日在當月繳息日之後時，那一期的期別是下個月，但要在還清當天記（未來的日期由下面擋住）
		YearMonth last = YearMonth.from(today).plusMonths(1);
		for (; !period.isAfter(last); period = period.plusMonths(1)) {
			LocalDate due = period.atDay(Math.min(loan.getPayDay(), period.lengthOfMonth()));
			if (!due.isAfter(loan.getStartDate()) || period.isBefore(loan.getPostingFrom())) {
				continue;
			}
			// 每一期都重新讀：上一期剛記的繳息日是這一期的計息起點
			LoanFacts f = facts.read(loan);
			LocalDate from = f.lastInterestPaidBefore(due);
			Optional<LocalDate> payoff = f.payoffDate().filter(d -> d.isAfter(from) && !d.isAfter(due));
			LocalDate to = payoff.orElse(due);
			if (to.isAfter(today)) {
				break;
			}
			BigDecimal interest = DailyInterestCalculator.roundUp(
					DailyInterestCalculator.interest(f.startBalance(), f.principalChanges(), f.rates(), from, to));
			if (interest.signum() > 0) {
				created += post(loan, f, CashMovementType.LOAN_INTEREST, to, interest, period, now);
			}
		}
		return created;
	}

	/** 這一期這種類型還沒產生過（含作廢的）而且金額 > 0 才記。 */
	private int post(Loan loan, LoanFacts f, CashMovementType type, LocalDate date, BigDecimal amount,
			YearMonth period, LocalDateTime now) {
		if (amount.signum() <= 0 || f.exists(type, period)) {
			return 0;
		}
		movements.saveAndFlush(CashMovement.ofLoan(loan, type, date, loan.getPayAccountId(), amount, period, now));
		return 1;
	}

}
