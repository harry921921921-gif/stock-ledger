package com.sideproject.stockledger.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.Loan;

/**
 * 借款。使用者操作的查詢都帶 userId；沒有 delete（不硬刪除）。唯一的例外是 {@link #findAllForPosting}（DR-036）。
 */
public interface LoanRepository extends Repository<Loan, Long> {

	Loan save(Loan loan);

	Optional<Loan> findByIdAndUserId(Long id, Long userId);

	/** 沒作廢的借款（資金來源頁、本月利息）。 */
	List<Loan> findByUserIdAndVoidedFalseOrderByIdAsc(Long userId);

	/** 修改、還本或自動記帳前讀取並鎖住這一列（SELECT … FOR UPDATE），讓同時進行的操作依序執行。 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from Loan l where l.id = :id and l.userId = :userId")
	Optional<Loan> findForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/**
	 * 自動記帳排程用（DR-036）：所有使用者沒作廢的借款。排程沒有登入者，所以這是唯一不帶 user_id 的查詢；
	 * 呼叫端只用每筆借款自己的 user_id 讀寫。
	 */
	@Query("select l from Loan l where l.voided = false order by l.id")
	List<Loan> findAllForPosting();

}
