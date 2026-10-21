package com.sideproject.stockledger.controller;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

/**
 * 一次性表單代號：防止「記一筆現金異動」連點兩次存成兩筆。
 * 現金異動沒有「名稱不可重複」這種自然的限制（同一天存兩筆一樣的錢是正常的），所以靠代號辨認是不是同一次送出。
 * 打開表單時發一個代號放在 session；送出時用掉；同一個代號第二次送出就不再新增。不需要資料表。
 * 注意：鎖是 session 物件本身，只在同一台伺服器內有效；如果之後改用 Spring Session（Redis／資料庫）要重新設計。
 */
@Component
public class FormTokens {

	private static final String ATTRIBUTE = FormTokens.class.getName();

	/** 每個 session 最多保留幾個還沒用的代號（開了很多分頁也夠用），超過就丟掉最舊的。 */
	static final int MAX_TOKENS = 20;

	public String issue(HttpSession session) {
		String token = UUID.randomUUID().toString();
		synchronized (WebUtils.getSessionMutex(session)) {
			Set<String> tokens = tokens(session);
			tokens.add(token);
			while (tokens.size() > MAX_TOKENS) {
				tokens.remove(tokens.iterator().next());
			}
			session.setAttribute(ATTRIBUTE, tokens);
		}
		return token;
	}

	/** 用掉代號；代號不存在（已經用過、過期或沒帶）時回傳 false。同一時間只有一個請求能用掉同一個代號。 */
	public boolean consume(HttpSession session, String token) {
		if (token == null) {
			return false;
		}
		synchronized (WebUtils.getSessionMutex(session)) {
			Set<String> tokens = tokens(session);
			boolean removed = tokens.remove(token);
			session.setAttribute(ATTRIBUTE, tokens);
			return removed;
		}
	}

	/** 送出失敗（例如金額填錯）時把代號還回去，改正後同一個表單還能再送。 */
	public void restore(HttpSession session, String token) {
		synchronized (WebUtils.getSessionMutex(session)) {
			Set<String> tokens = tokens(session);
			tokens.add(token);
			session.setAttribute(ATTRIBUTE, tokens);
		}
	}

	@SuppressWarnings("unchecked")
	private static Set<String> tokens(HttpSession session) {
		Object value = session.getAttribute(ATTRIBUTE);
		return value instanceof Set<?> set ? (Set<String>) set : new LinkedHashSet<>();
	}

}
