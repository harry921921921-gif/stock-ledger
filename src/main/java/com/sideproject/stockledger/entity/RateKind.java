package com.sideproject.stockledger.entity;

import java.util.Arrays;
import java.util.List;

/**
 * 費率與門檻的種類（spec 4.5、5.1、5.2）。資料庫存英文代碼，畫面顯示 label。
 * <ul>
 * <li>scope：LEGAL 是法定費率，由 Flyway seed 寫入、所有人共用、使用者不能改；PERSONAL 是每個人自己設定的。</li>
 * <li>unit：RATIO 是比率（0.001425），畫面顯示成百分比；AMOUNT 是金額，畫面顯示成「元」。</li>
 * </ul>
 * 證交稅依商品拆成四種，因為債券 ETF 停徵與當沖減半各有自己的期限。
 * 融資利率、維持率門檻要綁定某一筆借款，階段 3 再加。
 */
public enum RateKind {

	BROKERAGE_FEE_RATE("手續費率上限", Scope.LEGAL, Unit.RATIO),
	SECURITIES_TAX_STOCK("證交稅率（一般股票）", Scope.LEGAL, Unit.RATIO),
	SECURITIES_TAX_ETF("證交稅率（一般 ETF）", Scope.LEGAL, Unit.RATIO),
	SECURITIES_TAX_BOND_ETF("證交稅率（債券 ETF）", Scope.LEGAL, Unit.RATIO),
	SECURITIES_TAX_DAY_TRADE("證交稅率（現股當沖）", Scope.LEGAL, Unit.RATIO),
	NHI_SUPPLEMENT_RATE("二代健保補充保費率", Scope.LEGAL, Unit.RATIO),
	NHI_SUPPLEMENT_THRESHOLD("二代健保單次扣繳門檻", Scope.LEGAL, Unit.AMOUNT),
	BROKERAGE_DISCOUNT("手續費折扣", Scope.PERSONAL, Unit.RATIO),
	MIN_FEE_BOARD_LOT("整股最低手續費", Scope.PERSONAL, Unit.AMOUNT),
	MIN_FEE_ODD_LOT("零股最低手續費", Scope.PERSONAL, Unit.AMOUNT);

	public enum Scope {
		LEGAL, PERSONAL
	}

	public enum Unit {
		RATIO, AMOUNT
	}

	private final String label;
	private final Scope scope;
	private final Unit unit;

	RateKind(String label, Scope scope, Unit unit) {
		this.label = label;
		this.scope = scope;
		this.unit = unit;
	}

	/** 使用者可以自己新增版本的種類（設定頁下拉選單）。 */
	public static List<RateKind> personalKinds() {
		return Arrays.stream(values()).filter(k -> k.scope == Scope.PERSONAL).toList();
	}

	public boolean isLegal() {
		return scope == Scope.LEGAL;
	}

	public String label() {
		return label;
	}

	public Scope scope() {
		return scope;
	}

	public Unit unit() {
		return unit;
	}

}
