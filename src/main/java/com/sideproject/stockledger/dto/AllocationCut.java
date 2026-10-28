package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 借款改小、不夠付已經用它買的股票時（spec 4.8a-10、DR-051）：一筆交易裡這筆借款出的錢要從 from 調成 to，
 * 差額改算自有。
 *
 * @param stockLabel 「0050 元大台灣50」
 */
public record AllocationCut(Long tradeId, LocalDate date, String stockLabel, BigDecimal from, BigDecimal to) {
}
