# 階段三：資金來源（信貸與質押）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **本專案額外規則（CLAUDE.md）：** 程式由 Claude 寫；每個 Task 先寫測試再實作，做完派 opus subagent 審查並修正 Critical／Important；問題累積一次給使用者看；**commit 前給使用者看並經同意，絕不 `git push`**；V9 套用到使用者的 Docker 資料庫前先問；瀏覽器驗收用測試帳號 `demo`（先確認左下角使用者）。

**Goal：** 使用者在「資金來源」頁建立土地銀行信貸與玉山質押（既有借款），系統每月自動記信貸的還本與利息、質押的繳息，利息依應計制計入本月淨資產變動，資金來源頁顯示總負債、平均利率、本月利息與每筆借款。

**Architecture：** 新表 `loan` 存設定（不是事件，修改直接更新、不寫修改紀錄）。撥款、還本、利息是 `cash_movement` 的三種新類型，帶 `loan_id` 與期別，資料庫保證同一借款同一類型同一期最多一筆沒作廢的。利率是 `rate_setting` 綁 `loan_id` 的「借款年利率」版本。剩餘本金、攤還、應計利息都由純 Java Calculator 從事件推算。自動記帳排程 `LoanPostingService` 和固定收支一樣在啟動時與每天 00:10 執行（DR-036 的例外）。

**Tech Stack：** Java 21、Spring Boot、Thymeleaf、MySQL 8.4、Flyway、JUnit 5、Testcontainers。

**Spec：** `docs/superpowers/specs/2026-10-22-funding-sources-design.md`（全部，特別是第 11 節）；`docs/spec.md` 4.1、4.3、4.8、5.3、DR-008、DR-009、DR-018、DR-036、DR-037；設計稿 https://claude.ai/artifact/WHJdwUeLPEoAYjHKAtiuXx 的 `Funding`（只做目前有資料的部分）。

## Global Constraints

- Controller 不呼叫 Repository；Controller 與模板不做計算；使用者操作的查詢一律帶 `CurrentUser.id()`，排程只用每筆借款自己的 `user_id`（DR-036）。
- 金額 `BigDecimal`、`DECIMAL(19,4)`、現金只收整數元；利率 `DECIMAL(19,6)`（0.025 = 2.5%）；中間值 scale 10 `HALF_UP`；**利息 `UP` 到元**（DR-009）；**月付金 `HALF_UP` 到元**（設計第 4 節）。
- 利率只從 `rate_setting` 依生效日期取得（CLAUDE.md）。
- Schema 只新增 `V9__create_loan.sql`；V1～V8 不可修改。CHECK 遇 NULL 會通過，NULL 欄位的條件要寫 `IS NOT NULL`。
- 不硬刪除；撥款、還本、利息照 spec 4.9 寫修改紀錄；借款設定修改不寫。
- 不新增任何套件。

## 設計對應（實作時照這個做，不再問）

| 設計 | 做法 |
| --- | --- |
| 信貸月付金固定 | 只在利率版本生效、提前還本後重算；最後一期本金 ＝ 剩餘本金 |
| 信貸提前還本 | 下一期利息用還本後的剩餘本金；剩餘期數不變 |
| 質押計息 | 每天：餘額 × 當天利率 ÷ 365；期間 ＝ 上一筆實際繳息日（第一期為起算日）到這次繳息日，算頭不算尾；期間加總後 `UP` 到元 |
| 質押還清 | 剩餘本金變 0 的那天自動記最後一段利息 |
| 利息計入淨資產變動 | 已記繳息的期間用實際金額；還沒繳的天數用應計（質押按日、信貸當月那期算在扣款月）；`LOAN_INTEREST` 現金異動本身不再算一次 |
| 設定修改 | 起算日、起算本金、剩餘期數、類型：有自動記帳後不能改；扣款日、帳戶：從當月起生效（`posting_from`，同 DR-037） |
| 是否還清 | 剩餘本金 ＝ 0，不另存 |

