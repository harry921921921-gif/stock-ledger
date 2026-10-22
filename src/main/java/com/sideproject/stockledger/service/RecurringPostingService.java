package com.sideproject.stockledger.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sideproject.stockledger.calculator.DuePeriod;
import com.sideproject.stockledger.calculator.RecurringScheduleCalculator;
import com.sideproject.stockledger.entity.AmountMode;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.RecurringItem;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.RecurringItemRepository;

/**
 * 固定收支自動記帳（設計文件 4.2）：固定金額的項目到了入帳日，產生一筆存入或支出。
 * app 啟動時補記（家裡電腦不會一直開機），每天 00:10（Asia/Taipei）再檢查一次。
 * <p>
 * 這是系統排程，不是使用者操作，沒有登入者：逐一處理每個使用者的項目，只讀寫每個項目自己的 user_id，
 * 不接受任何外部輸入。這是「user_id 只從 CurrentUser 取得」的唯一例外（DR-036）。
 */
@Service
public class RecurringPostingService {

	private static final Logger log = LoggerFactory.getLogger(RecurringPostingService.class);

	private final RecurringItemRepository items;
	private final CashMovementRepository movements;
	private final TransactionTemplate transaction;
	private final Clock clock;
	private final boolean postOnStartup;

	public RecurringPostingService(RecurringItemRepository items, CashMovementRepository movements,
			TransactionTemplate transaction, Clock clock,
			@Value("${app.recurring.post-on-startup:true}") boolean postOnStartup) {
		this.postOnStartup = postOnStartup;
		this.items = items;
		this.movements = movements;
		this.transaction = transaction;
		this.clock = clock;
	}

	/** 啟動時補記；整合測試關掉（app.recurring.post-on-startup=false），避免建立測試環境時悄悄產生異動。 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (postOnStartup) {
			postDue();
		}
	}

	@Scheduled(cron = "0 10 0 * * *", zone = "Asia/Taipei")
	public void daily() {
		postDue();
	}

	/**
	 * 所有使用者到期、還沒產生的期數。每個項目各自一個交易：一個項目出錯（記在 log）不影響其他項目，
	 * 下次執行會再試。回傳這次產生了幾筆。
	 */
	public int postDue() {
		LocalDate today = LocalDate.now(clock);
		List<RecurringItem> all = items.findAllForPosting();
		int created = 0;
		for (RecurringItem item : all) {
			try {
				// 在交易裡鎖住並重新讀取這個項目：使用者同時在改它時，兩邊依序執行，也不會用到過期的設定
				Integer n = transaction.execute(status -> items.findForUpdate(item.getId(), item.getUserId())
					.map(fresh -> postDue(fresh, today))
					.orElse(0));
				created += n == null ? 0 : n;
			}
			catch (RuntimeException ex) {
				log.error("固定項目 {} 自動記帳失敗，下次執行會再試", item.getId(), ex);
			}
		}
		if (created > 0) {
			log.info("固定收支自動記帳：產生 {} 筆", created);
		}
		return created;
	}

	/**
	 * 一個項目到 today 為止到期、還沒產生的期數（新增或修改項目時也會呼叫）。
	 * <ul>
	 * <li>只處理固定金額；每月自己填的等使用者填。</li>
	 * <li>已經產生過的期數（含作廢的）不再產生：作廢後不會又冒出來。</li>
	 * <li>入帳日早於帳戶初始現金餘額日期的期數略過（不算已產生）。</li>
	 * <li>不會產生未來日期；用當下的設定（金額、帳戶）。</li>
	 * </ul>
	 */
	@Transactional
	public int postDue(RecurringItem item, LocalDate today) {
		if (item.getAmountMode() != AmountMode.FIXED) {
			return 0;
		}
		Optional<LocalDate> opening = movements
			.findByUserIdAndAccountIdAndTypeAndVoidedFalse(item.getUserId(), item.getAccountId(), CashMovementType.OPENING)
			.map(CashMovement::getMovementDate);
		LocalDateTime now = LocalDateTime.now(clock);
		int created = 0;
		for (DuePeriod p : RecurringScheduleCalculator.dueOnOrBefore(item.rule(), today)) {
			// 改過月份、入帳日或帳戶：之前的期數不用新規則補記
			if (p.period().isBefore(item.getPostingFrom())) {
				continue;
			}
			if (opening.isPresent() && p.dueDate().isBefore(opening.get())) {
				continue;
			}
			if (movements.existsByUserIdAndRecurringItemIdAndPeriod(item.getUserId(), item.getId(),
					p.period().atDay(1))) {
				continue;
			}
			movements.saveAndFlush(CashMovement.ofRecurring(item, p.dueDate(), item.getAmount(), p.period(), now));
			created++;
		}
		return created;
	}

}
