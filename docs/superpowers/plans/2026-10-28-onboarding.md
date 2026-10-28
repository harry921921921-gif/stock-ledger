# 第一次使用引導 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新帳號登入後進入一步一步的引導（帳戶 → 借款 → 股票 → 擔保 → 完成），儲存前先確認、沒填完不能存、上一步下一步都記得打到一半的字、已儲存的可以在引導裡修改；並在總覽、股票、資金來源頁最上方提醒「引導沒做完」「質押還沒設定擔保股票」。

**Architecture:** `app_user.onboarding_step` 記進度（只往前）。`OnboardingController` 只負責畫面與轉址，所有存取經 `OnboardingService`，它呼叫現有的 `CashAccountService`、`CashMovementService`、`LoanService`、`TradeService`，規則只有一份。儲存分兩段：`POST .../preview` 只驗證（Service 的 `validate` 方法）並畫出確認區塊，`POST .../save` 帶 `FormTokens` 才真的存。草稿放在 HttpSession 的 `OnboardingDrafts`。

**Tech Stack:** Java 21、Spring Boot、Spring Security、Thymeleaf、Flyway、MySQL 8.4（Testcontainers）、JUnit 5、MockMvc。

**Spec:** `docs/superpowers/specs/2026-10-25-onboarding-design.md`（全文，特別是 3.2、3.3、4、5、7a）；樣稿 `docs/superpowers/mockups/onboarding.html`。

**前置：** `docs/superpowers/plans/2026-10-28-loan-full-edit.md` 全部完成（引導裡修改借款用它的 `LoanService.edit(id, form, confirmShortfall)`；到期日規則與 `dueWarning` 也在那裡）。

## Global Constraints

- 套件 `com.sideproject.stockledger`；Controller 不碰 Repository、不計算；模板不計算、不判斷業務規則，只顯示 Service 給的資料與訊息。
- user_id 只從 `CurrentUser` 取得；`/welcome/collateral/{loanId}` 的 loanId 一律經 Service 用登入者 user_id 查，找不到丟 `NotFoundException`（404）。
- 新 migration 用接續的版本號：目前最後是 `V11__loan_repayment_method.sql`，本計畫用 `V12__onboarding_step.sql`（若上櫃 DR-049 先做而占用 V12，改用下一號）；已執行過的 migration 不改。套用到使用者的 Docker 前先問。
- `onboarding_step` 值：`WELCOME`、`ACCOUNTS`、`LOANS`、`STOCKS`、`COLLATERAL`、`DONE`（`VARCHAR(20) NOT NULL`，CHECK 限制）。
- 畫面文字照設計文件 3.1、3.2、3.3 與樣稿原文。外觀沿用 `app.css`，只加步驟列、確認區塊、已儲存清單的少量樣式。
- 只加一段 JavaScript：輸入框內按 Enter 不送出（`keydown` 時 `e.key === 'Enter' && e.target.matches('input')` 就 `preventDefault`），放 `static/js/onboarding.js`。換頁後焦點：每頁 `<h1 tabindex="-1" autofocus>`；確認區塊出現時焦點在「回去修改」（`autofocus`），不在「確認儲存」。
- 測試一律 `./mvnw clean test`；commit 前給使用者看訊息；不 push。

## Review Focus

1. 「確認儲存」按兩次（或重新整理確認頁再送）只存一筆 → Task 4 `saveTwiceCreatesOneAccount`（FormTokens）。
2. 打到一半按「上一步」、到別頁、再回來：欄位原樣在；儲存成功後草稿清掉 → Task 3 `draftSurvivesBackAndClearsAfterSave`。
3. 已完成（`DONE`）的帳號直接打 `/welcome/stocks` 不會再進引導 → Task 2 `doneUserIsSentHome`。
4. 修改已儲存的股票時，原本打到一半的新股票草稿不見 → Task 6 `editKeepsNewDraft`。
5. 引導途中 session 逾時重新登入：已儲存的資料都在、從最遠的那一步繼續（草稿沒了是預期的）→ Task 2 `resumesAtFurthestStepAfterRelogin`。

