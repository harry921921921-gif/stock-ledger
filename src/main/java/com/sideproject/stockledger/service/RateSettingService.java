package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.EffectiveRateSelector;
import com.sideproject.stockledger.calculator.RateCandidate;
import com.sideproject.stockledger.dto.RateRow;
import com.sideproject.stockledger.dto.RateSettingForm;
import com.sideproject.stockledger.entity.RateKind;
import com.sideproject.stockledger.entity.RateSetting;
import com.sideproject.stockledger.repository.RateSettingRepository;
import com.sideproject.stockledger.security.CurrentUser;

/**
 * 費率設定（spec 4.5）。費率一律從這裡依生效日期取得，不寫死在程式裡。
 * <ul>
 * <li>法定費率（LEGAL）只看全域列，就算資料庫裡出現使用者的法定費率列也不採用。</li>
 * <li>個人費率（PERSONAL）看全域預設 + 目前登入者自己的列；看不到別人的。</li>
 * </ul>
 */
@Service
public class RateSettingService {

	static final String UNSET = "未設定";

	private static final String NO_SOURCE = "—";

	/** 生效日期的合理範圍：系統不會有 2000 年以前的交易；資料庫 DATE 最多到 9999 年。 */
	private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
	private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

	/** 資料庫 value 欄位是 DECIMAL(19,6)。 */
	private static final int STORED_SCALE = 6;

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	/** DECIMAL(19,6) 的整數部分最多 13 位。 */
	private static final BigDecimal MAX_INPUT = new BigDecimal("1e13");

	/** 百分比輸入最多 4 位小數，÷ 100 後剛好是資料庫的 6 位，不會有進位。 */
	private static final int MAX_INPUT_DECIMALS = 4;

	private final RateSettingRepository rates;
	private final CurrentUser currentUser;
	private final Clock clock;

