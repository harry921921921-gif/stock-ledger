# 借款全欄位可修改 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 借款的每個欄位（名稱、銀行、還款方式、是否股票質押、撥款日、借款金額、原始利率、扣款金額、扣款日、期數、到期日、往來帳戶、開辦費）隨時都能改；系統跟著更新撥款與自動記的每期紀錄，使用者手改過的期數保留並提醒；借款改小不夠付股票時先提示，確定後差額改算自有。

**Architecture:** `LoanService.edit` 改成「整筆重新套用合約」：先驗證，再更新 `Loan`、撥款／開辦費現金異動、利率版本，接著由 `LoanPostingService.reconcile` 依新合約對照已記的每期紀錄（沒手改的就地改金額日期、多出來的作廢、缺的補記），最後檢查借款是否仍足以支付股票（DR-044）。不夠時第一次呼叫丟出帶調整清單的 `LoanShortfallException`，使用者確認後帶 `confirmShortfall=true` 再呼叫，由 `TradeService.reduceLoanAllocations` 從最後買的開始把借款分攤往下調並寫 trade_revision。

**Tech Stack:** Java 21、Spring Boot、Spring Data JPA、Thymeleaf、MySQL 8.4（Testcontainers）、JUnit 5、AssertJ、MockMvc。

**Spec:** `docs/superpowers/specs/2026-10-25-onboarding-design.md` 第 7a 節第 6 點（DR-051）；相關既有規格 `docs/spec.md` 4.8a、DR-038、DR-044、DR-047、DR-048。

## Global Constraints

- 套件 `com.sideproject.stockledger`；Controller 不碰 Repository、不計算；計算放 Service 或純 Java Calculator（有單元測試）。
- 金額 `BigDecimal`，每次除法、`setScale` 指定 `RoundingMode`；利息 `UP`、中間值 10 位 `HALF_UP`。
- 每個 Repository 查詢都帶 `currentUser.id()`；不接受畫面傳來的 user_id。
- 現金異動、交易不硬刪除：改用 `change(...)`／`markVoided()`，每個改到的欄位寫 `cash_movement_revision`／`trade_revision`。
- 系統自動寫的修改原因一律用常數 `LoanService.EDIT_REASON = "借款資料修改後重算"`。
- 不需要新的 Flyway migration（`updatable = false` 只是 JPA 設定，拿掉不改 schema）。
- 測試一律 `./mvnw clean test`（VS Code 的 Java 外掛會把壞 class 編進 target）；開始前 544＋2＝546 個測試全過。
- 使用者看得到的錯誤與提示用繁體中文，金額用 `MoneyFormatter.plain`。

## Review Focus

1. 只改名稱或銀行（合約沒變）：不得改到任何現金異動、不寫多餘的修改紀錄、不跳「你改過的期數」提醒 → Task 4 `editNameOnlyTouchesNoMovement`。
2. 使用者手改過某一期利息後，再改利率：那一期金額不變，且出現在保留清單；其他期跟著新利率 → Task 4 `keepsUserEditedPeriods`。
3. 每月固定還款改成到期一次還本金＋利息：所有沒手改的自動還本與利息作廢，之後不再自動記 → Task 4 `changeMethodToBulletVoidsAutoPeriods`。
4. 借款改小但有股票用它：不帶確認時不得寫入任何資料（整個交易回滾）→ Task 5 `shortfallWithoutConfirmChangesNothing`。
5. 別人的借款 id：修改回 404，不洩漏是否存在 → Task 6 `editOthersLoanIsNotFound`。

---

### Task 1: 規格書 DR-051 與設計文件定案

**Files:**
- Modify: `docs/spec.md`（版本說明加 v1.12；4.8a 加第 9 點；第 8 節加 DR-051 在 DR-049 上方；DR-044 的「放棄的選項」欄不動，在「決定」欄尾加「（v1.12 借款改小見 DR-051）」）

- [ ] **Step 1: 寫入規格**

