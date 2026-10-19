package com.sideproject.stockledger.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.sideproject.stockledger.entity.CashAccount;

/**
 * 只提供有帶 userId 的查詢（spec 4.10-3）。
 * 繼承 {@link Repository} 而不是 JpaRepository，這樣就不會有 findById、findAll 這類不帶 userId、會看到別人資料的方法。
 */
public interface CashAccountRepository extends Repository<CashAccount, Long> {

	/** 立刻寫進資料庫，讓唯一限制的錯誤在 Service 方法裡就出現（不要等到交易結束才丟出）。 */
	CashAccount saveAndFlush(CashAccount account);

	void flush();

	List<CashAccount> findByUserIdOrderByIdAsc(Long userId);

	Optional<CashAccount> findByIdAndUserId(Long id, Long userId);

	boolean existsByUserIdAndName(Long userId, String name);

	/** 修改時檢查重複：排除自己，否則「名稱不變只改銀行」會被當成重複。 */
	boolean existsByUserIdAndNameAndIdNot(Long userId, String name, Long id);

}