	public RateSettingService(RateSettingRepository rates, CurrentUser currentUser, Clock clock) {
		this.rates = rates;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	/** 某一天適用的費率（比率類回傳比率，例如 0.001425；金額類回傳元）。沒有任何版本時回傳 empty（spec 4.5-4「未設定」）。 */
	@Transactional(readOnly = true)
	public Optional<BigDecimal> findEffective(RateKind kind, LocalDate on) {
		List<RateCandidate> candidates = applicable(kind, rates.findVisible(kind, currentUser.id())).stream()
			.map(RateSetting::toCandidate)
			.toList();
		return EffectiveRateSelector.select(candidates, on).map(RateCandidate::value);
	}

	/** 設定頁：每個種類一列，顯示今天適用的值與所有歷史版本。 */
	@Transactional(readOnly = true)
	public List<RateRow> currentTable() {
		LocalDate today = LocalDate.now(clock);
		Map<RateKind, List<RateSetting>> byKind = rates.findAllVisible(currentUser.id()).stream()
			.collect(Collectors.groupingBy(RateSetting::getKind));
		return Arrays.stream(RateKind.values())
			.map(kind -> rowFor(kind, applicable(kind, byKind.getOrDefault(kind, List.of())), today))
			.toList();
	}

	/** 新增表單的預設值：手續費折扣、生效日期今天。 */
	public RateSettingForm emptyForm() {
		return new RateSettingForm(RateKind.BROKERAGE_DISCOUNT, null, LocalDate.now(clock), null);
	}

	/** 新增一個個人費率版本。只新增、不修改舊版本（spec 4.5-2）。 */
	@Transactional
	public void addPersonalVersion(RateSettingForm form) {
		// 不只依賴表單的 @Valid：之後給 App 用的 @RestController 也會呼叫這裡
		if (form.kind() == null || form.value() == null || form.effectiveDate() == null) {
			throw new RateValidationException("請填寫項目、數值與生效日期");
		}
		RateKind kind = form.kind();
		if (kind.scope() != RateKind.Scope.PERSONAL) {
			throw new RateValidationException("法定費率不能修改");
		}
		if (form.effectiveDate().isBefore(MIN_DATE) || form.effectiveDate().isAfter(MAX_DATE)) {
			throw new RateValidationException("生效日期要在 2000-01-01 到 9999-12-31 之間");
		}
		rates.save(RateSetting.personal(currentUser.id(), kind, toStoredValue(kind, form.value()),
				form.effectiveDate(), form.note(), LocalDateTime.now(clock)));
	}

	/** 畫面輸入 → 資料庫的值。比率類輸入的是百分比，要 ÷ 100。 */
	private static BigDecimal toStoredValue(RateKind kind, BigDecimal input) {
		// 先用 compareTo 擋掉過大的數字：像 1e2147483647 這種輸入會讓 @Digits 計算溢位而放行，setScale 時才出錯變 500
		if (input.abs().compareTo(MAX_INPUT) >= 0) {
			throw new RateValidationException("數字太大");
		}
		if (input.stripTrailingZeros().scale() > MAX_INPUT_DECIMALS) {
			throw new RateValidationException("小數最多 4 位");
		}
		if (kind == RateKind.BROKERAGE_DISCOUNT
				&& (input.signum() <= 0 || input.compareTo(HUNDRED) > 0)) {
			throw new RateValidationException("手續費折扣要大於 0、最多 100（例如 2.8 折填 28）");
		}
		if (kind.unit() == RateKind.Unit.AMOUNT) {
			if (input.signum() < 0 || input.stripTrailingZeros().scale() > 0) {
				throw new RateValidationException(kind.label() + "要是 0 或正整數（元）");
			}
			return input.setScale(STORED_SCALE, RoundingMode.HALF_UP);
		}
		return input.divide(HUNDRED, STORED_SCALE, RoundingMode.HALF_UP);
	}

	/** 法定費率只採用全域列；個人費率採用全域預設與自己的列（查詢本身已排除別人的列）。 */
	private static List<RateSetting> applicable(RateKind kind, List<RateSetting> visible) {
		if (kind.scope() == RateKind.Scope.LEGAL) {
			return visible.stream().filter(r -> r.getUserId() == null).toList();
		}
		return visible;
	}

	private static RateRow rowFor(RateKind kind, List<RateSetting> versions, LocalDate today) {
		Optional<RateCandidate> current = EffectiveRateSelector.select(
				versions.stream().map(RateSetting::toCandidate).toList(), today);
		Long currentId = current.map(RateCandidate::id).orElse(null);

		List<RateRow> history = versions.stream()
			.sorted(Comparator.comparing(RateSetting::getEffectiveDate)
				.thenComparing(RateSetting::getId)
				.reversed())
			.map(r -> new RateRow(kind, format(kind, r.getValue()), r.getEffectiveDate(), sourceLabel(kind, r.getUserId()),
					statusLabel(r, currentId, today), r.getNote(), List.of()))
			.toList();

		if (current.isEmpty()) {
			return new RateRow(kind, UNSET, null, NO_SOURCE, "", null, history);
		}
		RateCandidate c = current.get();
		return new RateRow(kind, format(kind, c.value()), c.effectiveDate(), sourceLabel(kind, c.userId()), "", null,
				history);
	}

	private static String sourceLabel(RateKind kind, Long userId) {
		if (userId != null) {
			return "個人";
		}
		return kind.scope() == RateKind.Scope.LEGAL ? "法定" : "預設";
	}

	private static String statusLabel(RateSetting rate, Long currentId, LocalDate today) {
		if (rate.getId().equals(currentId)) {
			return "目前適用";
		}
		return rate.getEffectiveDate().isAfter(today) ? "尚未生效" : "";
	}

	/** RATIO：0.001425 → 「0.1425%」；AMOUNT：20000 → 「20,000 元」。只是換小數點位置，不會有進位誤差。 */
	private static String format(RateKind kind, BigDecimal value) {
		DecimalFormat format = new DecimalFormat("#,##0.####", DecimalFormatSymbols.getInstance(Locale.TAIWAN));
		format.setRoundingMode(RoundingMode.HALF_UP);
		if (kind == RateKind.BROKERAGE_DISCOUNT) {
			// 台灣習慣說「幾折」：0.28 → 28%（2.8 折），避免誤讀成「打 28% 折」
			return format.format(value.movePointRight(2)) + "%（" + format.format(value.movePointRight(1)) + " 折）";
		}
		if (kind.unit() == RateKind.Unit.RATIO) {
			return format.format(value.movePointRight(2)) + "%";
		}
		return format.format(value) + " 元";
	}

}