## Review Focus

1. **和銀行對不上**：月付金、每期利息、最後一期 → Task 1 用設計數字（40 萬、2.5%、60 期 → 月付金 7,099、第 1 期利息 834、第 60 期本金 7,110）。
2. **利息算兩次或撥款被算成收入**：本月淨資產變動只算一次利息；撥款與還本不算 → Task 5 `monthlyChangeCountsInterestOnce`、`disbursementAndPrincipalNotCounted`。
3. **同一期記兩次、作廢後又冒出來** → Task 4 `postingTwiceCreatesOnce`、`voidedInstallmentNotRegenerated`。
4. **期中還本／動撥後的利息** → Task 1 `pledgeBalanceChangeMidPeriod`、Task 4 `pledgePayoffPostsFinalInterest`。
5. **看到或改到別人的借款** → Task 3 `otherUsersLoanIsNotFound`、Task 6 `otherUsersLoanIs404`。

---

### Task 1：借款 Calculator（純 Java）

**Files：**
- Create: `calculator/InstallmentCalculator.java`、`calculator/Installment.java`、`calculator/RateChange.java`、`calculator/PrincipalChange.java`、`calculator/DailyInterestCalculator.java`、`calculator/LoanRateCalculator.java`
- Test: `InstallmentCalculatorTest`、`DailyInterestCalculatorTest`、`LoanRateCalculatorTest`

**Interfaces：**
- `record RateChange(LocalDate effectiveDate, BigDecimal annualRate)`；`record PrincipalChange(LocalDate date, BigDecimal delta)`（還本為負、動撥為正）
- `record Installment(int number, YearMonth period, LocalDate dueDate, BigDecimal principal, BigDecimal interest, BigDecimal remainingAfter)`
- `InstallmentCalculator.monthlyPayment(BigDecimal principal, BigDecimal annualRate, int periods): BigDecimal`（HALF_UP 到元）
- `InstallmentCalculator.next(BigDecimal remaining, int remainingPeriods, BigDecimal currentPayment, BigDecimal annualRate, boolean recalc): Installment 的本金與利息`（`recalc` 為 true 時先用剩餘本金、剩餘期數重算月付金；`remainingPeriods == 1` 時本金 ＝ remaining）
- `DailyInterestCalculator.interest(BigDecimal openingBalance, List<PrincipalChange> changes, List<RateChange> rates, LocalDate from, LocalDate toExclusive): BigDecimal`（未進位，scale 10）與 `interestRoundedUp(...)`（UP 到元）
- `LoanRateCalculator.weightedAverage(List<BigDecimal[]> principalAndRate): Optional<BigDecimal>`（總本金 0 時 empty；顯示 HALF_UP 到 0.01%）

- [ ] **Step 1：寫測試**
  - `paymentMatchesFormula`：40 萬、2.5%、60 期 → 7,099。
  - `firstAndLastInstallment`：第 1 期利息 834、本金 6,265；照表跑到第 60 期，本金 7,110、利息 15、剩餘 0。
  - `rateChangeRecalculates`：第 13 期起利率改 3%，以第 12 期後剩餘本金與 48 期重算月付金（期待值用 `monthlyPayment(剩餘, 0.03, 48)`）。
  - `prepaymentKeepsPeriodsLowersPayment`：第 12 期後提前還 10 萬 → 下一期 `recalc`，月付金 ＝ `monthlyPayment(剩餘−100000, 0.025, 48)`，利息用還本後的本金。
  - `pledgeThirtyDays`：40 萬、3%、30 天 → 986.30…，UP 後 987。
  - `pledgeBalanceChangeMidPeriod`：10/1～10/31（30 天）、10/16 還 10 萬 → 前 15 天 40 萬、後 15 天 30 萬；當天起用新餘額。
  - `pledgeRateChangeMidPeriod`：10/16 起 3% → 3.2%。
  - `weightedAverage`：40 萬 2.5%、40 萬 3% → 2.75%；全部 0 → empty。
