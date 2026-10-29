package com.sideproject.stockledger.controller;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import com.sideproject.stockledger.security.CurrentUser;

/**
 * 引導裡填到一半、還沒按儲存的表單（設計文件 2026-10-25-onboarding 4、DR-050）。
 * 按「上一步」「下一步」「先離開」時把表單目前的內容原樣收起來，回到那一頁時填回去，儲存成功後清掉。
 * 草稿不是事件，所以放 HttpSession 不進資料庫（CLAUDE.md「只儲存原始事件」）；代價是登出或 session 逾時後就沒了。
 * 比照 {@link FormTokens}：每個 session 一份，用 session mutex 避免兩個分頁同時改。
 * 草稿依登入者分開存（session 屬性名稱帶 user id）：登入時 Spring Security 換 session id 但保留屬性，
 * 同一個瀏覽器沒登出就換人登入時，下一個人不會看到上一個人打的資料。
 *
 * <p>formKey：{@code accounts}、{@code loans}、{@code stocks}、{@code collateral-{loanId}}；
 * 修改已儲存的資料時，打到一半的新資料另外收在 {@code <key>:new}，改完或取消後放回表單。
 */
@Component
public class OnboardingDrafts {

	private static final String ATTRIBUTE = OnboardingDrafts.class.getName();

	/** 不是使用者打的字：CSRF token、一次性表單代號、目的地。 */
	private static final Set<String> IGNORED = Set.of("_csrf", "formToken", "to");

	/** 草稿只收表單欄位：名稱太長或欄位太多的多餘參數不收，草稿不會無限長大。 */
	static final int MAX_NAME_LENGTH = 100;

	static final int MAX_PARAMS = 100;

	private final CurrentUser currentUser;

	public OnboardingDrafts(CurrentUser currentUser) {
		this.currentUser = currentUser;
	}

	public void save(HttpSession session, String formKey, Map<String, String[]> params) {
		Map<String, String[]> copy = new LinkedHashMap<>();
		params.forEach((name, values) -> {
			if (!IGNORED.contains(name) && name.length() <= MAX_NAME_LENGTH && copy.size() < MAX_PARAMS) {
				copy.put(name, values == null ? new String[0] : values.clone());
			}
		});
		synchronized (WebUtils.getSessionMutex(session)) {
			HashMap<String, Map<String, String[]>> all = all(session);
			all.put(formKey, copy);
			session.setAttribute(attribute(), all);
		}
	}

	/** 回傳複本：呼叫端改了也不會動到草稿。 */
	public Optional<Map<String, String[]>> load(HttpSession session, String formKey) {
		synchronized (WebUtils.getSessionMutex(session)) {
			Map<String, String[]> draft = all(session).get(formKey);
			if (draft == null) {
				return Optional.empty();
			}
			Map<String, String[]> copy = new LinkedHashMap<>();
			draft.forEach((name, values) -> copy.put(name, values.clone()));
			return Optional.of(copy);
		}
	}

	public void clear(HttpSession session, String formKey) {
		synchronized (WebUtils.getSessionMutex(session)) {
			HashMap<String, Map<String, String[]>> all = all(session);
			all.remove(formKey);
			session.setAttribute(attribute(), all);
		}
	}

	// ---------- 確認區塊的內容（設計文件 3.2「按『確認儲存』才真的存」） ----------

	private static final String CONFIRM_PREFIX = "confirm:";

	/** 收起來的內容是給哪一個存檔用的（{@link #target}）；和表單欄位放在一起，拿出來時拿掉。 */
	private static final String TARGET = "\u0000target";

	/**
	 * 存檔目標：哪一步（formKey）、哪一筆（新增是 null）、做什麼（一般存檔是 null，例如借款的 {@code shortfall}）。
	 * 收起來的內容只能用在同一個目標：新增帳戶的代號拿去存「修改帳戶 5」、或拿去借款頁，都當作過期。
	 */
	public static String target(String formKey, Long id, String action) {
		return formKey + "/" + (id == null ? "new" : id) + (action == null ? "" : "/" + action);
	}

