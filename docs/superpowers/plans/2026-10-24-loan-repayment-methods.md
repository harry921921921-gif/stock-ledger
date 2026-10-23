# 借款還款方式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增借款先選還款方式（每月固定還款／到期一次還本金＋利息／每月繳息到期還本），每月扣款以銀行金額為準，到期一次付清只算應計利息並提醒，往來帳戶合一，開辦費算成本，股票質押在表單直接勾選擔保持股。

**Architecture:** `loan` 加 `repayment_method`；「股票質押」沿用 `loan_type`（PLEDGE＝勾了質押，CREDIT＝一般借款）。銀行每月扣款金額與利率一樣存成綁借款的 `rate_setting` 版本（新種類 `LOAN_MONTHLY_PAYMENT`），利率變動時可以一起換。開辦費是新的現金異動類型 `LOAN_FEE`。計算仍在 Calculator，LoanService 依還款方式分流。

**Tech Stack:** Java 21、Spring Boot 4、Thymeleaf、MySQL 8.4、Flyway、JUnit 5／AssertJ、Testcontainers。

**Spec:** `docs/spec.md` v1.10 第 4.8a 節、4.8、5.3、資料模型 Loan、DR-047、DR-048；樣稿 `docs/superpowers/mockups/loan-form.html`。

## Global Constraints

- 金額 `BigDecimal`；利息 `UP` 到元；中間值 10 位 `HALF_UP`；費率不可寫死，一律從 `RateSetting` 依生效日期取得。
- Schema 只能新增 migration：`V11__loan_repayment_method.sql`；V1～V10 不可修改。V11 套用到使用者 Docker 前要先問。
- 個人資料查詢一律帶 `CurrentUser.id()`；不接受畫面傳來的 user_id。
- Controller 與模板不計算；新的公式寫在純 Java Calculator 並有單元測試。
- 交易與資金異動不硬刪除。
- 還款方式代碼：`MONTHLY_FIXED`（每月固定還款）、`BULLET`（到期一次還本金＋利息）、`INTEREST_ONLY`（每月繳息、到期還本）。
- 到期提醒：到期日前 30 天（含）起提醒；提醒線／追繳線預設 150%／130%。
- 扣款金額核對：合約月付金和銀行扣款差超過 1 元才提醒，不擋存檔。

## Review Focus

1. **舊借款**：V11 之前建立的信貸與質押要照原本的行為繼續自動記帳（信貸照公式月付金、質押每月繳息），而且畫面不能壞 — Task 1、Task 3 各有一個測試。
2. **銀行扣款小於當期利息**（例如填錯成 50 元）：本金會變負數，應該擋下並說明 — Task 2 測試。
3. **到期一次付清的借款已經過了到期日還沒還**：卡片要顯示「已到期 N 天」，提醒不能消失 — Task 5 測試。
4. **勾了質押但沒勾任何擔保股票**：可以存，維持率顯示「—」 — Task 4 測試。
5. **還款方式和欄位不一致**（例如 BULLET 卻送了總期數，或 MONTHLY_FIXED 沒有扣款金額）：服務層要擋，不能只靠畫面隱藏欄位 — Task 4 測試。

---

### Task 1: V11、Entity 與舊借款對應

**Files:**
- Create: `src/main/resources/db/migration/V11__loan_repayment_method.sql`
- Create: `src/main/java/com/sideproject/stockledger/entity/RepaymentMethod.java`
- Modify: `entity/Loan.java`、`entity/LoanType.java`（label：CREDIT「一般借款」、PLEDGE「股票質押」）、`entity/CashMovementType.java`（加 `LOAN_FEE("開辦費")`，`isLoan()` 包含它）、`entity/RateKind.java`（加 `LOAN_MONTHLY_PAYMENT("每月扣款金額", Scope.LOAN, Unit.AMOUNT)`）、`entity/RateSetting.java`（`loanPayment(userId, loanId, amount, effectiveDate, now)`）、`calculator/CashLedgerCalculator.java`（`LOAN_FEE` 和利息一樣減少餘額）
- Test: `src/test/java/com/sideproject/stockledger/repository/LoanRepaymentSchemaTest.java`

