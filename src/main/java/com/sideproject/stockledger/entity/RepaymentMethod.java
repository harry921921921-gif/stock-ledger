package com.sideproject.stockledger.entity;

/** 借款的還款方式（spec 4.8a、DR-048）。和「是不是股票質押」（{@link LoanType}）分開。 */
public enum RepaymentMethod {

	/** 每月扣一筆，裡面有本金和利息（一般信貸、房貸）；扣款金額照銀行。 */
	MONTHLY_FIXED("每月固定還款"),

	/** 平常不用繳，到期一次還本金＋利息（例如股票質押）；每月只算應計利息。 */
	BULLET("到期一次還本金＋利息"),

	/** 每月只繳利息，本金到期才還。 */
	INTEREST_ONLY("每月繳息、到期還本");

	private final String label;

	RepaymentMethod(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** V11 以前的借款照原本的行為對應：信貸每月固定還款，質押每月繳息（spec 4.8a-9）。 */
	public static RepaymentMethod legacyOf(LoanType type) {
		return type == LoanType.CREDIT ? MONTHLY_FIXED : INTEREST_ONLY;
	}

}
