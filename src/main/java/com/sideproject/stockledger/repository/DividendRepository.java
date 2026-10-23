package com.sideproject.stockledger.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.sideproject.stockledger.entity.Dividend;

/** 除權除息（共用資料）。依（股票、除權息日）新增或更新。 */
public interface DividendRepository extends Repository<Dividend, Long> {

	Dividend save(Dividend dividend);

	Optional<Dividend> findByStockIdAndExDate(Long stockId, java.time.LocalDate exDate);

	List<Dividend> findByStockIdInOrderByExDateDesc(Collection<Long> stockIds);

}