---

### Task 1: 進度欄位、規格書 DR-050

**Files:**
- Create: `src/main/resources/db/migration/V12__onboarding_step.sql`
- Create: `src/main/java/com/sideproject/stockledger/entity/OnboardingStep.java`（enum，有 `isAfter(OnboardingStep)`）
- Modify: `src/main/java/com/sideproject/stockledger/entity/AppUser.java`（欄位 `onboardingStep`，`create(...)` 預設 `WELCOME`；方法 `advanceOnboarding(OnboardingStep to)` 只往前；`finishOnboarding()`）
- Modify: `docs/spec.md`（第 3 節頁面表加「第一次使用引導」；6 資料模型 AppUser 加 `onboarding_step`；第 8 節加 DR-050，內容照設計文件第 5 節與 7a-1～5；版本說明併入 v1.12）
- Test: `src/test/java/com/sideproject/stockledger/repository/OnboardingSchemaTest.java`、`src/test/java/com/sideproject/stockledger/entity/AppUserOnboardingTest.java`

Migration 內容：`ALTER TABLE app_user ADD COLUMN onboarding_step VARCHAR(20) NOT NULL DEFAULT 'WELCOME'`，加 `CONSTRAINT ck_app_user_onboarding_step CHECK (...)`；接著 `UPDATE app_user SET onboarding_step = 'DONE' WHERE EXISTS (SELECT 1 FROM cash_account a WHERE a.user_id = app_user.id)`。

