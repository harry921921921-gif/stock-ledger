package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Loan;

/**
 * 修改借款的結果（spec 4.8a-10、DR-051）。
 *
 * @param kept              使用者手改過、所以沒有自動重算，而且和新合約算出來不一樣（金額、日期或帳戶）的期數（存檔後列出請使用者確認）
 * @param payAccountChanged 這次修改換了扣款帳戶（手改過的期數留在原本的帳戶，畫面據此提醒）
 */
public record LoanEditResult(Loan loan, List<KeptPeriod> kept, boolean payAccountChanged) {

	public LoanEditResult {
		kept = List.copyOf(kept);
	}

	/** 一筆保留下來的每期紀錄：期別、類型、目前的金額。 */
	public record KeptPeriod(Long movementId, YearMonth period, CashMovementType type, BigDecimal amount) {

		public static KeptPeriod of(CashMovement m) {
			return new KeptPeriod(m.getId(), m.getPeriod(), m.getType(), m.getAmount());
		}

	}

}
