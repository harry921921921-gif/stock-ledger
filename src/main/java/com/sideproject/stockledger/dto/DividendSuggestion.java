package com.sideproject.stockledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 已經除息、還沒記入帳的配息（依證交所除息資料與除息前一天的持股）。
 *
 * @param gross 應領現金股利 ＝ 股數 × 每股現金股利（捨去到元）
 * @param nhi   二代健保（單次達門檻才扣）
 * @param net   預估實際入帳 ＝ gross − nhi
 */
public record DividendSuggestion(String code, String name, LocalDate exDate, long shares, BigDecimal perShare,
		BigDecimal gross, BigDecimal nhi, BigDecimal net) {
}
