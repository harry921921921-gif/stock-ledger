package com.sideproject.stockledger.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.PledgeCollateral;

/** 質押擔保股票：查詢都帶 userId。這是設定，不是事件；股數改成 0 就刪掉那一列。 */
public interface PledgeCollateralRepository extends Repository<PledgeCollateral, Long> {

	PledgeCollateral save(PledgeCollateral collateral);

	void delete(PledgeCollateral collateral);

	List<PledgeCollateral> findByUserId(Long userId);

	List<PledgeCollateral> findByUserIdAndLoanId(Long userId, Long loanId);

	/** 沒作廢借款的擔保列（擔保合計用）。作廢借款時會刪掉它的擔保列，這裡再排除一次是防舊資料。 */
	@Query("select c from PledgeCollateral c where c.userId = :userId and exists "
			+ "(select l from Loan l where l.id = c.loanId and l.voided = false)")
	List<PledgeCollateral> findActiveByUserId(@Param("userId") Long userId);

	Optional<PledgeCollateral> findByUserIdAndLoanIdAndStockId(Long userId, Long loanId, Long stockId);

}
