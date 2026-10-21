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

}
