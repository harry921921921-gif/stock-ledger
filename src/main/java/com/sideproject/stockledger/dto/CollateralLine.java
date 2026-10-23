package com.sideproject.stockledger.dto;

import java.math.BigDecimal;

/**
 * 質押的一檔擔保股票。
 *
 * @param marketValue 擔保股數 × 最新收盤價（整數元）；沒有股價為 null
 */
public record CollateralLine(String code, String label, long shares, BigDecimal marketValue) {
}
