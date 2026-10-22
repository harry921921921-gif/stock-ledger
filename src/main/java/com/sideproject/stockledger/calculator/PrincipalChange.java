package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 借款本金的變動：還本為負、撥款（質押再動撥）為正。從 date 當天起生效（算頭不算尾）。
 */
public record PrincipalChange(LocalDate date, BigDecimal delta) {
}
