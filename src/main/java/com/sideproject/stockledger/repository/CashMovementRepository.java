package com.sideproject.stockledger.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;

/**
 * 只提供帶 userId 的查詢（spec 4.10-3）。繼承 {@link Repository}：沒有 findById、findAll，也沒有 delete（不硬刪除）。
 */
public interface CashMovementRepository extends Repository<CashMovement, Long> {

	/** 立刻寫進資料庫，讓唯一限制的錯誤在 Service 方法裡就出現。 */
	CashMovement saveAndFlush(CashMovement movement);

	void flush();

	/** 目前登入者所有沒作廢的事件（計算餘額用）。 */
	List<CashMovement> findByUserIdAndVoidedFalse(Long userId);

	/** 目前登入者所有事件，新的在前（列表用，含作廢的）。 */
	List<CashMovement> findByUserIdOrderByMovementDateDescIdDesc(Long userId);

	/**
	 * 備註符合 LIKE 樣式的事件（含作廢的），新的在前。pattern 由呼叫端用 ! 跳脫 %、_、!；
	 * 不用 Spring Data 的 Containing，因為它用反斜線跳脫，搜尋「\」會找不到。
	 * 不分大小寫靠資料表的 collation（utf8mb4_0900_ai_ci）。
	 */
	@Query("select m from CashMovement m where m.userId = :userId and m.note like :pattern escape '!' "
			+ "order by m.movementDate desc, m.id desc")
	List<CashMovement> searchNote(@Param("userId") Long userId, @Param("pattern") String pattern);

	Optional<CashMovement> findByIdAndUserId(Long id, Long userId);

	/**
	 * 修改或作廢前讀取並鎖住這一列（SELECT … FOR UPDATE）：兩個請求同時改同一筆時，第二個會等第一個完成，
	 * 再讀到最新的值，修改紀錄的「舊值」才接得起來，也不會作廢兩次。
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from CashMovement m where m.id = :id and m.userId = :userId")
	Optional<CashMovement> findForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	Optional<CashMovement> findByUserIdAndAccountIdAndTypeAndVoidedFalse(Long userId, Long accountId,
			CashMovementType type);

	boolean existsByUserIdAndAccountIdAndTypeAndMovementDateAndVoidedFalse(Long userId, Long accountId,
			CashMovementType type, LocalDate movementDate);

	/**
	 * 某個帳戶（轉出或轉入都算）最早一筆沒作廢的異動日期，排除 excludeId（修改中的那筆；新增時傳 -1）。
	 * 用來檢查「初始現金餘額不能晚於其他異動」。
	 */
	@Query("select min(m.movementDate) from CashMovement m where m.userId = :userId and m.voided = false "
			+ "and (m.accountId = :accountId or m.toAccountId = :accountId) and m.id <> :excludeId")
	Optional<LocalDate> findEarliestDate(@Param("userId") Long userId, @Param("accountId") Long accountId,
			@Param("excludeId") Long excludeId);

	/** 修改對帳時檢查同一天有沒有「別的」對帳（排除自己）。 */
	boolean existsByUserIdAndAccountIdAndTypeAndMovementDateAndVoidedFalseAndIdNot(Long userId, Long accountId,
			CashMovementType type, LocalDate movementDate, Long id);

}
