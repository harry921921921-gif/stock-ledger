package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 某一種費率的一個版本，交給 {@link EffectiveRateSelector} 挑選用。
 * userId 為 null 代表法定費率或全域預設；有值代表某位使用者自己設定的版本。
 */
public record RateCandidate(Long id, Long userId, LocalDate effectiveDate, BigDecimal value) {

	public RateCandidate {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(effectiveDate, "effectiveDate");
		Objects.requireNonNull(value, "value");
	}

}
