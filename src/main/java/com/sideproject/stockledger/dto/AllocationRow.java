package com.sideproject.stockledger.dto;

import java.math.BigDecimal;

/**
 * 資產配置排行的一列：一檔股票或一個現金帳戶。
 *
 * @param sharePercent 占總資產 %（到 0.01）；總資產 ≤ 0 時為 null
 */
public record AllocationRow(String name, BigDecimal amount, BigDecimal sharePercent) {
}