- [ ] **Step 1: 寫失敗的測試**：`AppUserOnboardingTest`（純 Java）`advanceOnlyMovesForward`（`LOANS` 後 advance `ACCOUNTS` 仍是 `LOANS`）、`newUserStartsAtWelcome`；`OnboardingSchemaTest`（Testcontainers，`JdbcTemplate`）`rejectsUnknownStep`（UPDATE 成 `'X'` 丟 `DataIntegrityViolationException`）、`migrationMarksUsersWithAccountsDone`（用 Flyway 的 `target` 先 migrate 到 V11、插入一個有帳戶與一個沒帳戶的使用者，再 migrate 到 V12，前者 `DONE`、後者 `WELCOME`；寫法比照 `V7MigrationTest`）。
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw clean test -Dtest=AppUserOnboardingTest,OnboardingSchemaTest` → FAIL
- [ ] **Step 3: 實作 migration、enum、AppUser，寫規格書**
- [ ] **Step 4: 跑測試確認通過**：同上 → PASS；再跑 `./mvnw clean test` 全過
- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 2: OnboardingService 進度與轉址

**Files:**
- Create: `src/main/java/com/sideproject/stockledger/service/OnboardingService.java`
- Create: `src/main/java/com/sideproject/stockledger/controller/OnboardingController.java`（這個 task 只放轉址與 `/welcome`、`/welcome/done`、`/welcome/skip`、`/welcome/leave`）
- Create: `src/main/resources/templates/onboarding/layout.html`（fragment：步驟列、「← 上一步」按鈕、`step-actions` 列）、`welcome.html`、`done.html`
- Modify: `src/main/java/com/sideproject/stockledger/controller/HomeController.java`（`GET /`：`onboarding.currentStep()` 不是 `DONE` 就 `redirect:` 那一步的網址）
- Test: `src/test/java/com/sideproject/stockledger/service/OnboardingServiceTest.java`、`src/test/java/com/sideproject/stockledger/controller/OnboardingControllerTest.java`

**Interfaces:**
- Produces（`OnboardingService`）：
  - `OnboardingStep currentStep()`
  - `void reach(OnboardingStep step)`（進到某一步就呼叫，只往前）
  - `void finish()`（`DONE`）
  - `boolean done()`
  - `String urlOf(OnboardingStep step)`：`WELCOME→/welcome`、`ACCOUNTS→/welcome/accounts`、`LOANS→/welcome/loans`、`STOCKS→/welcome/stocks`、`COLLATERAL→/welcome/collateral`（再由 controller 轉到第一筆需要的質押）、`DONE→/`
  - `OnboardingSummary summary()`：`record OnboardingSummary(int accounts, int loans, int stocks)`（stocks＝有持股的檔數），放 `dto`。
- `/welcome/**`（除了這個 task 之外的頁面也一樣）在 `done()` 時一律 `redirect:/`：用 controller 裡的 `@ModelAttribute` 方法或每個 handler 開頭的共用私有方法，不加 Spring Security 規則。

- [ ] **Step 1: 寫失敗的測試**：`newUserIsRedirectedToWelcome`（`GET /` → 302 `/welcome`）、`doneUserIsSentHome`（`DONE` 帳號 `GET /` 看到總覽、`GET /welcome/stocks` → 302 `/`）、`skipFinishes`（`POST /welcome/skip` → `DONE`、302 `/`）、`leaveKeepsStep`（在 `LOANS` 時 `POST /welcome/leave` → 302 `/`，step 仍是 `LOANS`，再 `GET /` → 302 `/welcome/loans`）、`resumesAtFurthestStepAfterRelogin`、`summaryCounts`（service 測試）。
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw clean test -Dtest=OnboardingServiceTest,OnboardingControllerTest` → FAIL
- [ ] **Step 3: 實作**；既有 `OverviewControllerTest` 等用的測試帳號若因此被轉到 `/welcome`，在那些測試的 setUp 把帳號設成 `DONE`（不是改預期值；先列給使用者看）。
- [ ] **Step 4: 跑測試確認通過**：`./mvnw clean test` 全過
- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 3: 草稿、只驗證不存、確認區塊（共用機制）

**Files:**
- Create: `src/main/java/com/sideproject/stockledger/controller/OnboardingDrafts.java`（`@Component`，比照 `FormTokens` 存在 HttpSession）
- Modify: `src/main/java/com/sideproject/stockledger/service/CashAccountService.java`（`public void validate(CashAccountForm form)`，和 `create` 共用檢查，含名稱重複）
- Modify: `src/main/java/com/sideproject/stockledger/service/CashMovementService.java`（`public void validateOpening(OpeningForm form)`）
- Modify: `src/main/java/com/sideproject/stockledger/service/LoanService.java`（`public void validate(LoanForm form)`：`normalize`＋現有私有 `validate`，不存）
- Modify: `src/main/java/com/sideproject/stockledger/service/TradeService.java`（`public void validate(TradeForm form)`：和 `create` 共用，含「期初持股要早於這檔所有交易」「借款出的錢超過尚未投入」）
- Create: `src/main/java/com/sideproject/stockledger/dto/ConfirmRow.java`（`record ConfirmRow(String label, String value)`；value 為空字串時畫面顯示「（沒填）」）
- Create: `src/main/resources/templates/onboarding/confirm.html`（fragment：標題「確認要儲存這筆○○嗎？」、ConfirmRow 清單、提醒（紅底）、「回去修改」「確認儲存」；錯誤時標題「還有欄位沒填好，不能儲存」、只有「回去修改」）
- Create: `src/main/resources/static/js/onboarding.js`（只有 Enter 那段）
- Test: `src/test/java/com/sideproject/stockledger/service/ValidateOnlyTest.java`、`src/test/java/com/sideproject/stockledger/controller/OnboardingDraftsTest.java`

**Interfaces:**
- Produces：
  - `OnboardingDrafts`：`void save(HttpSession s, String formKey, Map<String, String[]> params)`、`Optional<Map<String, String[]>> load(HttpSession s, String formKey)`、`void clear(HttpSession s, String formKey)`；formKey 用 `"accounts"`、`"loans"`、`"stocks"`、`"collateral-{loanId}"`；修改已儲存資料時另用 `"<key>:new"` 收好新草稿。
  - 各 Service 的 `validate` 丟的例外和存檔時一模一樣（同一段程式）。
  - Controller 端每個步驟的 `ConfirmRow` 清單由 `OnboardingService` 組（`List<ConfirmRow> rowsFor(CashAccountForm, OpeningForm)`、`rowsFor(LoanForm)`、`rowsFor(TradeForm)`），因為欄位顯示文字（例如還款方式中文、帳戶名稱）要查資料。
- 「上一步」「下一步」「先離開」都是同一個 `<form>` 裡的 `<button formaction="...">`，送到 `POST /welcome/{step}/go?to=...`：controller 先 `drafts.save(...)` 再 `redirect:` 目的地，不驗證。

- [ ] **Step 1: 寫失敗的測試**：`ValidateOnlyTest` 每個 Service 各一：缺必填丟同樣訊息、合法時資料庫筆數不變（例如 `tradeValidateDoesNotSave`：`validate` 後 `trade` 表 0 筆）；`OnboardingDraftsTest` `draftSurvivesBackAndClearsAfterSave`（MockMvc：在 `/welcome/accounts` 填名稱「玉山」→ `POST .../go?to=welcome` → `GET /welcome/accounts` 頁面 input value 是「玉山」→ 走 preview＋save 存成功 → 再 `GET` 欄位是空的）。
- [ ] **Step 2: 跑測試確認失敗**：`./mvnw clean test -Dtest=ValidateOnlyTest,OnboardingDraftsTest` → FAIL
- [ ] **Step 3: 實作**（帳戶頁的 preview／save 在 Task 4 完成；這個 task 的 MockMvc 測試可以先標 `@Disabled("Task 4")`，Task 4 拿掉）
- [ ] **Step 4: 跑測試確認通過**：`./mvnw clean test -Dtest=ValidateOnlyTest,OnboardingDraftsTest` → PASS
- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 4: 帳戶這一步

**Files:**
- Modify: `OnboardingController.java`（`GET /welcome/accounts`、`POST /welcome/accounts/preview`、`POST /welcome/accounts/save`、`GET /welcome/accounts/{id}/edit`、`POST /welcome/accounts/{id}/preview`、`POST /welcome/accounts/{id}/save`、`POST /welcome/accounts/cancel-edit`）
- Modify: `OnboardingService.java`（`CashAccount addAccount(CashAccountForm form, OpeningForm opening)`：同一個交易呼叫 `CashAccountService.create` 和（有填餘額時）`CashMovementService.setOpening`；`void editAccount(Long id, CashAccountForm form, OpeningForm opening)`：`CashAccountService.update`＋`setOpening` 或 `editOpening(id, form, "第一次使用引導修改")`；`List<SavedRow> savedAccounts()`；`boolean hasSettlementAccount()`）
- Create: `src/main/java/com/sideproject/stockledger/dto/SavedRow.java`（`record SavedRow(Long id, String label)`，已儲存清單用）
- Create: `src/main/resources/templates/onboarding/accounts.html`
- Test: `OnboardingControllerTest.java`、`OnboardingServiceTest.java`

畫面：已儲存清單（每列「修改」）→ 表單（名稱、類型、銀行或券商、現在的餘額、這是哪一天的餘額）→「儲存這個帳戶」→ step-actions（先離開／下一步：借款 →，沒有已儲存的交割戶時 disabled 並顯示「至少要儲存一個證券交割戶，才能往下走。」）。進這一頁時 `reach(ACCOUNTS)`。

- [ ] **Step 1: 寫失敗的測試**：`previewShowsAllFields`（preview 回 200、model 的 rows 含「名稱 玉山交割戶」「現在的餘額 （沒填）」，資料庫 0 筆）、`previewWithMissingFieldShowsError`（沒填銀行或券商 → 標題「還有欄位沒填好，不能儲存」、`還沒填` 訊息是 `CashAccountForm` 的 `請輸入券商或銀行`）、`saveTwiceCreatesOneAccount`、`addAccountWithOpeningBalance`（service：帳戶＋初始現金餘額各一筆）、`editAccountInWizard`、`nextNeedsSettlementAccount`（只有銀行帳戶時 `POST .../go?to=loans` 仍回到帳戶頁並顯示提示）。拿掉 Task 3 的 `@Disabled`。
- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑測試確認通過**：`./mvnw clean test -Dtest=OnboardingControllerTest,OnboardingServiceTest,OnboardingDraftsTest` → PASS
- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 5: 借款這一步

**Files:**
- Modify: `OnboardingController.java`（`GET /welcome/loans`：還沒有借款且沒按過「有」時畫「你有拿去投資的借款嗎？」；`POST /welcome/loans/none`（略過，`reach(STOCKS)` 轉股票）；`GET /welcome/loans/form`；preview／save／edit／cancel-edit 同 Task 4 的命名；修改借款遇到 `LoanShortfallException` 畫 `funding/shortfall-confirm` 的同一個 fragment，確認後 `POST /welcome/loans/{id}/confirm-shortfall`）
- Modify: `OnboardingService.java`（`Loan addLoan(LoanForm form)` → `LoanService.create`；`LoanEditResult editLoan(Long id, LoanForm form, boolean confirmShortfall)` → `LoanService.edit`；`List<SavedRow> savedLoans()`；`rowsFor(LoanForm)` 另附 `LoanService.dueWarning(form)` 與 `paymentWarning` 當提醒）
- Create: `src/main/resources/templates/onboarding/loans-ask.html`、`loans.html`（表單欄位照樣稿：名稱、銀行、怎麼還、這是股票質押、撥款日、借款金額、年利率、依方式出現的欄位、往來帳戶、開辦費；不含擔保股票，說明「擔保股票等記完股票再設定」）
- Test: `OnboardingControllerTest.java`

依還款方式顯示欄位：沿用 `funding/loan-form.html` 現有的切換做法（它已經有依方式顯示的機制，抽成 fragment 共用，不要再寫一份）。

- [ ] **Step 1: 寫失敗的測試**：`askThenNoneSkipsToStocks`、`loanPreviewWarnsPastDueDate`（到期日已過 → 確認區塊有提醒文字、仍可確認儲存）、`loanPreviewRejectsDueBeforeStart`、`saveLoanThenEditAmount`（引導裡改金額，撥款跟著改）、`editLoanShortfallAsksFirst`。
- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑測試確認通過**：`./mvnw clean test -Dtest=OnboardingControllerTest` → PASS
- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 6: 股票這一步

**Files:**
- Modify: `OnboardingController.java`（`GET /welcome/stocks`、preview／save／edit／cancel-edit；「股票記完了，下一步 →」：有沒作廢的質押借款 → `reach(COLLATERAL)` 轉第一筆需要的質押，沒有 → `reach(DONE)` 前的完成頁 `/welcome/done`）
- Modify: `OnboardingService.java`（`Trade addStock(TradeForm form)` → `TradeService.create`；`void editStock(Long id, TradeForm form)` → `TradeService.edit(id, form, "第一次使用引導修改")`；`List<SavedRow> savedStocks()`（標籤「0050　5,000 股　73.5 元」）；`Optional<Long> nextPledgeNeedingCollateral(Long afterLoanId)`）
- Create: `src/main/resources/templates/onboarding/stocks.html`（記法二選一：「一次買進」= `TradeType.BUY`、「目前持有（不記得每次怎麼買）」= `TradeType.OPENING`；說明文字照設計文件 3.1；資金來源下拉列「自己的錢」與已儲存的借款，對應 `TradeForm.allocations`：選借款時那筆借款出全額）
- Test: `OnboardingControllerTest.java`

- [ ] **Step 1: 寫失敗的測試**：`stockPreviewMissingPrice`（沒填價格 → 不能儲存，訊息和股票頁相同）、`saveOpeningAndBuy`、`editKeepsNewDraft`（新股票打了「0056」→ 按某筆已儲存的「修改」→ 取消修改 → 欄位回到「0056」）、`nextGoesToCollateralWhenPledgeExists`、`nextGoesToDoneWithoutPledge`。
- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑測試確認通過** → PASS
- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 7: 擔保這一步與完成頁

**Files:**
- Modify: `OnboardingController.java`（`GET /welcome/collateral`（轉到 `nextPledgeNeedingCollateral(null)`，都設好就第一筆質押）、`GET /welcome/collateral/{loanId}`、preview／save、「之後再設定」、「下一步」到下一筆質押或 `/welcome/done`；`GET /welcome/done`、`POST /welcome/done`（`finish()` 後 `redirect:/`））
- Modify: `OnboardingService.java`（`CollateralPage collateralPage(Long loanId)`：持股清單（代號、名稱、持有股數、目前擔保股數，預設全部）、目前生效的提醒線與追繳線；`void saveCollateral(Long loanId, List<CollateralPick> picks, BigDecimal warn, BigDecimal call)`：每檔呼叫 `LoanService.setCollateral`（沒勾＝0）、門檻只有和目前生效值不同才呼叫 `setThresholds`）
- Create: `src/main/java/com/sideproject/stockledger/dto/CollateralPage.java`
- Create: `src/main/resources/templates/onboarding/collateral.html`
- Test: `OnboardingControllerTest.java`、`OnboardingServiceTest.java`

- [ ] **Step 1: 寫失敗的測試**：`collateralOfOthersLoanIsNotFound`（404）、`saveCollateralSetsShares`、`unchangedThresholdsWriteNoRateSetting`（儲存兩次，`rate_setting` 中這筆借款的門檻列數不變）、`collateralOverHoldingsRejected`（訊息和資金來源頁相同）、`donePageCountsAndFinishes`。
- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑測試確認通過** → PASS
- [ ] **Step 5: 給使用者看 commit 訊息，同意後 commit**

---

### Task 8: 頁面最上方的提醒

**Files:**
- Create: `src/main/java/com/sideproject/stockledger/service/NoticeService.java`（只依賴 Repository 與 `CurrentUser`，不依賴其他 Service，避免循環依賴）：`List<PageNotice> overviewNotices()`（只有引導提醒）、`List<PageNotice> stockAndFundingNotices()`（引導提醒＋每筆沒有擔保股票、沒作廢的質押）
- Create: `src/main/java/com/sideproject/stockledger/dto/PageNotice.java`（`record PageNotice(String text, String url)`）
- Modify: `OverviewService`（`OverviewView` 加 `List<PageNotice> notices`）、`TradeService.view()`（`StocksView` 加 `notices`）、`LoanService.fundingView()`（`FundingView` 加 `notices`），各自呼叫 `NoticeService`
- Create: `src/main/resources/templates/fragments/notices.html`（標題下方，每則一列，可點）
- Modify: `templates/overview/index.html`、`stocks/index.html`、`funding/index.html`（插入 fragment）
- Test: `src/test/java/com/sideproject/stockledger/service/NoticeServiceTest.java`

文字（照設計文件 3.3）：`"第一次使用引導還有沒完成的步驟"` → `/welcome`（只在總覽、股票、資金來源）；`"「" + loan.getName() + "」還沒設定擔保股票，維持率無法計算"` → `/funding#loan-{id}`（只在股票、資金來源；`funding/index.html` 的卡片加 `id="loan-{id}"`）。

- [ ] **Step 1: 寫失敗的測試**：`noticeWhileOnboarding`、`noNoticeWhenDone`、`pledgeWithoutCollateralNotice`、`voidedPledgeHasNoNotice`、`noticeGoneAfterCollateralSet`。
- [ ] **Step 2: 跑測試確認失敗** → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑全部測試**：`./mvnw clean test` → 全過
- [ ] **Step 5: 問使用者可否套用 V12 到他的 Docker；同意後 `docker compose up -d --build`，用測試帳號（新建、沒資料）從登入走完整個引導並截圖給使用者**
- [ ] **Step 6: 給使用者看 commit 訊息，同意後 commit**

---

## 之後（不在這份計畫的程式範圍）

- 使用者要求清空他自己帳號的資料、改用測試帳號測試（2026-10-24）。要清哪個帳號、哪些表，動手前再跟使用者確認；清完把他的 `onboarding_step` 設回 `WELCOME`。這是資料操作，不寫成程式。
- 做完後幫使用者新建 009815（等上櫃 DR-049 實作後；見交接筆記）。
