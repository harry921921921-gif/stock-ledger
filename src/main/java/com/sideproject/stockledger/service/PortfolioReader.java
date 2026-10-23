package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.DividendIncome;
import com.sideproject.stockledger.calculator.LoanFlow;
import com.sideproject.stockledger.calculator.LoanUsage;
import com.sideproject.stockledger.calculator.Portfolio;
import com.sideproject.stockledger.calculator.PortfolioCalculator;
import com.sideproject.stockledger.calculator.TradeEvent;
import com.sideproject.stockledger.entity.CashMovementType;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanOrigin;
import com.sideproject.stockledger.entity.SourceAllocation;
import com.sideproject.stockledger.repository.CashMovementRepository;
import com.sideproject.stockledger.repository.LoanRepository;
import com.sideproject.stockledger.repository.SourceAllocationRepository;
import com.sideproject.stockledger.repository.TradeRepository;

/**
 * 讀出一個使用者的交易、配息、借款流量，交給 PortfolioCalculator 算投資組合。
 * 獨立出來是為了讓 TradeService 與 CashMovementService 都能用（TradeService 依賴 CashMovementService，反過來會循環）。
 * userId 由呼叫端從 CurrentUser 取得，這裡不接受外部輸入。
 */
@Component
class PortfolioReader {

	private final TradeRepository trades;
	private final SourceAllocationRepository allocations;
	private final CashMovementRepository movements;
	private final LoanRepository loans;
	private final LoanFactsReader loanFacts;
	private final MoneyFormatter money;

	PortfolioReader(TradeRepository trades, SourceAllocationRepository allocations, CashMovementRepository movements,
			LoanRepository loans, LoanFactsReader loanFacts, MoneyFormatter money) {
		this.trades = trades;
		this.allocations = allocations;
		this.movements = movements;
		this.loans = loans;
		this.loanFacts = loanFacts;
		this.money = money;
	}

	@Transactional(readOnly = true)
	Portfolio read(Long userId) {
		Map<Long, Map<Long, BigDecimal>> allocationByTrade = new HashMap<>();
		for (SourceAllocation a : allocations.findByUserId(userId)) {
			allocationByTrade.computeIfAbsent(a.getTradeId(), k -> new HashMap<>()).put(a.getLoanId(), a.getAmount());
		}
		List<TradeEvent> events = trades.findByUserIdAndVoidedFalse(userId).stream()
			.map(t -> new TradeEvent(t.getId(), t.getType(), t.getTradeDate(), t.getStockId(), t.getShares(),
					t.getAmount(), allocationByTrade.getOrDefault(t.getId(), Map.of())))
			.toList();
		List<DividendIncome> income = movements.findByUserIdAndTypeAndVoidedFalse(userId, CashMovementType.DIVIDEND)
			.stream()
			.map(m -> new DividendIncome(m.getMovementDate(), m.getStockId(), m.getAmount()))
			.toList();
		return PortfolioCalculator.compute(events, income, loanFlows(userId));
	}

	/**
	 * 每筆借款買股票用掉的錢，在任何一天都不能超過那天以前借到的（DR-044）。有問題時回傳給使用者看的訊息。
	 */
	@Transactional(readOnly = true)
	Optional<String> uncoveredLoan(Long userId) {
		for (Map.Entry<Long, LoanUsage> e : read(userId).loans().entrySet()) {
			BigDecimal lowest = e.getValue().lowestUndeployed();
			if (lowest.signum() < 0) {
				String name = loans.findByIdAndUserId(e.getKey(), userId).map(Loan::getName).orElse("借款");
				return Optional.of("這樣「" + name + "」買股票用掉的錢會超過那時已經借到的錢（差 " + money.plain(lowest.negate())
						+ " 元）；請先修改那些交易的資金來源");
			}
		}
		return Optional.empty();
	}

	/** 每筆沒作廢的借款：起算本金（既有借款）與撥款進來，還本出去。 */
	private List<LoanFlow> loanFlows(Long userId) {
		List<LoanFlow> flows = new ArrayList<>();
		for (Loan loan : loans.findByUserIdAndVoidedFalseOrderByIdAsc(userId)) {
			LoanFacts f = loanFacts.read(loan);
			if (loan.getOrigin() == LoanOrigin.EXISTING) {
				flows.add(new LoanFlow(loan.getId(), loan.getStartDate(), loan.getStartPrincipal()));
			}
			f.principalChanges().forEach(c -> flows.add(new LoanFlow(loan.getId(), c.date(), c.delta())));
		}
		return flows;
	}

}
