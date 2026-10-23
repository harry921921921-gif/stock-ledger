package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.sideproject.stockledger.entity.TradeType;

/**
 * 新增或修改股票交易（spec 3a、3b、設計文件 2026-10-23-stocks 4）。規則都在 TradeService 檢查。
 *
 * @param price       買進、賣出：成交價；期初持股：平均成本（含手續費）
 * @param fee         手續費；空白時系統依費率自動算
 * @param tax         證交稅（賣出）；空白時自動算
 * @param amount      賣出：實際入帳金額；空白時用預估入帳
 * @param allocations 期初持股與買進：各借款出的錢；其餘是自有
 */
public record TradeForm(
		TradeType type,
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
		String stockCode,
		Long accountId,
		Long shares,
		BigDecimal price,
		BigDecimal fee,
		BigDecimal tax,
		BigDecimal amount,
		List<AllocationLine> allocations) {

	public TradeForm {
		stockCode = stockCode == null ? null : stockCode.strip().toUpperCase();
		allocations = allocations == null ? List.of() : allocations;
	}

}
