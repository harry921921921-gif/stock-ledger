package com.sideproject.stockledger.dto;

import java.util.List;
import java.util.Objects;

/**
 * 引導股票這一步「這筆錢從哪裡來？」的表格（設計文件 2026-10-25-onboarding 3.4），只給引導用；股票頁仍是 {@link TradeForm#allocations}。
 * 每列是一筆借款和它出的張數（{@link Mode#UNITS}）或金額（{@link Mode#AMOUNT}，元）；沒有列就是全部自己的錢。
 * value 用字串收：打錯的字（例如「1.5」「abc」）照樣留在畫面上，由 Service 檢查並換算成 {@link AllocationLine}。
 * 表單參數：{@code sourceMode}、{@code sources[i].loanId}、{@code sources[i].value}。
 */
public record StockSourceInput(Mode sourceMode, List<Row> sources) {

	public enum Mode {
		/** 填張數，系統依張數 ÷ 總張數 × 總成本換算金額。 */
		UNITS,
		/** 填金額（元），和股票頁一樣。 */
		AMOUNT
	}

	public record Row(Long loanId, String value) {

		public Row {
			value = value == null ? "" : value.strip();
		}

		public boolean blank() {
			return loanId == null && value.isEmpty();
		}

	}

	/** 引導表格最多幾列（草稿參數有上限，見 OnboardingDrafts.MAX_PARAMS）。 */
	public static final int MAX_ROWS = 10;

	public StockSourceInput {
		sourceMode = sourceMode == null ? Mode.UNITS : sourceMode;
		// 參數的索引跳號（例如只送 sources[2]）時中間是 null：拿掉
		sources = sources == null ? List.of() : sources.stream().filter(Objects::nonNull).limit(MAX_ROWS).toList();
	}

	public static StockSourceInput none() {
		return new StockSourceInput(Mode.UNITS, List.of());
	}

	public boolean units() {
		return sourceMode == Mode.UNITS;
	}

	/** 換列或切換模式的結果：新的表格，和要告訴使用者的話（沒有是 null）。 */
	public record Change(StockSourceInput sources, String notice) {
	}

}
