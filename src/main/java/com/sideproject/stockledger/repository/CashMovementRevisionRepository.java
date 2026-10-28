package com.sideproject.stockledger.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.CashMovementRevision;

/** 修改紀錄只新增、不修改也不刪除；查詢都帶 userId。 */
public interface CashMovementRevisionRepository extends Repository<CashMovementRevision, Long> {

	CashMovementRevision save(CashMovementRevision revision);

	List<CashMovementRevision> findByUserIdAndCashMovementIdOrderByIdAsc(Long userId, Long cashMovementId);

	/** 目前登入者有修改紀錄的現金事件 id（列表顯示「曾修改」）。 */
	@Query("select distinct r.cashMovementId from CashMovementRevision r where r.userId = :userId")
	List<Long> findRevisedMovementIds(@Param("userId") Long userId);

	/**
	 * 使用者自己改過（或作廢過）的現金事件 id：有一筆修改紀錄的原因不是系統重算時寫的 systemReason
	 * （沒填原因的也算使用者改的）。修改借款時用來判斷哪些期數要保留（DR-051）。
	 */
	@Query("select distinct r.cashMovementId from CashMovementRevision r where r.userId = :userId "
			+ "and (r.reason is null or r.reason <> :systemReason)")
	List<Long> findUserEditedMovementIds(@Param("userId") Long userId, @Param("systemReason") String systemReason);

}