- [ ] **Step 2：** 跑測試 → 編譯失敗。
- [ ] **Step 3：實作。**
- [ ] **Step 4：** 測試通過。

### Task 2：V9 與 Entity

**Files：**
- Create: `db/migration/V9__create_loan.sql`、`entity/Loan.java`、`entity/LoanType.java`（`CREDIT("信貸")`, `PLEDGE("質押")`）、`entity/LoanOrigin.java`（`NEW("新撥款")`, `EXISTING("既有借款")`）、`repository/LoanRepository.java`
- Modify: `entity/CashMovementType.java`（加 `LOAN_DISBURSEMENT("借款撥款")`, `LOAN_PRINCIPAL("借款還本")`, `LOAN_INTEREST("借款利息")`）、`entity/CashMovement.java`（`loanId`；工廠 `ofLoan(Long userId, Loan loan, CashMovementType type, LocalDate date, Long accountId, BigDecimal amount, YearMonth period, LocalDateTime now)`）、`entity/RateKind.java`（`LOAN_ANNUAL_RATE("借款年利率", PERSONAL, RATIO)`，不出現在設定頁的下拉選單）、`entity/RateSetting.java`（`loanId`）、`repository/CashMovementRepository.java`、`repository/RateSettingRepository.java`
- Test: Create `repository/LoanSchemaTest.java`；所有清表的測試在 `recurring_item` 前加 `DELETE FROM loan`（先刪 `cash_movement`、`rate_setting` 中有 loan_id 的列）

**V9 內容：**
- `loan`：`id, user_id, name VARCHAR(50), loan_type, bank VARCHAR(50), original_amount DECIMAL(19,4), origin, start_date DATE, start_principal DECIMAL(19,4), disburse_account_id NULL, pay_account_id, pay_day INT, remaining_periods INT NULL, first_period DATE NULL, due_date DATE NULL, posting_from DATE, created_at`；`UNIQUE(id, user_id)`；兩個帳戶外鍵連同 user_id；CHECK：代碼、金額 > 0 且整數、pay_day 1～31、信貸一定有 remaining_periods（≥1）與 first_period（1 號、不早於起算月），質押一定沒有；新撥款一定有 disburse_account_id，既有借款一定沒有；posting_from 是 1 號。
- `rate_setting` 加 `loan_id BIGINT NULL`，外鍵 `(loan_id, user_id) → loan(id, user_id)`；CHECK：`(kind = 'LOAN_ANNUAL_RATE') = (loan_id IS NOT NULL)`。
- `cash_movement` 加 `loan_id`，外鍵連同 user_id；放寬類型 CHECK 加三種；CHECK：三種借款類型 `loan_id IS NOT NULL` 且金額 > 0，其他類型 `loan_id IS NULL`；借款還本、借款利息自動記的期別放在既有的 `period`；產生欄位 `loan_active_key = CASE WHEN loan_id IS NOT NULL AND voided = FALSE AND period IS NOT NULL THEN CONCAT(loan_id, '-', type) END`，`UNIQUE(loan_active_key, period)`。修改 V8 的 `ck_cash_movement_recurring` 不需要（它只限制 recurring 欄位）。

- [ ] **Step 1：寫 `LoanSchemaTest`**：`validLoansAccepted`、`creditNeedsPeriods`、`pledgeHasNoPeriods`、`existingHasNoDisburseAccount`、`dbRejectsOtherUsersAccountOrLoan`、`loanMovementNeedsLoanId`、`dbRejectsSecondActiveInstallment`（同借款同類型同期兩筆沒作廢 → 擋；作廢後可以；同期不同類型可以）、`loanRateNeedsLoanId`。
- [ ] **Step 2：** 跑測試 → FAIL。
- [ ] **Step 3：** 寫 V9、entity、repository；更新各測試的清表順序。
- [ ] **Step 4：** `./mvnw test` 全部通過。

### Task 3：LoanService（新增、修改、利率、還本、動撥）

