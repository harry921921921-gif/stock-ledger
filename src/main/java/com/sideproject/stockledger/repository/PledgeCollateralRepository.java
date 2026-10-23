package com.sideproject.stockledger.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.sideproject.stockledger.entity.PledgeCollateral;

/** 質押擔保股票：查詢都帶 userId。這是設定，不是事件；股數改成 0 就刪掉那一列。 */
public interface PledgeCollateralRepository extends Repository<PledgeCollateral, Long> {

	PledgeCollateral save(PledgeCollateral collateral);

	void delete(PledgeCollateral collateral);

	List<PledgeCollateral> findByUserId(Long userId);

	Optional<PledgeCollateral> findByUserIdAndLoanIdAndStockId(Long userId, Long loanId, Long stockId);

}