**Interfaces:**
- Produces: `enum RepaymentMethod { MONTHLY_FIXED, BULLET, INTEREST_ONLY; String label(); }`；`Loan.getRepaymentMethod()`；`Loan.create(..., RepaymentMethod method, ...)`（在原參數後加）。

V11 內容：
1. `loan` 加 `repayment_method VARCHAR(20) NULL`；`UPDATE`：CREDIT → `MONTHLY_FIXED`、PLEDGE → `INTEREST_ONLY`；再改成 `NOT NULL`。
2. 拿掉 `ck_loan_credit`，改成 `ck_loan_method`：MONTHLY_FIXED 要有 `remaining_periods ≥ 1` 與 `first_period`；BULLET 要有 `due_date`、沒有期數；INTEREST_ONLY 沒有期數（`due_date` 可以 NULL，因為舊質押沒填）。`IS NOT NULL` 要寫出來。
3. `cash_movement` 的 `ck_cash_movement_type`、借款相關 CHECK（看 V10 最新版本）加入 `LOAN_FEE`；`LOAN_FEE` 和其他借款異動一樣要有 `loan_id`、沒有 `period`。
4. `rate_setting` 的 `ck_rate_setting_loan` 種類清單加入 `LOAN_MONTHLY_PAYMENT`。

- [ ] **Step 1: Write the failing test** `LoanRepaymentSchemaTest`：
  - `legacyLoansMapped`：用 Flyway API 在 Testcontainers 另開一個空 schema，先 `target("10")` 遷移、用 JDBC 插入 V10 形狀的信貸與質押各一筆，再遷移到 11 → 信貸的 `repayment_method` 是 `MONTHLY_FIXED`、質押是 `INTEREST_ONLY`，兩筆都還能讀。
  - `bulletNeedsDueDate`：插入 `repayment_method='BULLET'`、`due_date NULL` → 拋 `DataIntegrityViolationException`。
  - `monthlyNeedsPeriods`：`MONTHLY_FIXED` 沒有 `remaining_periods` → 拋例外。
  - `loanFeeMovement`：插入 `type='LOAN_FEE'` 帶 `loan_id` → 成功；不帶 `loan_id` → 拋例外。
  - `monthlyPaymentRate`：`rate_setting` 插入 `kind='LOAN_MONTHLY_PAYMENT'` 帶 `loan_id` → 成功。
- [ ] **Step 2: Run** `./mvnw test -Dtest=LoanRepaymentSchemaTest` → FAIL（欄位與種類不存在）。
- [ ] **Step 3: Implement** V11 與 Entity 變更；所有既有建立借款的程式先傳 `type == CREDIT ? MONTHLY_FIXED : INTEREST_ONLY`，讓行為不變。
- [ ] **Step 4: Run** `./mvnw test`（全部）→ 全部 PASS（既有借款測試不變）。
- [ ] **Step 5: Commit** `feat: V11 借款還款方式欄位與開辦費類型`

### Task 2: 每月扣款以銀行金額為準（InstallmentCalculator）

**Files:**
- Modify: `calculator/InstallmentCalculator.java`、`service/LoanFacts.java`、`service/LoanFactsReader.java`
- Create: `calculator/PaymentChange.java`
- Test: `calculator/InstallmentCalculatorTest.java`

**Interfaces:**
- Produces: `record PaymentChange(LocalDate effectiveDate, BigDecimal amount)`；`InstallmentCalculator.Input` 新增最後一個參數 `List<PaymentChange> payments`（空清單＝沿用公式）；`LoanFacts.payments()`。
- 規則：某一期扣款日適用的扣款版本（生效日 ≤ 扣款日的最後一筆，同一天以清單後面的為準）存在時，月付金＝那個金額；利息＝ceil(期初剩餘本金 × 年利率 ÷ 12)；本金＝月付金 − 利息；最後一期本金＝剩餘本金。沒有版本時照原本 DR-018 公式。