**Files：**
- Create: `dto/LoanForm.java`、`dto/RateVersionForm.java`、`dto/PrincipalForm.java`、`service/LoanService.java`、`service/LoanValidationException.java`
- Test: `service/LoanServiceTest.java`

**Interfaces：**
- `LoanForm(String name, LoanType type, String bank, LoanOrigin origin, BigDecimal originalAmount, LocalDate startDate, BigDecimal startPrincipal, BigDecimal annualRatePercent, Long disburseAccountId, Long payAccountId, Integer payDay, Integer remainingPeriods, YearMonth firstPeriod, LocalDate dueDate)`（利率畫面填百分比，例如 2.5，Service 轉成 0.025）
- `LoanService.create(LoanForm): Loan`（新撥款同時記 `LOAN_DISBURSEMENT`；既有借款不記；兩種都寫第一個利率版本，生效日 ＝ 起算日；建立後呼叫 `LoanPostingService.postDue(loan, today)`）
- `edit(Long id, LoanForm)`：名稱、銀行、原始金額、扣款日、帳戶、到期日可改；扣款日或帳戶改了把 `posting_from` 移到當月；其他欄位有自動記帳後不能改（訊息「起算日、起算本金、剩餘期數、類型不能改；要改請作廢重建」）
- `addRate(Long id, RateVersionForm(LocalDate effectiveDate, BigDecimal annualRatePercent))`
- `repay(Long id, PrincipalForm(LocalDate date, BigDecimal amount, Long accountId))`：信貸是提前還本、質押是還本；不能超過剩餘本金；記 `LOAN_PRINCIPAL`（`period` 為 null）
- `redraw(Long id, PrincipalForm)`：只限質押；記 `LOAN_DISBURSEMENT`
- `find`、`listMine`、`remainingPrincipal(Loan, LocalDate)`
- 檢查：起算日、第一期扣款日不早於相關帳戶的初始現金餘額日期（設計 11-7）；日期不晚於今天（撥款、還本）；利率 0～30%。

- [ ] **Step 1：寫測試**（數字用 Task 1 的例子）：`createExistingCredit`（不記撥款、寫利率版本）、`createNewPledgeRecordsDisbursement`、`validation`（每條訊息）、`startBeforeOpeningRejected`、`editPayDayMovesPostingFrom`、`lockedFieldsAfterPosting`、`repayCannotExceedRemaining`、`redrawOnlyForPledge`、`otherUsersLoanIsNotFound`。
- [ ] **Step 2～4：** RED → 實作 → `./mvnw test` 全部通過。

### Task 4：LoanPostingService（自動記帳）

**Files：**
- Create: `service/LoanPostingService.java`
- Modify: `repository/LoanRepository.java`（`findAllForPosting()`、`findForUpdate(id, userId)`）
- Test: `service/LoanPostingServiceTest.java`

**Interfaces：** `postDue(): int`（啟動與 00:10；每筆借款一個交易、先鎖住再讀）；`postDue(Loan, LocalDate today): int`。

- 信貸：從 `first_period`（且 ≥ `posting_from`）起，每期扣款日 ≤ today、還沒產生（含作廢的）的期數，記 `LOAN_PRINCIPAL` 與 `LOAN_INTEREST` 兩筆（同一期別），金額照 Task 1；剩餘期數用完或剩餘本金 0 就停。
- 質押：每月繳息日 ≤ today、還沒產生的期數，記 `LOAN_INTEREST`，金額 ＝ 上一筆實際繳息日（第一期為起算日）到這次繳息日的利息；剩餘本金變 0 的那天記最後一段利息（期別 ＝ 那天的月份）。
- 作廢的期數不再補；起算日之前、`posting_from` 之前的期數不產生；不產生未來日期。