	/**
	 * 預覽成功時，把確認區塊列出的那份表單內容依一次性代號收起來；「確認儲存」只存這一份，
	 * 不看送出時表單上的值，所以存進去的一定是使用者看過、確認過的內容。target 是這份內容要給哪一個存檔用（{@link #target}）。
	 * 每個使用者最多留 {@link FormTokens#MAX_TOKENS} 份（和代號一樣多），超過就丟最舊的。
	 */
	public void stashForConfirm(HttpSession session, String token, String target, Map<String, String[]> params) {
		synchronized (WebUtils.getSessionMutex(session)) {
			save(session, CONFIRM_PREFIX + token, params);
			HashMap<String, Map<String, String[]>> all = all(session);
			all.get(CONFIRM_PREFIX + token).put(TARGET, new String[] { target });
			List<String> confirms = all.keySet().stream().filter(k -> k.startsWith(CONFIRM_PREFIX)).toList();
			for (int i = 0; i < confirms.size() - FormTokens.MAX_TOKENS; i++) {
				all.remove(confirms.get(i));
			}
			session.setAttribute(attribute(), all);
		}
	}

	/**
	 * 「確認儲存」：拿出（並刪掉）確認區塊收起來的內容。token 是 null、過期、已經用過，
	 * 或收起來時的目標和這次的存檔目標不一樣（代號被拿到別的存檔網址）時為 empty。
	 */
	public Optional<Map<String, String[]>> takeConfirmed(HttpSession session, String token, String target) {
		return take(session, token, target::equals);
	}

	/**
	 * 確認區塊上的其他按鈕（上一步、下一步、修改、回去修改）：拿出這一步（formKey）收起來的內容當草稿，
	 * 不管是哪一筆、哪一種存檔。別一步的代號為 empty。
	 */
	public Optional<Map<String, String[]>> takeConfirmedForStep(HttpSession session, String token, String formKey) {
		return take(session, token, t -> t.startsWith(formKey + "/"));
	}

	private Optional<Map<String, String[]>> take(HttpSession session, String token,
			Predicate<String> targetMatches) {
		if (token == null) {
			return Optional.empty();
		}
		synchronized (WebUtils.getSessionMutex(session)) {
			Optional<Map<String, String[]>> stashed = load(session, CONFIRM_PREFIX + token);
			clear(session, CONFIRM_PREFIX + token);
			if (stashed.isEmpty()) {
				return stashed;
			}
			String[] target = stashed.get().remove(TARGET);
			return target != null && target.length == 1 && targetMatches.test(target[0]) ? stashed : Optional.empty();
		}
	}

	// ---------- 修改已儲存的資料（設計文件 3.2「修改前打到一半的新內容先收起來，改完或取消後自動放回表單」） ----------

	/**
	 * 修改模式的表單多一個隱藏欄位 editing＝正在改的那筆 id。它跟著草稿一起存，
	 * 所以修改中按「上一步」「下一步」再回來，還是在修改同一筆、改到一半的字也在。
	 */
	public static final String EDITING = "editing";

	private static final String NEW_SUFFIX = ":new";

	/**
	 * 按了某一筆的「修改」：params 是按下當時表單的內容。原本在打新資料（沒有 editing）就收到 {@code <key>:new}；
	 * 已經在修改另一筆就不動收起來的新資料（改到一半的那筆放棄，資料庫裡的不受影響）。之後表單改放那一筆的內容。
	 */
	public void beginEdit(HttpSession session, String formKey, Map<String, String[]> params) {
		synchronized (WebUtils.getSessionMutex(session)) {
			if (!params.containsKey(EDITING)) {
				save(session, formKey + NEW_SUFFIX, params);
			}
			clear(session, formKey);
		}
	}

	/** 修改存好或按「取消修改」：把收起來的新資料放回表單（沒有就清空）。 */
	public void endEdit(HttpSession session, String formKey) {
		synchronized (WebUtils.getSessionMutex(session)) {
			Optional<Map<String, String[]>> stashed = load(session, formKey + NEW_SUFFIX);
			clear(session, formKey + NEW_SUFFIX);
			if (stashed.isPresent()) {
				save(session, formKey, stashed.get());
			}
			else {
				clear(session, formKey);
			}
		}
	}

	/** 草稿是不是在修改某一筆；值不是數字（被改過的參數）當作沒有。 */
	public static Optional<Long> editingId(Map<String, String[]> params) {
		String[] values = params.get(EDITING);
		if (values == null || values.length == 0) {
			return Optional.empty();
		}
		try {
			return Optional.of(Long.valueOf(values[0]));
		}
		catch (NumberFormatException ex) {
			return Optional.empty();
		}
	}

	/** 每個使用者一個屬性；user id 只從 Spring Security 取得。 */
	private String attribute() {
		return ATTRIBUTE + ":" + currentUser.id();
	}

	@SuppressWarnings("unchecked")
	private HashMap<String, Map<String, String[]>> all(HttpSession session) {
		Object value = session.getAttribute(attribute());
		return value instanceof HashMap<?, ?> map ? (HashMap<String, Map<String, String[]>>) map : new LinkedHashMap<>();
	}

}