- [ ] **Step 1: Write the failing tests**
  - `bankPaymentIsUsed`：400,000、2.15%、84 期、payments=[(撥款日, 5133)] → 第 1 期利息 717、本金 4,416（717 ＝ ceil(400000 × 0.0215 ÷ 12) ＝ ceil(716.67)）。
  - `lastPeriodTakesRemainder`：同上，第 84 期本金等於第 83 期後的剩餘本金，剩餘本金歸零。
  - `paymentChangeFromLaterPeriod`：第 13 期起 payments 加 (第 13 期扣款日, 5200) → 第 13 期月付金 5,200。
  - `paymentBelowInterestRejected`：payments=[(撥款日, 500)] → `IllegalArgumentException`，訊息「每月扣款 500 元比當期利息 717 元少」。
  - `noPaymentFallsBackToFormula`：payments 空 → 結果和改動前一樣（沿用既有測試的數字）。
- [ ] **Step 2: Run** `./mvnw test -Dtest=InstallmentCalculatorTest` → FAIL。
- [ ] **Step 3: Implement**；`LoanFactsReader` 讀 `LOAN_MONTHLY_PAYMENT` 版本成 `List<PaymentChange>`，`LoanFacts.installmentInput()` 傳進去。
- [ ] **Step 4: Run** `./mvnw test -Dtest='InstallmentCalculatorTest,LoanPostingServiceTest,LoanServiceTest'` → PASS。
- [ ] **Step 5: Commit** `feat: 每月固定還款以銀行扣款金額為準`

### Task 3: 依還款方式自動記帳與應計利息

