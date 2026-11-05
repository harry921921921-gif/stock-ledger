package com.sideproject.stockledger.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * 質押借款的「還款／到期處理」（設計文件 2026-11-04-pledged-stocks 6a、DR-054）：一個表單勾選一種或多種。
 * 規則都在 LoanService 檢查。Serializable：預覽後原樣收在 session，確認儲存只用收起來的這份。
 *
 * @param cash               ☐ 現金還款
 * @param date               現金還款與賣股票還款的日期（賣出、還本都記在這一天）
 * @param accountId          現金還款從哪個帳戶扣
 * @param principal          現金還款的本金
 * @param interest           現金還款同時付的利息（到期一次還本金＋利息）
 * @param sell               ☐ 賣股票還款
 * @param settlementAccountId 賣出入帳、再從這裡還本的交割戶
 * @param sales              這筆借款的每一檔擔保股票一列；股數空白或 0 是不賣
 * @param saleRepay          賣股還款的金額；空白＝賣出實際入帳合計（不超過剩餘本金）
 * @param extend             ☐ 展延
 * @param newDueDate         展延後的到期日
 */
public record SettleForm(
		Boolean cash,
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
		Long accountId,
		BigDecimal principal,
		BigDecimal interest,
		Boolean sell,
		Long settlementAccountId,
		List<SettleSale> sales,
		BigDecimal saleRepay,
		Boolean extend,
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate newDueDate) implements Serializable {

	public SettleForm {
		// 勾選框沒勾時瀏覽器不送：當作沒勾（Boolean 而不是 boolean，綁定時少了參數才不會失敗）
		cash = Boolean.TRUE.equals(cash);
		sell = Boolean.TRUE.equals(sell);
		extend = Boolean.TRUE.equals(extend);
		// 收進 session 要能序列化：一律換成 ArrayList（綁定出來的 List 實作不一定）
		sales = sales == null ? new ArrayList<>() : new ArrayList<>(sales);
	}

	/**
	 * 賣出一檔擔保股票。
	 *
	 * @param fee    手續費；空白時照賣出規則自動算
	 * @param tax    證交稅；空白時自動算
	 * @param amount 實際入帳金額；空白時用預估入帳
	 */
	public record SettleSale(Long stockId, Long shares, BigDecimal price, BigDecimal fee, BigDecimal tax,
			BigDecimal amount) implements Serializable {

		/** 有填股數（不是 0）才算要賣。 */
		public boolean selling() {
			return shares != null && shares != 0;
		}

	}

}
