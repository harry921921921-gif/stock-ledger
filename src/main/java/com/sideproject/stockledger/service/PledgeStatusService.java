package com.sideproject.stockledger.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.calculator.Portfolio;
import com.sideproject.stockledger.calculator.Position;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.LoanType;
import com.sideproject.stockledger.entity.PledgeCollateral;
import com.sideproject.stockledger.entity.Stock;
import com.sideproject.stockledger.repository.LoanRepository;
import com.sideproject.stockledger.repository.PledgeCollateralRepository;
import com.sideproject.stockledger.repository.StockRepository;
import com.sideproject.stockledger.security.CurrentUser;

/**
 * 哪些質押現在還在質押中（設計文件 2026-11-04-pledged-stocks 第 6 節）：沒作廢、而且到今天還沒還清
 * （{@link LoanFacts#repaid}：剩餘本金（到今天）≤ 0 而且已經有一筆沒作廢的還本；還沒撥款的新借款不算還清、仍是質押中）。
 * 由事件推算、不另存：作廢或修改還本、再動撥後又不是還清，擔保自動恢復。已還清借款的擔保列不刪，
 * 只是不算進質押中、不擋賣出、不算進其他質押的擔保合計。
 * 只依賴 Repository、LoanFactsReader、PortfolioReader（兩個都只讀 Repository）、CurrentUser、Clock、MoneyFormatter：
 * TradeService、LoanService、NoticeService、CashMovementService 都會呼叫它，依賴它們會造成循環依賴。
 */
@Service
public class PledgeStatusService {

	private final LoanRepository loans;
	private final PledgeCollateralRepository collaterals;
	private final StockRepository stocks;
	private final LoanFactsReader facts;
	private final PortfolioReader portfolioReader;
	private final MoneyFormatter money;
	private final CurrentUser currentUser;
	private final Clock clock;

	public PledgeStatusService(LoanRepository loans, PledgeCollateralRepository collaterals, StockRepository stocks,
			LoanFactsReader facts, PortfolioReader portfolioReader, MoneyFormatter money, CurrentUser currentUser,
			Clock clock) {
		this.loans = loans;
		this.collaterals = collaterals;
		this.stocks = stocks;
		this.facts = facts;
		this.portfolioReader = portfolioReader;
		this.money = money;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	/** 一檔股票質押中的擔保股數超過持股。 */
	public record Excess(Long stockId, long pledged, long held) {
	}

	/** 登入者還在質押中的借款 id（沒作廢的質押、到今天還沒還清），照借款 id 排。 */
	@Transactional(readOnly = true)
	public Set<Long> activePledgeLoanIds() {
		LocalDate today = LocalDate.now(clock);
		Set<Long> ids = new LinkedHashSet<>();
		for (Loan loan : loans.findByUserIdAndVoidedFalseOrderByIdAsc(currentUser.id())) {
			if (loan.getType() == LoanType.PLEDGE && !facts.repaid(loan, today)) {
				ids.add(loan.getId());
			}
		}
		return ids;
	}

	/** 還在質押中的擔保股數：股票 id → 合計股數（照股票 id 排）。 */
	@Transactional(readOnly = true)
	public Map<Long, Long> pledgedSharesByStock() {
		return pledgedSharesByStockOtherThan(null);
	}

	/** 其他還在質押中的借款押了這檔多少股（設定擔保時「合計不能超過持股」用）。 */
	@Transactional(readOnly = true)
	public long pledgedSharesOtherThan(Long loanId, Long stockId) {
		return pledgedSharesByStockOtherThan(loanId).getOrDefault(stockId, 0L);
	}

	/** 除了 loanId（null ＝ 不排除）以外、還在質押中的借款押的股數：股票 id → 合計股數（照股票 id 排）。 */
	@Transactional(readOnly = true)
	public Map<Long, Long> pledgedSharesByStockOtherThan(Long loanId) {
		Set<Long> active = activePledgeLoanIds();
		Map<Long, Long> shares = new TreeMap<>();
		collaterals.findByUserId(currentUser.id())
			.stream()
			.filter(c -> active.contains(c.getLoanId()) && !c.getLoanId().equals(loanId))
			.forEach(c -> shares.merge(c.getStockId(), c.getShares(), Long::sum));
		return shares;
	}

	/**
	 * 這檔股票質押中的擔保合計是否超過 portfolio 的持股（賣出、改股數後；spec 5.5）。
	 * portfolio 由呼叫端給：交易試算時包含還沒存的那一筆。
	 */
	@Transactional(readOnly = true)
	public Optional<Excess> excess(Portfolio portfolio, Long stockId) {
		return excess(pledgedSharesByStock(), portfolio, stockId);
	}

	/**
	 * 借款的撥款、還本改了（作廢、修改還本、修改借款、再動撥）之後：如果這筆質押變回質押中，
	 * 它的擔保股票加上其他質押不能超過目前持股。超過時回傳訊息（呼叫端丟自己的例外，整筆退回）；
	 * 不是質押、已作廢或仍是已還清 → empty。
	 *
	 * @param what 訊息開頭，例如「這筆還本作廢／修改後」
	 */
	@Transactional(readOnly = true)
	public Optional<String> collateralNotHeld(Long loanId, String what) {
		Long userId = currentUser.id();
		Optional<Loan> loan = loans.findByIdAndUserId(loanId, userId);
		if (loan.isEmpty() || !activePledgeLoanIds().contains(loanId)) {
			return Optional.empty();
		}
		Map<Long, Long> pledged = pledgedSharesByStock();
		Portfolio portfolio = portfolioReader.read(userId);
		for (PledgeCollateral c : collaterals.findByUserIdAndLoanId(userId, loanId)) {
			Optional<Excess> excess = excess(pledged, portfolio, c.getStockId());
			if (excess.isPresent()) {
				String code = stocks.findById(c.getStockId()).map(Stock::getCode).orElse("");
				return Optional.of(what + "，「" + loan.get().getName() + "」又回到質押中，" + code + " 擔保 "
						+ money.plain(BigDecimal.valueOf(excess.get().pledged())) + " 股超過目前持股 "
						+ money.plain(BigDecimal.valueOf(excess.get().held())) + " 股；請先到資金來源頁調整擔保股數");
			}
		}
		return Optional.empty();
	}

	/** 擔保合計和持股的比較：賣出與借款改動共用這一個。 */
	private static Optional<Excess> excess(Map<Long, Long> pledgedByStock, Portfolio portfolio, Long stockId) {
		long pledged = pledgedByStock.getOrDefault(stockId, 0L);
		Position pos = portfolio.positions().get(stockId);
		long held = pos == null ? 0 : pos.shares();
		return pledged > held ? Optional.of(new Excess(stockId, pledged, held)) : Optional.empty();
	}

}
