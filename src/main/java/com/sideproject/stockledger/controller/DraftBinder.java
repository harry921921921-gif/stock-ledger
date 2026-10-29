package com.sideproject.stockledger.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ResolvableType;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DataBinder;
import org.springframework.validation.Validator;

/**
 * 把 session 裡收起來的參數（{@link OnboardingDrafts} 的草稿、確認區塊的內容）綁回表單 record
 * （設計文件 2026-10-25-onboarding 3.2、4）。
 * 和 Spring MVC 處理 {@code @ModelAttribute} 用同一套 DataBinder、mvcConversionService 與 mvcValidator，
 * 所以日期格式（@DateTimeFormat）、空字串變 null、必填訊息都和直接送出表單時一樣；模板也一樣用 th:object／th:field 顯示。
 * 格式錯的欄位（例如金額打了「abc」）會記在 BindingResult 裡，th:field 顯示原本打的字，不會不見。
 */
@Component
public class DraftBinder {

	private final FormattingConversionService conversion;
	private final Validator validator;

	public DraftBinder(@Qualifier("mvcConversionService") FormattingConversionService conversion,
			@Qualifier("mvcValidator") Validator validator) {
		this.conversion = conversion;
		this.validator = validator;
	}

	/** 草稿：只綁定不驗證（打到一半的內容，按「儲存」時才檢查），連同 BindingResult 以 name 放進 model。 */
	public void bindInto(Model model, String name, Class<?> type, Map<String, String[]> params) {
		put(model, bind(name, type, params, false));
	}

	/**
	 * 綁定並視需要驗證（等同 {@code @Valid @ModelAttribute(name)}）；表單本身是 {@code result.getTarget()}。
	 * 有欄位格式錯（例如金額打「abc」）時 record 建不出來：改用「拿掉格式錯的欄位」再建一次當表單，格式錯誤照樣留在結果裡
	 * （th:field 顯示原本打的字），所以模板裡 {@code *{type}} 這類不是 th:field 的運算式也不會因為表單是 null 而出錯。
	 * 這時不跑 Bean Validation（和 MVC 一樣：格式錯就先只報格式錯）。連拿掉後都建不出來才是 null。
	 */
	public BindingResult bind(String name, Class<?> type, Map<String, String[]> params, boolean validate) {
		BindingResult result = construct(name, type, params, validate);
		if (result.getTarget() != null || !result.hasFieldErrors()) {
			return result;
		}
		Map<String, String[]> rest = new LinkedHashMap<>(params);
		result.getFieldErrors().forEach(e -> rest.remove(e.getField()));
		Object partial = construct(name, type, rest, false).getTarget();
		if (partial == null) {
			return result;
		}
		DataBinder binder = new DataBinder(partial, name);
		binder.setConversionService(conversion);
		BindingResult merged = binder.getBindingResult();
		merged.addAllErrors(result);
		return merged;
	}

	private BindingResult construct(String name, Class<?> type, Map<String, String[]> params, boolean validate) {
		DataBinder binder = new DataBinder(null, name);
		binder.setTargetType(ResolvableType.forClass(type));
		binder.setConversionService(conversion);
		binder.construct(new DataBinder.ValueResolver() {

			@Override
			public Object resolveValue(String paramName, Class<?> paramType) {
				String[] values = params.get(paramName);
				if (values == null) {
					return null;
				}
				return values.length == 1 ? values[0] : values;
			}

			@Override
			public Set<String> getNames() {
				return params.keySet();
			}

		});
		if (validate && binder.getTarget() != null) {
			binder.setValidator(validator);
			binder.validate();
		}
		return binder.getBindingResult();
	}

	/** 把綁定結果放進 model，模板用 th:object="${name}" 顯示（和 MVC 綁定的 model 一樣）。 */
	public static void put(Model model, BindingResult result) {
		model.addAttribute(result.getObjectName(), result.getTarget());
		model.addAttribute(BindingResult.MODEL_KEY_PREFIX + result.getObjectName(), result);
	}

}
