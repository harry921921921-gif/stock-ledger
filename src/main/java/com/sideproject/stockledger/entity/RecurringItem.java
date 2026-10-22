package com.sideproject.stockledger.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.sideproject.stockledger.calculator.RecurringRule;

/**
 * 一個固定收支項目（設計文件 4.1、spec 6 RecurringItem）。這是「設定」不是交易事件：修改直接更新、不寫修改紀錄；
 * 不硬刪除，不用了就填結束月份。收入或支出、金額方式、週期、開始月份建立後不能改（要改就結束後另建一個）。
 */
@Entity
@Table(name = "recurring_item")
public class RecurringItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(nullable = false, length = 50)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private RecurringDirection direction;

	@Enumerated(EnumType.STRING)
	@Column(name = "amount_mode", nullable = false, length = 10, updatable = false)
	private AmountMode amountMode;

	@Column(precision = 19, scale = 4)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12, updatable = false)
	private RecurringCycle cycle;

	@Column(name = "anchor_month")
	private Integer anchorMonth;

	@Column(name = "day_of_month", nullable = false)
	private int dayOfMonth;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	/** 那個月的 1 號。 */
	@Column(name = "start_month", nullable = false, updatable = false)
	private LocalDate startMonth;

	/** 那個月的 1 號；沒有結束為 null。 */
	@Column(name = "end_month")
	private LocalDate endMonth;

	/**
	 * 從哪一期開始記帳（那個月的 1 號）：建立時等於開始月份；改了月份、入帳日或帳戶就改成那時的月份，
	 * 修改才不會用新規則回頭補記過去（例如年繳從 1 月改成 3 月，過去幾年的 3 月不會冒出來）。
	 */
	@Column(name = "posting_from", nullable = false)
	private LocalDate postingFrom;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected RecurringItem() {
		// JPA 需要
	}

	public static RecurringItem create(Long userId, String name, RecurringDirection direction, AmountMode amountMode,
			BigDecimal amount, RecurringCycle cycle, Integer anchorMonth, int dayOfMonth, Long accountId,
			YearMonth startMonth, YearMonth endMonth, LocalDateTime now) {
		RecurringItem item = new RecurringItem();
		item.userId = userId;
		item.direction = direction;
		item.amountMode = amountMode;
		item.cycle = cycle;
		item.startMonth = startMonth.atDay(1);
		item.postingFrom = item.startMonth;
		item.createdAt = now;
		item.change(name, amount, anchorMonth, dayOfMonth, accountId, endMonth, startMonth);
		return item;
	}

	/**
	 * 修改：只影響還沒產生的期數，已經記好的異動不變。每月自己填的項目不存金額。
	 * 改了「哪幾期、哪天、哪個帳戶」（月份、入帳日、帳戶）時，記帳起點移到 currentMonth：
	 * 本月以前沒記到的期數（例如因為早於初始現金餘額而略過的）不會用新規則補出來。
	 */
	public void change(String name, BigDecimal amount, Integer anchorMonth, int dayOfMonth, Long accountId,
			YearMonth endMonth, YearMonth currentMonth) {
		// 先建規則：入帳日、月份、結束月份不合理時在這裡就擋下
		new RecurringRule(cycle, anchorMonth, dayOfMonth, getStartMonth(), endMonth);
		boolean scheduleChanged = this.accountId != null && (!Objects.equals(this.anchorMonth, anchorMonth)
				|| this.dayOfMonth != dayOfMonth || !this.accountId.equals(accountId));
		if (scheduleChanged && currentMonth.atDay(1).isAfter(postingFrom)) {
			this.postingFrom = currentMonth.atDay(1);
		}
		this.name = CashAccount.normalizeName(name);
		this.amount = amountMode == AmountMode.FIXED ? amount : null;
		this.anchorMonth = anchorMonth;
		this.dayOfMonth = dayOfMonth;
		this.accountId = accountId;
		this.endMonth = endMonth == null ? null : endMonth.atDay(1);
	}

	/** 交給 RecurringScheduleCalculator 計算用。 */
	public RecurringRule rule() {
		return new RecurringRule(cycle, anchorMonth, dayOfMonth, getStartMonth(), getEndMonth());
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getName() {
		return name;
	}

	public RecurringDirection getDirection() {
		return direction;
	}

	public AmountMode getAmountMode() {
		return amountMode;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public RecurringCycle getCycle() {
		return cycle;
	}

	public Integer getAnchorMonth() {
		return anchorMonth;
	}

	public int getDayOfMonth() {
		return dayOfMonth;
	}

	public Long getAccountId() {
		return accountId;
	}

	public YearMonth getStartMonth() {
		return YearMonth.from(startMonth);
	}

	public YearMonth getEndMonth() {
		return endMonth == null ? null : YearMonth.from(endMonth);
	}

	/** 從哪一期開始記帳；早於這個月份的期數不再自動產生，也不用填。 */
	public YearMonth getPostingFrom() {
		return YearMonth.from(postingFrom);
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
