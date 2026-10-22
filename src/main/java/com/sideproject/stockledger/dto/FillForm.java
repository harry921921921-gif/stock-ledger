package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.springframework.format.annotation.DateTimeFormat;

/** 「每月自己填」的項目填某一期的金額（設計文件 4.3）。日期由系統決定：那一期的入帳日，還沒到就用今天。 */
public record FillForm(@DateTimeFormat(pattern = "yyyy-MM") YearMonth period, BigDecimal amount) {
}
