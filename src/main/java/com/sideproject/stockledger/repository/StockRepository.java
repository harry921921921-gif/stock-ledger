package com.sideproject.stockledger.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.sideproject.stockledger.entity.Stock;

/** 上市股票清單（共用資料，沒有 user_id）。只由證交所資料新增與改名；沒有 delete。 */
public interface StockRepository extends Repository<Stock, Long> {

	Stock save(Stock stock);

	List<Stock> findAll();

	Optional<Stock> findById(Long id);

	Optional<Stock> findByCode(String code);

	List<Stock> findByIdIn(List<Long> ids);

	long count();

}
