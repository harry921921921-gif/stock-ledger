package com.sideproject.stockledger.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.StockPrice;

/** 每日收盤價（共用資料）。只新增。 */
public interface StockPriceRepository extends Repository<StockPrice, Long> {

	StockPrice save(StockPrice price);

	/** 某一天已經有收盤價的股票（避免重複寫入）。 */
	@Query("select p.stockId from StockPrice p where p.tradeDate = :date")
	List<Long> findStockIdsByTradeDate(@Param("date") LocalDate date);

	/** 這些股票在某一天（含）以前最新的收盤價，每檔一筆。 */
	@Query("select p from StockPrice p where p.stockId in :ids and p.tradeDate = (select max(p2.tradeDate) "
			+ "from StockPrice p2 where p2.stockId = p.stockId and p2.tradeDate <= :asOf)")
	List<StockPrice> findLatestOnOrBefore(@Param("ids") Collection<Long> ids, @Param("asOf") LocalDate asOf);

	/** 這些股票在某一天以後最早的收盤價，每檔一筆（那天以前還沒有股價時的替代）。 */
	@Query("select p from StockPrice p where p.stockId in :ids and p.tradeDate = (select min(p2.tradeDate) "
			+ "from StockPrice p2 where p2.stockId = p.stockId and p2.tradeDate > :after)")
	List<StockPrice> findEarliestAfter(@Param("ids") Collection<Long> ids, @Param("after") LocalDate after);

	@Query("select max(p.tradeDate) from StockPrice p")
	Optional<LocalDate> findLatestTradeDate();

}
