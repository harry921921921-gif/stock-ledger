package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 引導擔保頁（設計文件 2026-10-25-onboarding 3、7a-4；樣稿「「X」押了哪些股票？」）要顯示的內容，Service 算好、模板只顯示。
 *
 * @param loanName    標題「「玉山質押」押了哪些股票？」
 * @param rows        目前持股（照股票代號），表單第 i 列是 collateral[i]
 * @param warnPercent 目前生效的維持率提醒線（%），沒設定是 150
 * @param callPercent 目前生效的追繳線（%），沒設定是 130
 * @param saved       「已儲存」清單：目前的擔保股票，例如「0050 元大台灣50　3,000 股」；還沒設定是空的
 * @param nextLabel   下一步按鈕的字：最後一筆質押是「下一步：完成 →」（樣稿），不是最後一筆是「下一步：下一筆質押 →」
 */
public record CollateralPage(Long loanId, String loanName, List<Row> rows, BigDecimal warnPercent,
		BigDecimal callPercent, List<String> saved, String nextLabel) {

	/**
	 * 一檔持股。
	 *
	 * @param label     「0050 元大台灣50」
	 * @param held      目前持有股數
	 * @param pledged   這筆質押目前的擔保股數；沒有擔保這檔是 null
	 * @param available 這筆質押還能押的股數：持有股數扣掉其他沒作廢的質押已經押的（不會小於 0）；表單預設用這個
	 */
	public record Row(Long stockId, String code, String label, long held, Long pledged, long available) {
	}

}