4.8a 第 9 點與 DR-051 的內容照設計文件 7a-6 原文整理：借款每個欄位隨時可改；改了合約資料，撥款與開辦費跟著改、自動記的期數沒手改的重算、手改的保留並列出；還款方式可改；修改原始利率與新增利率版本分開；借款改小不夠付股票時先提示，確定後從最後買的交易開始把借款出的錢往下調、差額算自有，不另外扣帳戶的錢。DR-051 原因欄：「使用者要求所有資料都能改（2026-10-28）；撥款日、金額從建立起就鎖住，打錯沒有出路」；放棄的選項：「作廢再重建（留下作廢紀錄）；只在引導期間可改；不夠付股票直接擋下」。

- [ ] **Step 2: 給使用者看 commit 訊息，同意後 commit**

```bash
git add docs/spec.md docs/superpowers/specs/2026-10-25-onboarding-design.md docs/superpowers/mockups/onboarding.html docs/superpowers/plans/
git commit -m "docs: 第一次使用引導設計與借款全欄位可修改（v1.12、DR-051）"
```

---

### Task 2: 到期日規則（正式頁一起改）

**Files:**
- Modify: `src/main/java/com/sideproject/stockledger/service/LoanService.java`（`validate` 約第 797 行；新增 `dueWarning`）
- Modify: `src/main/java/com/sideproject/stockledger/controller/FundingController.java`（新增與修改後顯示提醒，比照 `paymentWarning` 的用法）
- Test: `src/test/java/com/sideproject/stockledger/service/LoanRepaymentServiceTest.java`

**Interfaces:**
- Produces: `public String dueWarning(LoanForm form)`（`@Transactional(readOnly = true)`；沒提醒回 `null`）；`LoanValidationException` 訊息 `"到期日要晚於撥款日"`。

- [ ] **Step 1: 寫失敗的測試**

```java
@Test
void dueDateMustBeAfterStartDate() {
	assertThatThrownBy(() -> loans.create(bullet(today.minusDays(10), today.minusDays(10))))
		.hasMessage("到期日要晚於撥款日");
}

@Test
void pastDueDateOnlyWarns() {
	LoanForm form = bullet(today.minusDays(100), today.minusDays(1));
	assertThat(loans.dueWarning(form))
		.isEqualTo("到期日 " + today.minusDays(1) + " 已經過了。如果已經還清，這筆就不用記；如果有展延，請填展延後的到期日。");
	assertThat(loans.create(form).getId()).isNotNull();
	assertThat(loans.dueWarning(bullet(today.minusDays(100), today.plusDays(30)))).isNull();
}
```

