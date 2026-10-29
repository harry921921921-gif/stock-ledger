package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 引導擔保頁的表單（設計文件 2026-10-25-onboarding 3）。collateral[i] 對應 {@link CollateralPage#rows()} 第 i 列：
 * 勾了才有 stockId（勾選框的值），股數欄一直都會送；沒勾（stockId 為 null）就是不擔保這檔。
 *
 * @param warnPercent 維持率提醒線（%）
 * @param callPercent 追繳線（%）
 */
public record CollateralForm(List<CollateralPick> collateral, BigDecimal warnPercent, BigDecimal callPercent) {

	public CollateralForm {
		collateral = collateral == null ? List.of() : collateral;
	}

}
