package com.sideproject.stockledger.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sideproject.stockledger.entity.TradeType;

/**
 * 持股、資金來源標記與借款用途（spec 4.1、4.2、4.7、DR-002、DR-015、DR-016、DR-017）。純 Java、不依賴 Spring。
 * <ul>
 * <li>平均成本法：買進加成本（含手續費）；賣出成本 ＝ 平均成本 × 賣出股數；配股只加股數。</li>
 * <li>來源標記：期初與買進依使用者的分配；賣出按比例減少；賣股入帳全部算自有（不回到借款）。</li>
 * <li>借款：起算本金與撥款進「尚未投入」，期初與買進用掉；還本先扣尚未投入，不足的部分按各持股中這筆借款的
 * 金額比例，把標記轉為自有。</li>
 * <li>已實現損益與配息依當時「借款持有的股數 ÷ 持股」分到各借款（DR-045）。</li>
 * <li>記下每筆借款「尚未投入」的最低點，用來檢查有沒有某一天借款出的錢超過已經借到的。</li>
 * <li>借款持有的股數：每筆期初與買進按借款金額 ÷ 總成本分股數；配股按比例增加；賣出、還本轉自有時按比例減少（借款帳面損益用）。</li>
 * </ul>
 * 同一天的順序：借款撥入 → 交易（依 id）→ 配息 → 還本。中間值保留 10 位、HALF_UP。
 */
public final class PortfolioCalculator {

	private static final int SCALE = 10;

	private PortfolioCalculator() {
	}

	public static Portfolio compute(List<TradeEvent> trades, List<DividendIncome> dividends, List<LoanFlow> loanFlows) {
		Objects.requireNonNull(trades, "trades");
		Objects.requireNonNull(dividends, "dividends");
		Objects.requireNonNull(loanFlows, "loanFlows");
		State state = new State();
		for (Step step : steps(trades, dividends, loanFlows)) {
			step.apply(state);
		}
		Map<Long, Position> positions = new HashMap<>();
		state.holdings.forEach((stock, h) -> positions.put(stock, h.toPosition(stock)));
		Map<Long, LoanUsage> loans = new HashMap<>();
		state.undeployed.keySet().forEach(loan -> loans.put(loan, state.usage(loan)));
		state.holdings.values().forEach(h -> h.loanTags.keySet().forEach(loan -> loans.computeIfAbsent(loan, state::usage)));
		return new Portfolio(Map.copyOf(positions), Map.copyOf(loans), List.copyOf(state.sales));
	}

	/** 某一天結束時持有的股數（賣出股數檢查用）。 */
	public static long sharesOn(List<TradeEvent> trades, Long stockId, LocalDate day) {
		long shares = 0;
		for (TradeEvent t : trades) {
			if (!t.stockId().equals(stockId) || t.date().isAfter(day)) {
				continue;
			}
			shares += t.type() == TradeType.SELL ? -t.shares() : t.shares();
		}
		return shares;
	}

	// ---------- 事件排序 ----------

	private interface Step {

		void apply(State state);

	}

	private record Ordered(LocalDate date, int rank, long tieBreak, Step step) {
	}

	private static List<Step> steps(List<TradeEvent> trades, List<DividendIncome> dividends, List<LoanFlow> flows) {
		List<Ordered> all = new ArrayList<>();
		for (LoanFlow f : flows) {
			all.add(new Ordered(f.date(), f.delta().signum() >= 0 ? 0 : 3, 0, s -> s.loanFlow(f)));
		}
		for (TradeEvent t : trades) {
			all.add(new Ordered(t.date(), 1, t.id() == null ? 0 : t.id(), s -> s.trade(t)));
		}
		for (DividendIncome d : dividends) {
			all.add(new Ordered(d.date(), 2, 0, s -> s.dividend(d)));
		}
		all.sort(Comparator.comparing(Ordered::date).thenComparingInt(Ordered::rank).thenComparingLong(Ordered::tieBreak));
		return all.stream().map(Ordered::step).toList();
	}

