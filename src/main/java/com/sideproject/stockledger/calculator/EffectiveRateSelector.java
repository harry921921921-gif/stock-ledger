package com.sideproject.stockledger.calculator;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 從同一種費率的所有版本中，挑出某一天適用的那一筆（spec 4.5-1）。純 Java、不依賴 Spring。
 * <ol>
 * <li>只看生效日期 ≤ 查詢日的版本（未來的版本還沒生效）。</li>
 * <li>使用者自己設定的版本優先於全域（userId 為 null）的版本，即使全域版本的日期比較新。</li>
 * <li>同一層裡取生效日期最晚的；日期相同取 id 最大（較晚新增）的，這樣法規追溯修改時只要新增一列。</li>
 * </ol>
 * 呼叫端要先把「別人的版本」濾掉：這裡只分得出「有沒有 userId」，分不出是誰的。
 */
public final class EffectiveRateSelector {

	private static final Comparator<RateCandidate> PRIORITY = Comparator
		.comparing((RateCandidate c) -> c.userId() != null)
		.thenComparing(RateCandidate::effectiveDate)
		.thenComparing(RateCandidate::id);

	private EffectiveRateSelector() {
	}

	public static Optional<RateCandidate> select(List<RateCandidate> candidates, LocalDate on) {
		Objects.requireNonNull(candidates, "candidates");
		Objects.requireNonNull(on, "on");
		return candidates.stream()
			.filter(c -> !c.effectiveDate().isAfter(on))
			.max(PRIORITY);
	}

}
