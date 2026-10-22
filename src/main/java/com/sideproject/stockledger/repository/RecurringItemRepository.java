package com.sideproject.stockledger.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.RecurringItem;

/**
 * 固定項目。使用者操作的查詢都帶 userId；沒有 delete（不硬刪除，不用了就填結束月份）。
 * 唯一的例外是 {@link #findAllForPosting}（DR-036）。
 */
public interface RecurringItemRepository extends Repository<RecurringItem, Long> {

	RecurringItem save(RecurringItem item);

	Optional<RecurringItem> findByIdAndUserId(Long id, Long userId);

	List<RecurringItem> findByUserIdOrderByNameAscIdAsc(Long userId);

	/** 修改、填金額或自動記帳前讀取並鎖住這一列（SELECT … FOR UPDATE），讓同時進行的操作依序執行。 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from RecurringItem i where i.id = :id and i.userId = :userId")
	Optional<RecurringItem> findForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/**
	 * 自動記帳排程用（DR-036）：所有使用者的固定金額項目。已結束的也要拿：電腦關機期間結束的項目，
	 * 最後幾期可能還沒產生。排程不是使用者操作、沒有登入者，所以這是唯一不帶 user_id 的查詢；
	 * 呼叫端只用每個項目自己的 user_id 讀寫。
	 */
	@Query("select i from RecurringItem i where i.amountMode = com.sideproject.stockledger.entity.AmountMode.FIXED "
			+ "order by i.id")
	List<RecurringItem> findAllForPosting();

}
