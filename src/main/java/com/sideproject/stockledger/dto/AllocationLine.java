package com.sideproject.stockledger.dto;

import java.math.BigDecimal;

/** 資金來源的一列：某筆借款出了多少錢（其餘是自有）。金額空白或 0 的列會略過。 */
public record AllocationLine(Long loanId, BigDecimal amount) {
}
