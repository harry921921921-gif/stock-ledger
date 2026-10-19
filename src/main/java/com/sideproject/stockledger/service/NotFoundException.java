package com.sideproject.stockledger.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 找不到資料，或資料不屬於目前登入者。兩種情況都回 404：
 * 回 403 會讓人知道「這個 id 存在，只是不是你的」。
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

}
