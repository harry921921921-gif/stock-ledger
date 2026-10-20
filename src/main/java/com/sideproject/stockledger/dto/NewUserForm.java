package com.sideproject.stockledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.sideproject.stockledger.entity.Role;

/**
 * 管理者建立使用者的表單（spec 4.10-2）。
 * 密碼至少 8 個字；上限 72 是 BCrypt 的限制，但 BCrypt 算的是 bytes（中文一個字 3 bytes），所以 Service 會再用 bytes 檢查一次。
 * 密碼不去前後空白：空白也是密碼的一部分。
 */
public record NewUserForm(
		@NotBlank(message = "請輸入帳號") @Size(max = 50, message = "帳號最多 50 個字") String username,
		@NotBlank(message = "請輸入顯示名稱") @Size(max = 50, message = "顯示名稱最多 50 個字") String displayName,
		@NotBlank(message = "請輸入密碼") @Size(min = 8, max = 72, message = "密碼至少 8 個字、最多 72 個字") String password,
		@NotNull(message = "請選擇角色") Role role) {

	public static NewUserForm empty() {
		return new NewUserForm("", "", "", Role.USER);
	}

}
