package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * 金額的顯示格式：全站同一套規則（千分位、整數元、負號用「−」）。
 * 模板用 {@code ${@money.plain(x)}}、{@code ${@money.signed(x)}} 呼叫；這是格式化，不是計算。
 * 之後加 @RestController 時，App 拿的是原始數字，不經過這裡。
 */
@Component("money")
public class MoneyFormatter {

	/** 減號（U+2212），比連字號「-」清楚，和設計稿一致。 */
	private static final String MINUS = "−";

	/** 20,000、−1,200；null 顯示「—」。 */
	public String plain(BigDecimal value) {
		if (value == null) {
			return "—";
		}
		return (value.signum() < 0 ? MINUS : "") + digits(value.abs());
	}

	/** +200、−1,200、0：正數也加「+」，看得出是增加還是減少。 */
	public String signed(BigDecimal value) {
		if (value == null) {
			return "—";
		}
		return (value.signum() > 0 ? "+" : "") + plain(value);
	}

	private static String digits(BigDecimal value) {
		DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.TAIWAN));
		// 金額都是整數元（Service 與資料庫都有檢查）；萬一有小數也明確指定進位方式
		format.setRoundingMode(RoundingMode.HALF_UP);
		return format.format(value);
	}

}
