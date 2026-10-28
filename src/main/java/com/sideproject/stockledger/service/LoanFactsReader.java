package com.sideproject.stockledger.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * 同一個生效日有好幾個版本時只留 id 最大（最後新增）的：修改原始利率是在同一天新增一個版本（DR-051，同 EffectiveRateSelector）。
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
				latestPerDate(versions, RateKind.LOAN_ANNUAL_RATE).stream()
					.map(r -> new RateChange(r.getEffectiveDate(), r.getValue()))
					.toList(),
				movements.findByUserIdAndLoanIdOrderByMovementDateAscIdAsc(loan.getUserId(), loan.getId()),
				latestPerDate(versions, RateKind.LOAN_MONTHLY_PAYMENT).stream()
					.map(r -> new PaymentChange(r.getEffectiveDate(), r.getValue()))
					.toList());
	}

	/** versions 已依生效日、id 由小到大排好：同一天後面的蓋掉前面的，順序不變。 */
	private static List<RateSetting> latestPerDate(List<RateSetting> versions, RateKind kind) {
		Map<LocalDate, RateSetting> byDate = new LinkedHashMap<>();
		for (RateSetting r : versions) {
			if (r.getKind() == kind) {
				byDate.put(r.getEffectiveDate(), r);
			}
		}
		return new ArrayList<>(byDate.values());
	}

}
