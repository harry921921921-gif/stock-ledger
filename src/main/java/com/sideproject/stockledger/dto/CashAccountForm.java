package com.sideproject.stockledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.sideproject.stockledger.entity.CashAccount;
import com.sideproject.stockledger.entity.CashAccountType;

/** 新增／修改現金帳戶的表單。故意沒有 userId 欄位：擁有者只從登入者取得，畫面多送 userId 也不會被綁進來。 */
public record CashAccountForm(
		@NotBlank(message = "請輸入帳戶名稱") @Size(max = 50, message = "帳戶名稱最多 50 個字") String name,
		@NotNull(message = "請選擇帳戶類型") CashAccountType type,
		@NotBlank(message = "請輸入券商或銀行") @Size(max = 50, message = "券商或銀行最多 50 個字") String institution) {

	/** 先去掉前後空白（含注音輸入法的全形空白）再檢查長度與重複，所以用 strip() 而不是 trim()。 */
	public CashAccountForm {
		name = CashAccount.normalizeName(name);
		institution = CashAccount.normalizeName(institution);
	}

	public static CashAccountForm empty() {
		return new CashAccountForm("", CashAccountType.SETTLEMENT, "");
	}

	public static CashAccountForm from(CashAccount account) {
		return new CashAccountForm(account.getName(), account.getType(), account.getInstitution());
	}

}
