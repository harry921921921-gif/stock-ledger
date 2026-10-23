package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.NetWorthCalculator;
import com.sideproject.stockledger.dto.FundingView;
import com.sideproject.stockledger.dto.LoanCard;
import com.sideproject.stockledger.dto.OverviewView;
import com.sideproject.stockledger.dto.OverviewView.DueReminder;
import com.sideproject.stockledger.dto.OverviewView.FlowRow;
import com.sideproject.stockledger.dto.OverviewView.MaintenanceRow;
import com.sideproject.stockledger.dto.StocksView;
import com.sideproject.stockledger.dto.TotalAssetsView;
import com.sideproject.stockledger.entity.CashMovement;
import com.sideproject.stockledger.entity.CashMovementType;

/**
 * 總覽頁（spec 第 3 節第 1 頁）：淨資產、某個月的淨資產變動拆解、報酬率、借款淨賺、維持率、資產與負債、本月資金移動、持股。
 * 只組合其他 Service 算好的數字；新的公式在 NetWorthCalculator。
 */
@Service
public class OverviewService {

	/** 本月資金移動要列的類型（spec 第 2 節：只改變資產形式、不改變財富）。 */
	private static final List<CashMovementType> FLOW_TYPES = List.of(CashMovementType.STOCK_BUY,
			CashMovementType.STOCK_SELL, CashMovementType.LOAN_DISBURSEMENT, CashMovementType.LOAN_PRINCIPAL,
			CashMovementType.TRANSFER);

	private static final YearMonth EARLIEST = YearMonth.of(2000, 1);

	private final TotalAssetsService totalAssets;
	private final TradeService trades;
	private final LoanService loans;
	private final CashMovementService movements;
	private final Clock clock;

	public OverviewService(TotalAssetsService totalAssets, TradeService trades, LoanService loans,
			CashMovementService movements, Clock clock) {
		this.totalAssets = totalAssets;
		this.trades = trades;
		this.loans = loans;
		this.movements = movements;
		this.clock = clock;
	}

	/** month 為 null、晚於本月或早於 2000-01 時顯示本月。 */
	@Transactional(readOnly = true)
	public OverviewView view(YearMonth requested) {
		YearMonth current = YearMonth.from(LocalDate.now(clock));
		YearMonth month = requested == null || requested.isAfter(current) || requested.isBefore(EARLIEST) ? current
				: requested;
		TotalAssetsView assets = totalAssets.view();
		FundingView funding = loans.fundingView();
		StocksView stocks = trades.view();

		BigDecimal debt = funding.totalDebt();
		BigDecimal loanNetGain = funding.loans().isEmpty() ? null
				: funding.loans().stream().map(LoanCard::netGain).reduce(BigDecimal.ZERO, BigDecimal::add);
		List<MaintenanceRow> maintenance = new ArrayList<>();
		for (LoanCard c : funding.loans()) {
			if (c.pledge() && c.maintenancePercent() != null) {
				maintenance.add(new MaintenanceRow(c.name(), c.maintenancePercent(), c.maintenanceLevel(),
						c.warnPercent(), c.callPercent()));
			}
		}
		return new OverviewView(NetWorthCalculator.netWorth(assets.totalAssets(), debt), assets.totalAssets(),
				assets.stockValue(), assets.cash(), debt, funding.weightedRatePercent(),
				NetWorthCalculator.leveragePercent(debt, assets.totalAssets()).orElse(null), month,
				month.isAfter(EARLIEST) ? month.minusMonths(1) : null, month.equals(current) ? null : month.plusMonths(1),
				month.equals(current) ? assets.monthChange() : totalAssets.monthChange(month), stocks.returnPercent(),
				loanNetGain, maintenance, assets.reminders(), flows(month), stocks.holdings(), stocks.priceDate(),
				funding.loans().stream()
					.filter(LoanCard::dueSoon)
					.map(c -> new DueReminder(c.name(), c.dueDate(), c.daysToDue(), c.amountAtDue()))
					.toList());
	}

	/** 這個月沒作廢的資金移動，依類型加總。 */
	private List<FlowRow> flows(YearMonth month) {
		Map<CashMovementType, BigDecimal> sums = new LinkedHashMap<>();
		FLOW_TYPES.forEach(t -> sums.put(t, BigDecimal.ZERO));
		for (CashMovement m : movements.listAll()) {
			if (!m.isVoided() && YearMonth.from(m.getMovementDate()).equals(month) && sums.containsKey(m.getType())) {
				sums.merge(m.getType(), m.getAmount(), BigDecimal::add);
			}
		}
		return sums.entrySet().stream()
			.filter(e -> e.getValue().signum() != 0)
			.map(e -> new FlowRow(e.getKey().label(), e.getValue().setScale(0, RoundingMode.HALF_UP)))
			.toList();
	}

}