**Files:**
- Modify: `service/LoanPostingService.java`、`service/LoanService.java`（`interestIn`、`card`）、`calculator/LoanAccrualCalculator.java`（只在需要時）
- Test: `service/LoanPostingServiceTest.java`、`service/LoanServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `Loan.getRepaymentMethod()`、Task 2 `LoanFacts.payments()`。
- 分流改用 `repaymentMethod`，不再用 `loan_type`：MONTHLY_FIXED → 現在的 `postCredit`；INTEREST_ONLY → 現在的 `postPledge`；BULLET → 不自動記任何利息（包括還清當天）。
- 本月應計利息：MONTHLY_FIXED 用攤還表；INTEREST_ONLY 與 BULLET 都用 `LoanAccrualCalculator.pledgeMonth`（BULLET 沒有繳息紀錄，按日一直累積）。

- [ ] **Step 1: Write the failing tests**
  - `LoanPostingServiceTest.bulletPostsNothing`：BULLET 借款 100,000、4%、撥款 3 個月前 → `postDue` 後沒有任何 `LOAN_INTEREST`。
  - `LoanServiceTest.bulletAccruesMonthly`：同一筆，`accruedInterest(本月)` ＞ 0，等於按日計息到今天的本月部分。
  - `LoanPostingServiceTest.legacyPledgeStillPostsMonthly`：INTEREST_ONLY（V11 對應的舊質押）照舊每月繳息日記一筆。
- [ ] **Step 2: Run** 上面兩個測試類別 → FAIL。
- [ ] **Step 3: Implement** 分流。
- [ ] **Step 4: Run** `./mvnw test` → 全部 PASS。
- [ ] **Step 5: Commit** `feat: 依還款方式自動記帳；到期一次付清只算應計利息`

### Task 4: 新增與修改借款（表單資料、檢查、開辦費、擔保股票）

**Files:**
- Modify: `dto/LoanForm.java`、`service/LoanService.java`（`create`、`edit`、`form`、`validate`、`addRate`、`repay`）、`dto/RateVersionForm.java`、`dto/PrincipalForm.java`
- Create: `dto/CollateralPick.java`
- Test: `service/LoanRepaymentServiceTest.java`

**Interfaces:**
- Produces:
  - `LoanForm(String name, String bank, RepaymentMethod repaymentMethod, boolean pledge, LocalDate startDate, BigDecimal amount, BigDecimal annualRatePercent, Long accountId, BigDecimal monthlyPayment, Integer payDay, Integer periods, LocalDate dueDate, BigDecimal fee, List<CollateralPick> collateral, BigDecimal warnPercent, BigDecimal callPercent)`
  - `record CollateralPick(Long stockId, Long shares)`（stockId 為 null 或 shares 為 0 的列略過）
  - `RateVersionForm(LocalDate effectiveDate, BigDecimal annualRatePercent, BigDecimal monthlyPayment)`（monthlyPayment 選填）
  - `PrincipalForm(LocalDate date, BigDecimal amount, Long accountId, BigDecimal interest)`（interest 選填；有值時同一天另記一筆 `LOAN_INTEREST`）
  - `LoanService.Created create(LoanForm)`，`record Created(Loan loan, String paymentWarning)`：paymentWarning 為 null 或「用金額、利率、期數算出來是 5,187，和銀行扣款 5,133 差 54；請確認金額或利率」。
  - `LoanService.repayDefaults(Long id)`：BULLET 帶入本金＝剩餘本金、利息＝最後一次付息（或撥款日）到今天的應計利息。
- 存檔規則：`disburseAccountId` 與 `payAccountId` 都設成 `accountId`；`origin` 一律 NEW；第一期扣款月份＝撥款日之後第一個扣款日所在月份；`pledge` → `LoanType.PLEDGE`；MONTHLY_FIXED 寫一筆 `LOAN_MONTHLY_PAYMENT`（生效日＝撥款日）；fee ＞ 0 記一筆 `LOAN_FEE`（撥款日）；collateral 逐筆呼叫既有 `setCollateral` 規則；門檻有填才寫版本。

- [ ] **Step 1: Write the failing tests**（`LoanRepaymentServiceTest`）
  - `monthlyFixedCreate`：土銀信貸 400,000、2.15%、5,133、1 號、84 期、撥款 2026-02-01 → 第一期 2026-03；`paymentWarning` 為 null；`rate_setting` 有 `LOAN_MONTHLY_PAYMENT` 5133。
  - `paymentWarning`：同上但利率 2.45% → warning 含「5,187」「54」。
  - `bulletCreate`：玉山質押 100,000、4%、到期 2027-07-28、pledge=true、勾 00830 1,000 股 → `loan_type=PLEDGE`、`repayment_method=BULLET`、`pledge_collateral` 一列。
  - `pledgeWithoutCollateral`：pledge=true、collateral 空 → 存檔成功，卡片 `maintenancePercent` 為 null。
  - `feeRecorded`：fee=3000 → 一筆 `LOAN_FEE` 3000、日期＝撥款日；卡片 `paidInterest` 含 3000。
  - `fieldsMustMatchMethod`：BULLET 帶 periods=84 → 「到期一次還本金＋利息不用填總期數」；MONTHLY_FIXED 沒有 monthlyPayment → 「請填銀行每月扣款金額」；BULLET 沒有 dueDate → 「請填到期日」。
  - `bulletRepayWithInterest`：`repay(id, PrincipalForm(到期日, 100000, 帳戶, 4000))` → 一筆 `LOAN_PRINCIPAL` 100,000＋一筆 `LOAN_INTEREST` 4,000，借款已還清。
  - `rateVersionWithPayment`：`addRate(id, RateVersionForm(第 13 期扣款日, 2.45, 5200))` → 寫入利率與扣款兩個版本。
  - `otherUsersLoanNotFound`：別人的借款 `repayDefaults` → `NotFoundException`。
- [ ] **Step 2: Run** → FAIL。
- [ ] **Step 3: Implement**；`edit` 只允許改名稱、銀行、往來帳戶、扣款日、到期日（展延）；其他開始記帳後不能改（沿用 LOCKED 訊息）。
- [ ] **Step 4: Run** `./mvnw test` → 全部 PASS（舊測試若用到舊的 `LoanForm` 建構子，改成新欄位，數字不變）。
- [ ] **Step 5: Commit** `feat: 新增借款依還款方式填寫；開辦費與擔保股票`

### Task 5: 資金來源卡片、到期提醒、賺贏利息

**Files:**
- Modify: `dto/LoanCard.java`、`service/LoanService.java`（`card`、`fundingView`）、`dto/OverviewView.java`、`service/OverviewService.java`
- Test: `service/LoanRepaymentServiceTest.java`（續）、`controller/OverviewControllerTest.java`

**Interfaces:**
- LoanCard 新增：`String repaymentLabel`、`BigDecimal monthlyPayment`（MONTHLY_FIXED）、`LocalDate dueDate`、`Integer daysToDue`（負數＝已過期）、`BigDecimal amountAtDue`（BULLET：本金＋到期利息估計；INTEREST_ONLY：本金）、`BigDecimal accruedInterest`（BULLET 到今天）、`boolean dueSoon`（0 ≤ daysToDue ≤ 30 或已過期且沒還清）。
- BULLET 的「賺贏利息」：已付利息改用 `accruedInterest`＋開辦費。
- OverviewView 新增 `List<DueReminder> dueReminders`，`record DueReminder(String loanName, LocalDate dueDate, int daysToDue, BigDecimal amountAtDue)`。

- [ ] **Step 1: Write the failing tests**
  - `bulletCard`：撥款 2026-07-28、到期 2027-07-28、今天固定 → `daysToDue`、`amountAtDue`＝100,000＋ceil(100000 × 4% × 365 ÷ 365)＝104,000、`accruedInterest` ＞ 0、`verdict` 用應計利息判斷。
  - `overdueBulletKeepsReminder`：到期日是 5 天前、沒還清 → `daysToDue = -5`、`dueSoon = true`，總覽 `dueReminders` 一筆。
  - `monthlyCard`：`monthlyPayment = 5133`。
  - `OverviewControllerTest.dueReminderShown`：頁面有「玉山質押 2027-07-28 到期」。
- [ ] **Step 2: Run** → FAIL。
- [ ] **Step 3: Implement**。
- [ ] **Step 4: Run** `./mvnw test` → PASS。
- [ ] **Step 5: Commit** `feat: 借款卡片依還款方式顯示；到期提醒`

### Task 6: 畫面（照樣稿）

**Files:**
- Modify: `templates/funding/loan-form.html`、`templates/funding/rate-form.html`、`templates/funding/principal-form.html`、`templates/funding/index.html`、`templates/overview/index.html`、`controller/FundingController.java`、`static/js/app.js`（`data-show-source` 支援 radio）、`static/css/app.css`
- Test: `controller/FundingControllerTest.java`

**Interfaces:**
- Consumes: Task 4 的 `LoanForm`、`Created.paymentWarning`、`repayDefaults`；Task 5 的 LoanCard 欄位。
- 表單照樣稿：還款方式三個 radio（`name="repaymentMethod"`）、「這是股票質押」checkbox（`name="pledge"`）、擔保股票清單（持股由 `TradeService.holdingValues()` 經 Controller 的 `@ModelAttribute` 提供，欄位 `collateral[i].stockId`、`collateral[i].shares`）、提醒線／追繳線、往來帳戶、開辦費。存檔後若有 `paymentWarning`，用 flash 訊息顯示在資金來源頁（不擋存檔）。
- 樣稿裡的即時試算只放在畫面上當輔助（維持率、合約月付金核對）時，要改成伺服器算：表單送出前不試算，送出後由 Task 4 的 warning 顯示；畫面不做計算（CLAUDE.md）。

- [ ] **Step 1: Write the failing tests**（`FundingControllerTest`）
  - `formShowsRepaymentMethods`：`GET /funding/new` 有三個 `name="repaymentMethod"` radio、`name="pledge"`、`data-show-for="MONTHLY_FIXED"` 區塊。
  - `createMonthlyViaForm`：POST 土銀信貸資料 → redirect `/funding`，頁面有「每月扣款」「5,133」。
  - `paymentWarningFlash`：利率 2.45% → flash 含「差 54」。
  - `bulletRepayFormPrefilled`：`GET /funding/{id}/repay` 對 BULLET 借款帶入本金與利息兩個值。
- [ ] **Step 2: Run** → FAIL。
- [ ] **Step 3: Implement**。
- [ ] **Step 4: Run** `./mvnw test` → 全部 PASS；`docker compose` 不動使用者資料庫，改用另開的測試資料庫（127.0.0.1:3317、8081）手動跑過三種還款方式的新增、卡片、還本。
- [ ] **Step 5: Commit** `feat: 新增借款表單照樣稿（還款方式、股票質押勾選）`

### Task 7: 審查與驗收

- [ ] **Step 1:** 派 opus 子代理審查整個分支（重點：Review Focus 五項、舊借款相容、user_id、BigDecimal 進位）。
- [ ] **Step 2:** 修 Critical／Important，問題清單留給使用者。
- [ ] **Step 3:** 問使用者是否把 V11 套用到 Docker；同意後重建並用 demo 帳號截圖講解。
- [ ] **Step 4:** 規格書標註完成、樣稿檔一起 commit（訊息先給使用者看）。
