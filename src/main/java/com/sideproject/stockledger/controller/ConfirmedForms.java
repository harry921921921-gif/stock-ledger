package com.sideproject.stockledger.controller;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Optional;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import com.sideproject.stockledger.security.CurrentUser;

/**
 * 預覽確認過、還沒存的表單（資金來源頁的「還款／到期處理」，DR-054）：和引導的 {@link OnboardingDrafts#stashForConfirm}
 * 同一個做法——預覽成功時依一次性代號把「確認畫面上列出的那份」收起來，「確認儲存」只存這一份，不看送出時表單上的值。
 * 引導收的是原始參數（要畫回草稿）；這裡收的是 Service 算好的表單物件（手續費、證交稅、實際入帳都已經是確認畫面上的數字）。
 * 依登入者分開存（屬性名稱帶 user id），每人最多 {@link FormTokens#MAX_TOKENS} 份，超過就丟最舊的。不進資料庫（不是事件）。
 */
@Component
public class ConfirmedForms {

	private static final String ATTRIBUTE = ConfirmedForms.class.getName();

	private final CurrentUser currentUser;

	public ConfirmedForms(CurrentUser currentUser) {
		this.currentUser = currentUser;
	}

	/** 收起來的一份：存檔目標（例如 settle/12，代號拿到別筆借款就當過期）和表單。 */
	private record Entry(String target, Serializable form) implements Serializable {
	}

	public void stash(HttpSession session, String token, String target, Serializable form) {
		synchronized (WebUtils.getSessionMutex(session)) {
			LinkedHashMap<String, Entry> all = all(session);
			all.put(token, new Entry(target, form));
			while (all.size() > FormTokens.MAX_TOKENS) {
				all.remove(all.keySet().iterator().next());
			}
			session.setAttribute(attribute(), all);
		}
	}

	/** 拿出（並刪掉）收起來的那份；代號是 null、過期、用過、目標不同或型別不對時為 empty。 */
	public <T> Optional<T> take(HttpSession session, String token, String target, Class<T> type) {
		if (token == null) {
			return Optional.empty();
		}
		synchronized (WebUtils.getSessionMutex(session)) {
			LinkedHashMap<String, Entry> all = all(session);
			Entry entry = all.remove(token);
			session.setAttribute(attribute(), all);
			if (entry == null || !entry.target().equals(target) || !type.isInstance(entry.form())) {
				return Optional.empty();
			}
			return Optional.of(type.cast(entry.form()));
		}
	}

	private String attribute() {
		return ATTRIBUTE + ":" + currentUser.id();
	}

	@SuppressWarnings("unchecked")
	private LinkedHashMap<String, Entry> all(HttpSession session) {
		Object value = session.getAttribute(attribute());
		return value instanceof LinkedHashMap<?, ?> map ? (LinkedHashMap<String, Entry>) map : new LinkedHashMap<>();
	}

}