	// ---------- 狀態 ----------

	private static final class Holding {

		long shares;
		BigDecimal cost = BigDecimal.ZERO;
		BigDecimal own = BigDecimal.ZERO;
		final Map<Long, BigDecimal> loanTags = new HashMap<>();
		final Map<Long, BigDecimal> loanShares = new HashMap<>();
		BigDecimal dividends = BigDecimal.ZERO;

		Position toPosition(Long stockId) {
			Map<Long, BigDecimal> tags = new HashMap<>();
			loanTags.forEach((loan, amount) -> {
				if (amount.signum() != 0) {
					tags.put(loan, amount);
				}
			});
			Map<Long, BigDecimal> held = new HashMap<>();
			loanShares.forEach((loan, count) -> {
				if (count.signum() != 0) {
					held.put(loan, count);
				}
			});
			return new Position(stockId, shares, cost, own, Map.copyOf(tags), dividends, Map.copyOf(held));
		}

	}

	private static final class State {

		final Map<Long, Holding> holdings = new HashMap<>();
		final Map<Long, BigDecimal> undeployed = new HashMap<>();
		final Map<Long, BigDecimal> lowest = new HashMap<>();
		final Map<Long, BigDecimal> realized = new HashMap<>();
		final Map<Long, BigDecimal> loanDividends = new HashMap<>();
		final List<RealizedSale> sales = new ArrayList<>();

		Holding holding(Long stockId) {
			return holdings.computeIfAbsent(stockId, k -> new Holding());
		}

		void trade(TradeEvent t) {
			Holding h = holding(t.stockId());
			switch (t.type()) {
				case OPENING, BUY -> {
					h.shares += t.shares();
					h.cost = h.cost.add(t.amount());
					BigDecimal loanTotal = BigDecimal.ZERO;
					for (Map.Entry<Long, BigDecimal> e : t.loanAllocations().entrySet()) {
						h.loanTags.merge(e.getKey(), e.getValue(), BigDecimal::add);
						if (t.amount().signum() > 0) {
							h.loanShares.merge(e.getKey(), share(BigDecimal.valueOf(t.shares()), e.getValue(), t.amount()),
									BigDecimal::add);
						}
						BigDecimal left = undeployed.merge(e.getKey(), e.getValue().negate(), BigDecimal::add);
						lowest.merge(e.getKey(), left, BigDecimal::min);
						loanTotal = loanTotal.add(e.getValue());
					}
					h.own = h.own.add(t.amount().subtract(loanTotal));
				}
				case STOCK_DIVIDEND -> {
					if (h.shares > 0) {
						BigDecimal factor = BigDecimal.valueOf(h.shares + t.shares()).divide(BigDecimal.valueOf(h.shares),
								SCALE * 2, RoundingMode.HALF_UP);
						h.loanShares.replaceAll((loan, count) -> scale(count.multiply(factor)));
					}
					h.shares += t.shares();
				}
				case SELL -> sell(h, t);
			}
		}

