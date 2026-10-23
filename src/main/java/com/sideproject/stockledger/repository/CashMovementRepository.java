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

	/** 這個固定項目這一期是否已經產生過異動（含作廢的）：固定金額的項目作廢後不再自動補。 */
	boolean existsByUserIdAndRecurringItemIdAndPeriod(Long userId, Long recurringItemId, LocalDate period);

	/** 這個固定項目沒作廢的異動（找出每月自己填還沒填的期數）。 */
	List<CashMovement> findByUserIdAndRecurringItemIdAndVoidedFalse(Long userId, Long recurringItemId);

	/** 這個固定項目產生的所有異動（含作廢的），新的在前；全部異動頁依項目篩選用。 */
	List<CashMovement> findByUserIdAndRecurringItemIdOrderByMovementDateDescIdDesc(Long userId, Long recurringItemId);

	/** 股票交易自動記的那筆（沒作廢的）。 */
	Optional<CashMovement> findByUserIdAndTradeIdAndVoidedFalse(Long userId, Long tradeId);

	/** 某種類型沒作廢的異動（例如所有配息）。 */
	List<CashMovement> findByUserIdAndTypeAndVoidedFalse(Long userId, CashMovementType type);

	/** 某筆借款的所有異動（含作廢的），舊的在前；推算剩餘本金、攤還、利息用。 */
	List<CashMovement> findByUserIdAndLoanIdOrderByMovementDateAscIdAsc(Long userId, Long loanId);

	/** 某筆借款全部異動，新的在前（全部異動頁依借款篩選）。 */
	List<CashMovement> findByUserIdAndLoanIdOrderByMovementDateDescIdDesc(Long userId, Long loanId);

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
	 * 用來檢查「初始現金餘額不能晚於其他異動」。借款與股票買賣自動記的不算：它們照真實日期記，
	 * 早於初始現金餘額日期的不影響餘額（DR-047）。
	 */
	@Query("select min(m.movementDate) from CashMovement m where m.userId = :userId and m.voided = false "
			+ "and (m.accountId = :accountId or m.toAccountId = :accountId) and m.id <> :excludeId "
			+ "and m.type not in (com.sideproject.stockledger.entity.CashMovementType.LOAN_DISBURSEMENT, "
			+ "com.sideproject.stockledger.entity.CashMovementType.LOAN_PRINCIPAL, "
			+ "com.sideproject.stockledger.entity.CashMovementType.LOAN_INTEREST, "
			+ "com.sideproject.stockledger.entity.CashMovementType.STOCK_BUY, "
			+ "com.sideproject.stockledger.entity.CashMovementType.STOCK_SELL)")
	Optional<LocalDate> findEarliestDate(@Param("userId") Long userId, @Param("accountId") Long accountId,
			@Param("excludeId") Long excludeId);

	/** 修改對帳時檢查同一天有沒有「別的」對帳（排除自己）。 */
	boolean existsByUserIdAndAccountIdAndTypeAndMovementDateAndVoidedFalseAndIdNot(Long userId, Long accountId,
			CashMovementType type, LocalDate movementDate, Long id);

}
