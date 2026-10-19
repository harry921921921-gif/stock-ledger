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

	CashAccount save(CashAccount account);

	List<CashAccount> findByUserIdOrderByIdAsc(Long userId);

	Optional<CashAccount> findByIdAndUserId(Long id, Long userId);

	boolean existsByUserIdAndName(Long userId, String name);

	/** 修改時檢查重複：排除自己，否則「名稱不變只改銀行」會被當成重複。 */
	boolean existsByUserIdAndNameAndIdNot(Long userId, String name, Long id);

}
