package com.sideproject.stockledger.repository;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.sideproject.stockledger.entity.PriceUpdateLog;

/** 股價與除權息抓取紀錄（共用資料）。只新增。 */
public interface PriceUpdateLogRepository extends Repository<PriceUpdateLog, Long> {

	PriceUpdateLog save(PriceUpdateLog log);

	List<PriceUpdateLog> findTop10ByOrderByRunAtDescIdDesc();

}
