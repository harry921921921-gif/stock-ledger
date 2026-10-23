package com.sideproject.stockledger.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.TradeRevision;

/** 交易修改紀錄：只新增；查詢都帶 userId。 */
public interface TradeRevisionRepository extends Repository<TradeRevision, Long> {

	TradeRevision save(TradeRevision revision);

	@Query("select distinct r.tradeId from TradeRevision r where r.userId = :userId")
	List<Long> findRevisedTradeIds(@Param("userId") Long userId);

}