`bullet(start, due)` 是測試裡的小工具：到期一次還本金＋利息、金額 100000、利率 4、往來帳戶用 setUp 建的帳戶。

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw clean test -Dtest=LoanRepaymentServiceTest`
Expected: 兩個新測試 FAIL（第一個訊息是「到期日不能早於起算日」或沒丟例外；第二個 `dueWarning` 不存在，編譯失敗）

- [ ] **Step 3: 實作**

`validate` 的到期日檢查改成 `!form.dueDate().isAfter(form.startDate())` 就丟 `"到期日要晚於撥款日"`，每月固定還款有填到期日時也檢查。`dueWarning` 只看 `form.dueDate()` 是否早於 `today()`，其他還款方式（沒有到期日）回 `null`。FundingController 新增、修改成功後把 `dueWarning` 放進 flash attribute `warning`，畫面沿用現有顯示 `paymentWarning` 的位置。

- [ ] **Step 4: 跑測試確認通過**

Run: `./mvnw clean test -Dtest=LoanRepaymentServiceTest,LoanServiceTest,FundingControllerTest`
Expected: PASS（若舊測試用「到期日等於起算日」當合法資料，先停下來問使用者，不要改預期值）

- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 3: Loan 可以改還款方式、類型與起點

**Files:**
- Modify: `src/main/java/com/sideproject/stockledger/entity/Loan.java`（拿掉 `loan_type`、`repayment_method`、`disburse_account_id` 的 `updatable = false`；新增方法）
- Test: `src/test/java/com/sideproject/stockledger/service/LoanServiceTest.java`

**Interfaces:**
- Produces: `public void reviseContract(LoanType type, RepaymentMethod method, LocalDate startDate, BigDecimal startPrincipal, Long disburseAccountId, Integer remainingPeriods, YearMonth firstPeriod)`；起點或方式改了，`postingFrom` 設成 `YearMonth.from(startDate)`（從撥款月起重新對照，不是從今天）。`origin` 維持不能改（V1.9 起都是 NEW；EXISTING 的舊借款沿用原值）。

- [ ] **Step 1: 寫失敗的測試**（直接存取 repository 驗證欄位真的寫進資料庫）

```java
@Test
void reviseContractPersistsMethodAndType() {
	Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
	transactionTemplate.executeWithoutResult(s -> loanRepository.findForUpdate(loan.getId(), userId).orElseThrow()
		.reviseContract(LoanType.PLEDGE, RepaymentMethod.BULLET, today.minusMonths(2), new BigDecimal("300000"),
				loan.getDisburseAccountId(), null, null));
	Loan reloaded = loanRepository.findByIdAndUserId(loan.getId(), userId).orElseThrow();
	assertThat(reloaded.getRepaymentMethod()).isEqualTo(RepaymentMethod.BULLET);
	assertThat(reloaded.getType()).isEqualTo(LoanType.PLEDGE);
	assertThat(reloaded.getStartDate()).isEqualTo(today.minusMonths(2));
	assertThat(reloaded.getPostingFrom()).isEqualTo(YearMonth.from(today.minusMonths(2)));
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw clean test -Dtest=LoanServiceTest#reviseContractPersistsMethodAndType`
Expected: 編譯失敗（沒有 `reviseContract`）

- [ ] **Step 3: 實作 `Loan.reviseContract`**；既有 `changeStart` 改成呼叫它或刪掉（`LoanService.edit` 是唯一呼叫者，Task 4 會改寫）。

- [ ] **Step 4: 跑測試確認通過**

Run: `./mvnw clean test -Dtest=LoanServiceTest`
Expected: PASS

- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 4: 修改借款時重新對照撥款與每期紀錄

**Files:**
- Modify: `src/main/java/com/sideproject/stockledger/service/LoanService.java`（改寫 `edit`；新增 `EDIT_REASON`；新增 `correctRate` 私有方法）
- Modify: `src/main/java/com/sideproject/stockledger/service/LoanPostingService.java`（新增 `reconcile`；`postCredit`／`postPledge` 改成先算「應有的期數清單」再套用，`postDue` 與 `reconcile` 共用）
- Modify: `src/main/java/com/sideproject/stockledger/service/CashMovementService.java`（新增 `followLoan`）
- Create: `src/main/java/com/sideproject/stockledger/dto/LoanEditResult.java`
- Test: `src/test/java/com/sideproject/stockledger/service/LoanEditTest.java`（新檔，setUp 比照 `LoanRepaymentServiceTest`）

**Interfaces:**
- Consumes: Task 3 的 `Loan.reviseContract`。
- Produces:
  - `public LoanEditResult edit(Long id, LoanForm form, boolean confirmShortfall)`；舊的 `edit(Long, LoanForm)` 刪掉，呼叫端（FundingController、測試）改用 `edit(id, form, false)`。
  - `public record LoanEditResult(Loan loan, List<KeptPeriod> kept)`；`public record KeptPeriod(Long movementId, YearMonth period, CashMovementType type, BigDecimal amount)`（巢狀在 `LoanEditResult` 裡）。
  - `LoanPostingService`：`record Planned(CashMovementType type, YearMonth period, LocalDate date, BigDecimal amount)`；`public List<CashMovement> reconcile(Loan loan, LocalDate today, Set<Long> userEdited)`，回傳「手改過、而且和新合約算出來不同」的那幾筆。
  - `CashMovementService`：`public void followLoan(CashMovement m, LocalDate date, Long accountId, BigDecimal amount, String reason)`（由 LoanService／LoanPostingService 呼叫，借款已鎖住；用 `change` ＋ `writeEditRevisions`，金額日期帳戶都沒變就什麼都不寫）。
  - 「手改過」的判定：`cashMovementRevisionRepository.findRevisedMovementIds(userId)` 裡有這筆，而且那些修改紀錄的原因不是 `EDIT_REASON`（系統自己重算寫的不算使用者改過）。在 `CashMovementRevisionRepository` 新增 `@Query` 方法 `List<Long> findUserEditedMovementIds(Long userId, String systemReason)`。

`reconcile` 的規則（每一種 `LOAN_PRINCIPAL`／`LOAN_INTEREST`，以期別配對）：
1. 新合約有、已記也有、沒手改 → `followLoan` 改成新的日期、帳戶（往來帳戶）、金額。
2. 新合約有、已記也有、手改過 → 不動；金額或日期和新合約不同就放進回傳清單。
3. 新合約沒有、已記有、沒手改 → `markVoided` 並寫 `CashMovementRevision.VOID`（原因 `EDIT_REASON`）。手改過的照規則 2 保留並列出。
4. 新合約有、還沒記 → 照現在 `post` 補記（已作廢的同一期不補，維持 DR-040）。
質押（每月繳息）計息起點用「上一期應有的繳息日」，手改過而保留的那期用它實際的日期。

`edit` 的順序：鎖借款 → 驗證（沿用 `normalize`＋`validate`，還款方式用 `form.repaymentMethod()`，pledge 由 `form.type()` 決定）→ `loan.reviseContract`＋`loan.change` → 撥款那筆（`LOAN_DISBURSEMENT` 且 period 為 null 的第一筆）`followLoan` 到新撥款日、金額、往來帳戶 → 開辦費：原本有、新的是 0 或空 → 作廢；原本沒有、新的有 → 新增；都有 → `followLoan` → 原始利率改了：`rates.save(RateSetting.loanRate(userId, loanId, newRate, startDate, now))`（同生效日、id 較大的優先，見 `EffectiveRateSelector`；扣款金額同理用 `RateSetting.loanPayment`）→ `checkSchedule`（每月固定還款）→ `posting.reconcile` → 借款是否足夠（Task 5）。只改名稱、銀行、到期日、扣款日、帳戶時，`reconcile` 仍會跑，但金額日期沒變就不寫任何紀錄。

- [ ] **Step 1: 寫失敗的測試**

```java
@Test
void editNameOnlyTouchesNoMovement() {
	Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
	int revisionsBefore = count("cash_movement_revision");
	LoanEditResult r = loans.edit(loan.getId(), withName(loans.form(loan.getId()), "土銀信貸（改名）"), false);
	assertThat(r.loan().getName()).isEqualTo("土銀信貸（改名）");
	assertThat(r.kept()).isEmpty();
	assertThat(count("cash_movement_revision")).isEqualTo(revisionsBefore);
}

@Test
void editAmountFollowsDisbursementAndReposts() {
	Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));          // 400000、2.15%、84 期、月付 5133
	loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "300000", "3850"), false);
	assertThat(amountOf(loan.getId(), CashMovementType.LOAN_DISBURSEMENT)).isEqualByComparingTo("300000");
	assertThat(firstInterest(loan.getId())).isEqualByComparingTo(
			new BigDecimal("300000").multiply(new BigDecimal("0.0215")).divide(BigDecimal.valueOf(12), 0, RoundingMode.UP));
	assertThat(revisionReasons(loan.getId())).containsOnly(LoanService.EDIT_REASON);
}

@Test
void keepsUserEditedPeriods() {
	Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
	CashMovement first = firstMovement(loan.getId(), CashMovementType.LOAN_INTEREST);
	cashMovements.editLoanMovement(first.getId(), new LoanMovementForm(first.getMovementDate(), new BigDecimal("700")), "照銀行");
	LoanEditResult r = loans.edit(loan.getId(), withRate(loans.form(loan.getId()), "3"), false);
	assertThat(amountOf(first.getId())).isEqualByComparingTo("700");
	assertThat(r.kept()).extracting(LoanEditResult.KeptPeriod::movementId).containsExactly(first.getId());
}

@Test
void changeMethodToBulletVoidsAutoPeriods() {
	Loan loan = loans.create(monthly("土銀信貸", today.minusMonths(3)));
	loans.edit(loan.getId(), withMethod(loans.form(loan.getId()), RepaymentMethod.BULLET, today.plusYears(1)), false);
	assertThat(activeCount(loan.getId(), CashMovementType.LOAN_PRINCIPAL)).isZero();
	assertThat(activeCount(loan.getId(), CashMovementType.LOAN_INTEREST)).isZero();
	assertThat(postingService.postDue(loanRepository.findByIdAndUserId(loan.getId(), userId).orElseThrow(), today)).isZero();
}

@Test
void correctingRateUsesNewRateFromStart() {
	Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(2)));   // 4%
	loans.edit(loan.getId(), withRate(loans.form(loan.getId()), "4.25"), false);
	assertThat(loans.currentRate(loanRepository.findByIdAndUserId(loan.getId(), userId).orElseThrow(), loan.getStartDate()))
		.isEqualByComparingTo("0.0425");
}
```

小工具（`monthly`、`bulletPledge`、`withName`、`withAmount`、`withRate`、`withMethod`、`count`、`amountOf`、`firstInterest`、`firstMovement`、`activeCount`、`revisionReasons`）寫在測試類別裡，用 `JdbcTemplate` 查資料。

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw clean test -Dtest=LoanEditTest`
Expected: 編譯失敗（`edit(id, form, boolean)`、`LoanEditResult` 不存在）

- [ ] **Step 3: 實作上面的 Interfaces 與規則**；`LoanFactsReader` 讀利率時若同一生效日有多筆，只留 id 最大的（若現在不是這樣，一併修並在 `correctingRateUsesNewRateFromStart` 之外補一個 `LoanFactsReader` 的測試）。

- [ ] **Step 4: 跑測試確認通過**

Run: `./mvnw clean test -Dtest=LoanEditTest,LoanServiceTest,LoanPostingServiceTest,LoanRepaymentServiceTest,FundingStocksTest`
Expected: PASS；舊測試裡「起點欄位不能改」（訊息 `LOCKED`）的斷言是被 DR-051 推翻的規則 → 先列給使用者看、同意後才改那幾個測試的預期，並刪掉 `LOCKED` 常數。

- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 5: 借款改小、不夠付股票時先提示，確定後改算自有

**Files:**
- Create: `src/main/java/com/sideproject/stockledger/service/LoanShortfallException.java`
- Modify: `src/main/java/com/sideproject/stockledger/service/TradeService.java`（新增 `shortfallAdjustments`、`reduceLoanAllocations`）
- Modify: `src/main/java/com/sideproject/stockledger/service/LoanService.java`（`edit` 最後一步）
- Test: `src/test/java/com/sideproject/stockledger/service/LoanEditTest.java`

**Interfaces:**
- Produces:
  - `public record AllocationCut(Long tradeId, LocalDate date, String stockLabel, BigDecimal from, BigDecimal to)`（放 `dto` 套件）。
  - `public class LoanShortfallException extends LoanValidationException`，欄位 `BigDecimal shortfall`、`List<AllocationCut> cuts`；訊息 `"借款金額小於已經用它買股票的金額，差 " + money.plain(shortfall) + " 元"`。
  - `TradeService`：`List<AllocationCut> shortfallAdjustments(Long loanId)`（從交易日最晚、同日 id 最大的交易開始，把這筆借款的分攤往下調，每調一筆就重算 `lowestUndeployed`，到不再小於 0 為止；只算不存）；`void reduceLoanAllocations(Long loanId, List<AllocationCut> cuts, String reason)`（改 `source_allocation`、寫 `trade_revision` 欄位 `allocation`，沿用 `edit` 裡的 `allocationText` 快照方式）。
- `LoanService.edit`：`reconcile` 之後若 `portfolioReader.uncoveredLoan` 有值 → `confirmShortfall=false` 時丟 `LoanShortfallException`（整個 `@Transactional` 回滾）；`true` 時 `reduceLoanAllocations(..., EDIT_REASON)` 後再檢查一次，仍不夠（例如還有別筆借款的問題）就丟原本的 `LoanValidationException`。

- [ ] **Step 1: 寫失敗的測試**

```java
@Test
void shortfallWithoutConfirmChangesNothing() {
	Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(2)));             // 100000
	buyWithLoan(loan, today.minusMonths(1), "0050", 1000, "73.5", "73500");
	assertThatThrownBy(() -> loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "50000", null), false))
		.isInstanceOfSatisfying(LoanShortfallException.class, ex -> {
			assertThat(ex.getMessage()).isEqualTo("借款金額小於已經用它買股票的金額，差 23,500 元");
			assertThat(ex.cuts()).singleElement().satisfies(c -> {
				assertThat(c.from()).isEqualByComparingTo("73500");
				assertThat(c.to()).isEqualByComparingTo("50000");
			});
		});
	assertThat(amountOf(loan.getId(), CashMovementType.LOAN_DISBURSEMENT)).isEqualByComparingTo("100000");
}

