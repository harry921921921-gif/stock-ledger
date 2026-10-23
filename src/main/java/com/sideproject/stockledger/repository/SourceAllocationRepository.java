package com.sideproject.stockledger.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.SourceAllocation;

/** 資金來源分攤：查詢都帶 userId。修改交易時整批換掉（交易的修改紀錄會記下舊的分攤）。 */
public interface SourceAllocationRepository extends Repository<SourceAllocation, Long> {

	SourceAllocation save(SourceAllocation allocation);

	List<SourceAllocation> findByUserId(Long userId);

	List<SourceAllocation> findByUserIdAndTradeId(Long userId, Long tradeId);

	@Modifying(flushAutomatically = true)
	@Query("delete from SourceAllocation a where a.userId = :userId and a.tradeId = :tradeId")
	int deleteForTrade(@Param("userId") Long userId, @Param("tradeId") Long tradeId);

}
