package com.sideproject.stockledger.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sideproject.stockledger.dto.PageNotice;
import com.sideproject.stockledger.entity.AppUser;
import com.sideproject.stockledger.entity.Loan;
import com.sideproject.stockledger.entity.OnboardingStep;
import com.sideproject.stockledger.repository.AppUserRepository;
import com.sideproject.stockledger.repository.LoanRepository;
import com.sideproject.stockledger.repository.PledgeCollateralRepository;
import com.sideproject.stockledger.security.CurrentUser;

/**
 * 總覽、股票、資金來源頁標題下方的提醒（設計文件 3.3）。
 * 只依賴 Repository、CurrentUser 與 PledgeStatusService（它不依賴任何呼叫 NoticeService 的 Service），不依賴其他 Service：
 * OverviewService、TradeService、LoanService 都會呼叫它，
 * 依賴其他 Service 會造成循環依賴。
 */
@Service
public class NoticeService {

	private final AppUserRepository users;
	private final LoanRepository loans;
	private final PledgeCollateralRepository collaterals;
	private final PledgeStatusService pledgeStatus;
	private final CurrentUser currentUser;

	public NoticeService(AppUserRepository users, LoanRepository loans, PledgeCollateralRepository collaterals,
			PledgeStatusService pledgeStatus, CurrentUser currentUser) {
		this.users = users;
		this.loans = loans;
		this.collaterals = collaterals;
		this.pledgeStatus = pledgeStatus;
		this.currentUser = currentUser;
	}

	/** 總覽頁：只有引導提醒。 */
	@Transactional(readOnly = true)
	public List<PageNotice> overviewNotices() {
		List<PageNotice> result = new ArrayList<>();
		addOnboarding(result);
		return result;
	}

	/** 股票與資金來源頁：引導提醒，加上每筆沒有擔保股票、還在質押中（沒作廢、沒還清）的質押。 */
	@Transactional(readOnly = true)
	public List<PageNotice> stockAndFundingNotices() {
		List<PageNotice> result = new ArrayList<>();
		addOnboarding(result);
		Long userId = currentUser.id();
		Set<Long> active = pledgeStatus.activePledgeLoanIds();
		for (Loan loan : loans.findByUserIdAndVoidedFalseOrderByIdAsc(userId)) {
			if (active.contains(loan.getId()) && collaterals.findByUserIdAndLoanId(userId, loan.getId()).isEmpty()) {
				result.add(new PageNotice("「" + loan.getName() + "」還沒設定擔保股票，維持率無法計算",
						"/funding#loan-" + loan.getId()));
			}
		}
		return result;
	}

	private void addOnboarding(List<PageNotice> result) {
		AppUser me = users.findById(currentUser.id()).orElseThrow(() -> new NotFoundException("找不到使用者"));
		if (me.getOnboardingStep() != OnboardingStep.DONE) {
			// 回到走到的那一步（設計文件 3.3），不是從歡迎頁重來
			result.add(new PageNotice("第一次使用引導還有沒完成的步驟", me.getOnboardingStep().url()));
		}
	}

}
