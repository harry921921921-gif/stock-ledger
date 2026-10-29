package com.sideproject.stockledger.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class LoanRepaymentMethodTest {

	private static Loan loan(LoanType type, RepaymentMethod method) {
		return Loan.create(1L, "玉山", type, method, "玉山銀行", new BigDecimal("1000"), LoanOrigin.EXISTING,
				LocalDate.of(2026, 1, 1), new BigDecimal("1000"), null, 2L, 25, null, null, null,
				LocalDateTime.of(2026, 1, 1, 9, 0));
	}

	@Test
	void savedMethodIsUsed() {
		assertThat(loan(LoanType.PLEDGE, RepaymentMethod.BULLET).repaymentMethodOrLegacy())
			.isEqualTo(RepaymentMethod.BULLET);
	}

	/** 沒有還款方式（V11 以前的舊資料）：照原本的行為對應（spec 4.8a-9）。 */
	@Test
	void missingMethodFallsBackToLegacy() {
		assertThat(loan(LoanType.CREDIT, null).repaymentMethodOrLegacy()).isEqualTo(RepaymentMethod.MONTHLY_FIXED);
		assertThat(loan(LoanType.PLEDGE, null).repaymentMethodOrLegacy()).isEqualTo(RepaymentMethod.INTEREST_ONLY);
	}

}