@Test
void confirmedShortfallMovesDifferenceToOwnMoney() {
	Loan loan = loans.create(bulletPledge("玉山質押", today.minusMonths(2)));
	Long older = buyWithLoan(loan, today.minusMonths(1), "0050", 500, "70", "35000");
	Long newer = buyWithLoan(loan, today.minusDays(5), "0050", 500, "76", "38000");
	loans.edit(loan.getId(), withAmount(loans.form(loan.getId()), "50000", null), true);
	assertThat(loanAllocation(older, loan)).isEqualByComparingTo("35000");
	assertThat(loanAllocation(newer, loan)).isEqualByComparingTo("15000");
	assertThat(tradeRevisionReasons(newer)).containsExactly(LoanService.EDIT_REASON);
	assertThat(settlementBalanceChangedBy(loan)).isEqualByComparingTo("0");   // 不另外扣帳戶的錢
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw clean test -Dtest=LoanEditTest`
Expected: 兩個新測試 FAIL（沒有 `LoanShortfallException`）

- [ ] **Step 3: 實作**

- [ ] **Step 4: 跑測試確認通過**

Run: `./mvnw clean test -Dtest=LoanEditTest,FundingStocksTest,TradeServiceTest`
Expected: PASS

- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 6: 資金來源頁的修改畫面

**Files:**
- Modify: `src/main/java/com/sideproject/stockledger/controller/FundingController.java`（`GET/POST /funding/{id}/edit`；新增 `POST /funding/{id}/edit/confirm-shortfall`）
- Modify: `src/main/resources/templates/funding/loan-form.html`（修改時顯示所有欄位，和新增一樣；還款方式、股票質押可改；撥款日、金額不再唯讀；說明文字「改了合約資料，系統會重算自動記的每期紀錄；你改過的期數不會動」）
- Create: `src/main/resources/templates/funding/shortfall-confirm.html`（列出差額與每筆交易「日期、股票、借款出的錢 from → to」，按鈕「確定修改」「取消」；表單把原本送出的欄位用 hidden 帶回去）
- Test: `src/test/java/com/sideproject/stockledger/controller/FundingControllerTest.java`

**Interfaces:**
- Consumes: Task 4 `edit(id, form, confirmShortfall)` 與 `LoanEditResult.kept()`；Task 5 `LoanShortfallException`。
- 成功後轉回 `/funding`，flash `kept`（`List<KeptPeriod>`，畫面列「這幾期你改過，沒有自動重算，請確認：2026-08 借款利息 700 元」）與 Task 2 的 `warning`。

- [ ] **Step 1: 寫失敗的測試**

```java
@Test
void editCanChangeStartDateAndAmount() throws Exception { /* POST /funding/{id}/edit 帶新的 startDate、startPrincipal → 302 /funding；資料庫撥款金額是新值 */ }

@Test
void shortfallShowsConfirmPage() throws Exception { /* 借款被股票用掉後改小 → 200，view "funding/shortfall-confirm"，model 有 cuts；資料庫沒變 */ }

@Test
void confirmShortfallSaves() throws Exception { /* POST /funding/{id}/edit/confirm-shortfall → 302 /funding；交易分攤已調整 */ }

@Test
void editOthersLoanIsNotFound() throws Exception { /* 用 fs-ben 登入 POST 別人的 id → 404 */ }
```

（MockMvc 寫法比照同檔既有測試：`.session(session).with(csrf())`、`param(...)`。）

- [ ] **Step 2: 跑測試確認失敗**

Run: `./mvnw clean test -Dtest=FundingControllerTest`
Expected: 新測試 FAIL

- [ ] **Step 3: 實作**

- [ ] **Step 4: 跑測試確認通過，再跑全部**

Run: `./mvnw clean test`
Expected: 全部 PASS（數量 = 546 ＋ 本計畫新增）

- [ ] **Step 5: 重建 Docker 讓使用者試**

Run: `docker compose up -d --build`，用 demo 帳號在資金來源頁改一筆借款的撥款日與金額，截圖給使用者。

- [ ] **Step 6: 給使用者看 commit 訊息，同意後 commit**