		private void sell(Holding h, TradeEvent t) {
			if (t.shares() > h.shares) {
				throw new IllegalArgumentException("賣出 " + t.shares() + " 股超過持有的 " + h.shares + " 股");
			}
			BigDecimal ratio = BigDecimal.valueOf(t.shares()).divide(BigDecimal.valueOf(h.shares), SCALE * 2,
					RoundingMode.HALF_UP);
			BigDecimal costOfSold = t.shares() == h.shares ? h.cost : scale(h.cost.multiply(ratio));
			BigDecimal gain = t.amount().subtract(costOfSold);
			BigDecimal held = BigDecimal.valueOf(h.shares);
			for (Map.Entry<Long, BigDecimal> e : h.loanTags.entrySet()) {
				// 借款的已實現損益 ＝ 實際入帳 × 借款股數 ÷ 持股 − 借款成本中賣掉的部分（DR-045）
				BigDecimal loanShares = h.loanShares.getOrDefault(e.getKey(), BigDecimal.ZERO);
				BigDecimal tagSold = t.shares() == h.shares ? e.getValue() : scale(e.getValue().multiply(ratio));
				realized.merge(e.getKey(), share(t.amount(), loanShares, held).subtract(tagSold), BigDecimal::add);
				e.setValue(e.getValue().subtract(tagSold));
			}
			h.loanShares.replaceAll((loan, count) -> t.shares() == h.shares ? BigDecimal.ZERO
					: count.subtract(scale(count.multiply(ratio))));
			h.own = t.shares() == h.shares ? BigDecimal.ZERO : h.own.subtract(scale(h.own.multiply(ratio)));
			h.cost = h.cost.subtract(costOfSold);
			h.shares -= t.shares();
			sales.add(new RealizedSale(t.id(), t.stockId(), t.date(), t.shares(), costOfSold, t.amount(), gain));
		}

		void dividend(DividendIncome d) {
			Holding h = holding(d.stockId());
			h.dividends = h.dividends.add(d.amount());
			if (h.shares <= 0) {
				return;
			}
			// 借款分到的配息 ＝ 配息 × 借款股數 ÷ 持股（DR-045）
			BigDecimal held = BigDecimal.valueOf(h.shares);
			h.loanShares.forEach((loan, count) -> loanDividends.merge(loan, share(d.amount(), count, held),
					BigDecimal::add));
		}

		void loanFlow(LoanFlow f) {
			if (f.delta().signum() >= 0) {
				undeployed.merge(f.loanId(), f.delta(), BigDecimal::add);
				return;
			}
			BigDecimal repay = f.delta().negate();
			BigDecimal available = undeployed.getOrDefault(f.loanId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
			BigDecimal fromUndeployed = repay.min(available);
			undeployed.merge(f.loanId(), fromUndeployed.negate(), BigDecimal::add);
			BigDecimal rest = repay.subtract(fromUndeployed);
			BigDecimal deployed = deployed(f.loanId());
			if (rest.signum() <= 0 || deployed.signum() <= 0) {
				return;
			}
			BigDecimal convert = rest.min(deployed);
			for (Holding h : holdings.values()) {
				BigDecimal tag = h.loanTags.getOrDefault(f.loanId(), BigDecimal.ZERO);
				if (tag.signum() <= 0) {
					continue;
				}
				BigDecimal moved = share(convert, tag, deployed);
				BigDecimal count = h.loanShares.getOrDefault(f.loanId(), BigDecimal.ZERO);
				h.loanShares.put(f.loanId(), count.subtract(share(count, moved, tag)));
				h.loanTags.put(f.loanId(), tag.subtract(moved));
				h.own = h.own.add(moved);
			}
		}

		BigDecimal deployed(Long loanId) {
			return holdings.values().stream()
				.map(h -> h.loanTags.getOrDefault(loanId, BigDecimal.ZERO))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		}

		LoanUsage usage(Long loanId) {
			return new LoanUsage(undeployed.getOrDefault(loanId, BigDecimal.ZERO), deployed(loanId),
					realized.getOrDefault(loanId, BigDecimal.ZERO), loanDividends.getOrDefault(loanId, BigDecimal.ZERO),
					lowest.getOrDefault(loanId, BigDecimal.ZERO).min(undeployed.getOrDefault(loanId, BigDecimal.ZERO)));
		}

	}

	/** amount × part ÷ whole，保留 10 位。 */
	private static BigDecimal share(BigDecimal amount, BigDecimal part, BigDecimal whole) {
		return amount.multiply(part).divide(whole, SCALE, RoundingMode.HALF_UP);
	}

	private static BigDecimal scale(BigDecimal value) {
		return value.setScale(SCALE, RoundingMode.HALF_UP);
	}

}
