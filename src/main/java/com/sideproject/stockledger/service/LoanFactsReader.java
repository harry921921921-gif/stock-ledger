package com.sideproject.stockledger.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.PaymentChange;
import com.sideproject.stockledger.calculator.RateChange;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.RateKind;
import com.sideproject.stockledger.entity.RateSetting;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.RateSettingRepository;

/**
 * 讀出一筆借款的利率版本與異動。只用這筆借款自己的 user_id 查詢：呼叫端要先確認借款是登入者的
 * （LoanService），或是排程逐一處理每筆借款（LoanPostingService，DR-036）。
 */
@Component
class LoanFactsReader {

	private final RateSettingRepository rates;
	private final CashMovementRepository movements;

	LoanFactsReader(RateSettingRepository rates, CashMovementRepository movements) {
		this.rates = rates;
		this.movements = movements;
	}

	@Transactional(readOnly = true)
	LoanFacts read(Loan loan) {
		List<RateSetting> versions = rates.findByUserIdAndLoanIdOrderByEffectiveDateAscIdAsc(loan.getUserId(),
				loan.getId());
		return new LoanFacts(loan,
				versions.stream()
					.filter(r -> r.getKind() == RateKind.LOAN_ANNUAL_RATE)
					.map(r -> new RateChange(r.getEffectiveDate(), r.getValue()))
					.toList(),
				movements.findByUserIdAndLoanIdOrderByMovementDateAscIdAsc(loan.getUserId(), loan.getId()),
				versions.stream()
					.filter(r -> r.getKind() == RateKind.LOAN_MONTHLY_PAYMENT)
					.map(r -> new PaymentChange(r.getEffectiveDate(), r.getValue()))
					.toList());
	}

}
