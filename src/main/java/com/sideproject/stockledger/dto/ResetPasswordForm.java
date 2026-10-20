package com.sideproject.stockledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理者重設某位使用者的密碼（spec 4.10-2「忘記密碼由管理者重設」）。 */
public record ResetPasswordForm(
		@NotBlank(message = "請輸入新密碼") @Size(min = 8, max = 72, message = "密碼至少 8 個字、最多 72 個字") String newPassword) {
}
