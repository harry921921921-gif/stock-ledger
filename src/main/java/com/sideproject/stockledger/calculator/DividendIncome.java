package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 一筆配息入帳（沒作廢的）：實際入帳金額，依當時的來源比例分到各借款。 */
public record DividendIncome(LocalDate date, Long stockId, BigDecimal amount) {
}
