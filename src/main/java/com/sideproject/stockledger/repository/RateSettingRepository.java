package com.sideproject.stockledger.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.sideproject.stockledger.entity.RateKind;
import com.sideproject.stockledger.entity.RateSetting;

/**
 * 只提供「全域列 + 指定使用者的列」的查詢，看不到別人的個人費率（spec 4.10-3）。
 * 繼承 {@link Repository}：沒有 findAll、findById、delete（費率只新增、不修改也不刪除，spec 4.5-2）。
 */
public interface RateSettingRepository extends Repository<RateSetting, Long> {

	RateSetting save(RateSetting rate);

	@Query("select r from RateSetting r where r.kind = :kind and (r.userId is null or r.userId = :userId)")
	List<RateSetting> findVisible(@Param("kind") RateKind kind, @Param("userId") Long userId);

	@Query("select r from RateSetting r where r.userId is null or r.userId = :userId")
	List<RateSetting> findAllVisible(@Param("userId") Long userId);

}
