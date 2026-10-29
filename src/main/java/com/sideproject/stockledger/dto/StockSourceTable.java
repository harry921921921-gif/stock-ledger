package com.sideproject.stockledger.dto;

import java.util.List;

/**
 * 畫面上「這筆錢從哪裡來？」表格要顯示的內容（設計文件 2026-10-25-onboarding 3.4），由 Service 算好：
 * 每列的借款、使用者填的字、換算金額；最後一列自己的錢（張數、金額）。算不出來（例如還沒填價格、張數打錯）顯示「—」。
 *
 * @param units     true 是填張數，false 是填金額
 * @param ownUnits  自己的錢的張數（例如「3 張」）；填金額時是空字串
 * @param canAdd    還能不能「＋ 加一筆借款」（有借款、列數沒到上限）
 */
public record StockSourceTable(boolean units, List<Row> rows, String ownUnits, String ownAmount, boolean canAdd) {

	public StockSourceTable {
		rows = List.copyOf(rows);
	}

	public record Row(Long loanId, String value, String amount) {
	}

}
