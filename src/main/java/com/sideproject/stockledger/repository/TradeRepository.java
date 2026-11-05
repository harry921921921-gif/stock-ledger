package com.sideproject.stockledger.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.Trade;

/** 股票交易：查詢都帶 userId；沒有 delete（不硬刪除）。 */
public interface TradeRepository extends Repository<Trade, Long> {

	Trade saveAndFlush(Trade trade);

	void flush();

	Optional<Trade> findByIdAndUserId(Long id, Long userId);

	/** 修改或作廢前讀取並鎖住這一列。 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Trade t where t.id = :id and t.userId = :userId")
	Optional<Trade> findForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	List<Trade> findByUserIdAndVoidedFalse(Long userId);

	List<Trade> findByUserIdOrderByTradeDateDescIdDesc(Long userId);

	/** 這個人這檔股票有沒有任何沒作廢的交易（期初持股、買進、賣出、配股都算）。 */
	boolean existsByUserIdAndStockIdAndVoidedFalse(Long userId, Long stockId);

}