- [ ] **Step 1：寫測試**：`creditPostsInstallments`（既有借款、剩 60 期、第一期 2026-09 → 兩期四筆，數字對 Task 1）、`postingTwiceCreatesOnce`、`voidedInstallmentNotRegenerated`、`editedPrincipalCarriesToLastPeriod`（改第 1 期本金 → 之後月付金不變，最後一期補足）、`pledgePostsMonthlyInterest`、`pledgeUsesLastActualPaymentDate`、`pledgePayoffPostsFinalInterest`、`neverPostsFutureDates`、`postsForEveryUser`。
- [ ] **Step 2～4：** RED → 實作 → 通過。

### Task 5：帳本與本月淨資產變動

**Files：**
- Modify: `calculator/CashLedgerCalculator.java`（撥款加、還本與利息減）、`calculator/MonthlyCashChange.java`（加 `interest`；`total = deposits − withdrawals − interest + adjustments`）、`service/TotalAssetsService.java`（`MovementRow` 方向、利息由 `LoanService.accruedInterest(YearMonth)` 提供）、`templates/assets/index.html`（拆解加「利息」）、`service/CashMovementService.java`（借款類型的修改：只能改日期與金額，並寫修改紀錄；`record` 不接受借款類型）、`templates/assets/movement-form.html` 或新的 `loan-movement-form.html`
- Test: `CashLedgerCalculatorTest`、`TotalAssetsServiceTest`、`CashMovementRevisionTest`

- [ ] **Step 1：寫測試**：`loanMovementsChangeBalance`、`disbursementAndPrincipalNotCounted`、`monthlyChangeCountsInterestOnce`（質押當月應計 + 信貸當月那期；`LOAN_INTEREST` 異動不另外算）、`paidPeriodUsesActualAmount`（改成銀行金額後，利息那行用實際金額）、`editLoanMovementWritesRevision`、`recordRejectsLoanTypes`。
- [ ] **Step 2～4：** RED → 實作 → 通過。

### Task 6：資金來源頁

**Files：**
- Create: `controller/FundingController.java`、`dto/FundingView.java`、`dto/LoanCard.java`、`templates/funding/index.html`、`templates/funding/loan-form.html`、`templates/funding/rate-form.html`、`templates/funding/principal-form.html`
- Modify: `templates/fragments/layout.html`（「資金來源」可點，`active == 'funding'`）、`controller/CashMovementController.java`（`?loan={id}` 篩選）、`static/js/app.js`（沿用 `data-show-source`：類型決定信貸欄位、方式決定撥入帳戶）
- Test: `controller/FundingControllerTest.java`

- 頁面：卡片（總負債、加權平均利率、本月利息）；每筆借款卡片（名稱、銀行、類型、原始金額、剩餘本金、目前年利率、本月利息、累計已付利息、尚未投入 ＝ 剩餘本金；信貸：月付金、剩餘期數、下次扣款日；質押：下次繳息日）；操作：修改、新增利率版本、還本（質押另有再動撥）、看紀錄。

- [ ] **Step 1：寫測試**：`pageShowsCardsAndLoans`、`createExistingCreditViaForm`、`formHasConditionalFields`、`addRateViaForm`、`repayViaForm`、`movementsByLoan`、`otherUsersLoanIs404`、`duplicateTokenDoesNotCreateTwice`、`sidebarLinksFunding`。
- [ ] **Step 2～4：** RED → 實作 → `./mvnw test` 全部通過。

### Task 7：審查與驗收

- [ ] **Step 1：** 派 opus subagent 審查全部改動，修正 Critical／Important。
- [ ] **Step 2：** 規格書更新（第 3 節第 4 頁、4.3 拆解加利息、6 Loan／CashMovement／RateSetting、新 DR：既有借款、應計利息與實付校正、借款排程沿用 DR-036），寫入 `docs/spec.md`。
- [ ] **Step 3：** 問使用者可否套用 V9 到 Docker；同意後用 `demo` 帳號建一筆信貸、一筆質押截圖講解（先確認左下角是測試帳號）。
- [ ] **Step 4：** 整理 diff 與 commit 建議，經同意才 commit。
