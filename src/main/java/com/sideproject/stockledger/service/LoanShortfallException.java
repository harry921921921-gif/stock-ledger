package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.util.List;

import com.sideproject.stockledger.dto.AllocationCut;

/**
 * 借款改小、不夠付已經用它買的股票（spec 4.8a-10、DR-051）：先提示，使用者確定後才把差額改算自有。
 * 丟出時整個修改都回滾；畫面用 {@link #cuts()} 列出會調整哪幾筆交易。
 */
public class LoanShortfallException extends LoanValidationException {

	private final BigDecimal shortfall;
	private final transient List<AllocationCut> cuts;

	/** @param shortfallText 已經格式化的差額（「23,500」） */
	public LoanShortfallException(BigDecimal shortfall, List<AllocationCut> cuts, String shortfallText) {
		super("借款金額小於已經用它買股票的金額，差 " + shortfallText + " 元");
		this.shortfall = shortfall;
		this.cuts = List.copyOf(cuts);
	}

	public BigDecimal shortfall() {
		return shortfall;
	}

	public List<AllocationCut> cuts() {
		return cuts;
	}

}
