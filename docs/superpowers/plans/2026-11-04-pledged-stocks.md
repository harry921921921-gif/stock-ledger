# 已經質押的股票 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增質押借款時一起記「還沒記在系統裡的質押股票」（期初持股＋擔保，價格預設證交所當天收盤價）；質押中的股票照算淨資產並分開標示；還清後自動解除質押；質押借款改用「還款／到期處理」（現金、賣股票還款、展延）。

**Architecture:** 新的 `PledgeStatusService`（只依賴 Repository、`LoanFactsReader`、`CurrentUser`）集中判斷「哪些借款還在質押中（剩餘本金 > 0、沒作廢）」與各股票的質押股數，TradeService、LoanService、NoticeService、畫面都用它。歷史收盤價由 `MarketDataService.closeOn` 依需要向證交所個股日成交資訊抓整個月並存進 `stock_price`。新增借款的質押股票與還款處理都在 LoanService 的單一交易內呼叫 TradeService，規則只有一份。

**Tech Stack:** Java 21、Spring Boot、Spring Data JPA、Thymeleaf、RestClient、MySQL 8.4（Testcontainers）、JUnit 5、MockMvc。

**Spec:** `docs/superpowers/specs/2026-11-04-pledged-stocks-design.md`（全文，含 6a）。

## Global Constraints

- 套件 `com.sideproject.stockledger`；Controller 不碰 Repository、不計算；模板不計算；user_id 只從 `CurrentUser`。
- 金額 `BigDecimal`、明確 `RoundingMode`；不硬刪交易與現金異動；每個改到的欄位寫 revision。
- 不新增 Flyway migration（設計文件已確認不需要）。
- 新增 JavaScript：不新增；表格加列、拿掉、帶入收盤價都用表單按鈕送到伺服器、伺服器畫回來，打到一半的字保留（和引導一致）。
- 抓不到收盤價時留空，絕不猜數字；成功與失敗都寫 `price_update_log`（kind `HISTORY`）。
- 測試一律 `./mvnw clean test`，連網路的部分用假的 `TwseClient`（和現有測試一樣），不真的連證交所。
- 執行時：不逐 task commit，最後一次給使用者看 commit 訊息；不 push；改到使用者 Docker 前先問（這個計畫沒有 migration，重建 Docker 仍要先問）。

## Review Focus

1. 還清後又作廢那筆還本（或再動撥）：股票重新變回「質押中」、賣出又被擋 → Task 3 `pledgeComesBackWhenRepaymentVoided`。
2. 質押股票那一列的代號已經有任何交易（包括只有買進）：整筆借款不存、訊息正確、沒有留下半套資料 → Task 5 `existingStockRowRejectsWholeLoan`。
3. 賣股票還款時，賣出股數超過擔保股數或持股：整個還款處理不存 → Task 7 `sellMoreThanPledgedRejected`。
4. 那天休市或證交所連不上：價格欄空著、說明原因、不寫任何價格 → Task 2 `closeOnHolidayIsEmpty`、`closeOnNetworkErrorIsEmptyAndLogged`。
5. 別人的借款 id 打在 `/funding/{id}/settle`：404 → Task 7 `settleOthersLoanIsNotFound`。

---

### Task 1: 規格書、決策紀錄、CLAUDE.md

**Files:** Modify `docs/spec.md`（版本 v1.13、4.8a 第 3 點補充、5.5、7.1、第 8 節 DR-052／053／054 在 DR-051 上方）、`CLAUDE.md`「外部資料」第一條補「過去某一天的收盤價用證交所個股日成交資訊（DR-052）」。

- [ ] 照設計文件第 2、3、5、6、6a 節寫入；DR-052 原因：OpenAPI 只有最新一天、使用者質押股票看不到成本；放棄：手動輸入、AI 查價。DR-053、DR-054 的原因與放棄選項照設計文件與對話（使用者 2026-11-04 決定）。
- [ ] 不 commit（最後一起）。

---

### Task 2: 歷史收盤價

**Files:**
- Modify: `service/TwseClient.java`（`String monthlyQuotesJson(String code, YearMonth month)`）、`service/HttpTwseClient.java`（第二個 RestClient，base URL `${app.twse.site-url:https://www.twse.com.tw}`，路徑 `/exchangeReport/STOCK_DAY?response=json&date=YYYYMM01&stockNo=代號`）、`service/TwseParser.java`（`static List<DailyQuote> parseMonthlyQuotes(String code, String json)`：`data` 每列 `[日期(民國 115/07/28), …, 收盤價(第 7 欄, index 6), …]`；收盤價不是數字（`--`）就略過；`stat` 不是 `OK` 回空清單）、`service/MarketDataService.java`、`entity/PriceUpdateLog.java`（`HISTORY = "HISTORY"`）
- Test: `service/TwseParserTest.java`、`service/MarketDataServiceTest.java`

**Interfaces:**
- Produces: `MarketDataService.closeOn(String stockCode, LocalDate date) → Optional<BigDecimal>`：先查 `stock_price`；沒有就抓那個月、把當月每天的收盤價存進 `stock_price`（已經有的那天不覆蓋）、寫 log；那天沒有資料回空；任何例外回空並寫失敗 log。`@Transactional` 與現有 refresh 方法同樣的寫法。

- [ ] **Step 1: 失敗的測試**：`parseMonthlyQuotes` 用 2026-07 的 00646 真實格式片段（`["115/07/28","11,632,330","875,670,735","75.40","75.50","75.15","75.15","-1.05","4,829",""]`）→ 2026-07-28 收盤 75.15；`closeOnUsesStoredPrice`（已有 stock_price 不呼叫 client）、`closeOnFetchesMonthAndStores`（假 client 回兩天 → 存兩列、回 75.15）、`closeOnHolidayIsEmpty`（那天不在資料裡 → 空、不存那天）、`closeOnNetworkErrorIsEmptyAndLogged`（client 丟例外 → 空、log 一筆 HISTORY 失敗）。
- [ ] **Step 2: 跑**：`./mvnw clean test -Dtest=TwseParserTest,MarketDataServiceTest` → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑** → PASS

---

### Task 3: 質押狀態由事件推算

**Files:**
- Create: `service/PledgeStatusService.java`
- Modify: `service/TradeService.java`（`checkCollateral` 用它）、`service/LoanService.java`（`checkCollateral`、`collateralOfOtherLoans`、`fundingView` 卡片：已還清的質押顯示「已還清，擔保股票已解除質押」、不算維持率）、`service/NoticeService.java`（「還沒設定擔保股票」只對還在質押中的借款）、`dto/LoanCard.java`（加 `boolean collateralReleased`）、`templates/funding/index.html`
- Test: `service/PledgeStatusServiceTest.java`、`service/FundingStocksTest.java`

**Interfaces:**
- Produces（只依賴 Repository、`LoanFactsReader`、`CurrentUser`、`Clock`，避免循環依賴）：
  - `Set<Long> activePledgeLoanIds()`：沒作廢、而且到今天剩餘本金 > 0 的借款 id（剩餘本金和 `LoanService.remainingPrincipal` 同一個算法，抽到 `LoanFactsReader` 或共用的地方，不要寫第二份）。
  - `Map<Long, Long> pledgedSharesByStock()`：只算上面那些借款的擔保列。
  - `long pledgedSharesOtherThan(Long loanId, Long stockId)`。

- [ ] **Step 1: 失敗的測試**：`repaidLoanDoesNotBlockSell`（質押 1,000 股、全部還清 → 賣 1,000 股成功）、`pledgeComesBackWhenRepaymentVoided`（再作廢那筆還本 → 賣出被擋，原訊息）、`repaidCardShowsReleased`（卡片 `collateralReleased` true、維持率 null）、`noticeSkipsRepaidPledge`。
- [ ] **Step 2: 跑** `./mvnw clean test -Dtest=PledgeStatusServiceTest,FundingStocksTest,NoticeServiceTest` → FAIL
- [ ] **Step 3: 實作**
- [ ] **Step 4: 跑** → PASS，再跑全部

---

### Task 4: 質押中照算淨資產、分開標示

**Files:** Modify `dto/HoldingRow.java`（加 `long pledgedShares`）、`service/TradeService.java`（`view()` 填入，用 `PledgeStatusService.pledgedSharesByStock()`）、`templates/stocks/index.html`（名稱旁「質押中 N 股」標籤，N>0 才顯示）、`dto/TotalAssetsView.java`（加 `BigDecimal pledgedValue`）、`service/TotalAssetsService.java`（Σ 質押股數 × 最新收盤價，沒有收盤價的那檔不算）、`templates/assets/index.html`（股票市值下方「其中質押中的股票」）。
**Test:** `controller/StockControllerTest.java`、`service/TotalAssetsServiceTest.java`

- [ ] **Step 1: 失敗的測試**：`holdingShowsPledgedShares`（頁面含「質押中 1,000 股」）、`pledgedValueShownButTotalUnchanged`（質押 1,000 股 × 60 → pledgedValue 60000，totalAssets 和沒質押時相同）、還清後標籤消失。
- [ ] **Step 2–4:** 跑 → FAIL → 實作 → PASS

---

### Task 5: 新增借款時記質押股票（資金來源頁）

**Files:**
- Create: `dto/NewPledgeLine.java`（`record NewPledgeLine(String stockCode, Long shares, LocalDate date, BigDecimal price, String priceNote)`；`priceNote` 由 Service 填，例如「證交所 2026-07-28 收盤價」或「那天沒有收盤價（休市或還沒有資料），請自己填」）
- Modify: `dto/LoanForm.java`（加 `List<NewPledgeLine> newPledges`，舊建構子補空清單）、`service/LoanService.java`（`create`：同一交易建借款 → 每列 `TradeService.create(OPENING…)`（日期＝質押日期、股數、平均成本＝價格、沒有帳戶、沒有資金來源）→ `setCollateral(股數)`；新增 `List<NewPledgeLine> fillPrices(List<NewPledgeLine>)` 對價格空白、代號與日期都有的列呼叫 `MarketDataService.closeOn`）、`service/LoanService.java` `voidLoan` 成功訊息加「質押時記的期初持股沒有刪，要刪請到股票頁作廢」（由 Service 回傳是否有這種持股不需要：一律在有擔保列的借款作廢時加這句）、`controller/FundingController.java`（`POST /funding/new/pledges?action=add|remove&index=i|prices`：存表單內容、改清單、畫回 `funding/loan-form`，不驗證不存）、`templates/fragments/loan-fields.html`（勾股票質押後的第二個表格；說明文字照設計文件 4.1）
- Test: `service/PledgedLoanCreateTest.java`、`controller/FundingControllerTest.java`

**檢查（在 `LoanService` 的共用 `check`，`validate` 與 `create` 同一份）：** 代號是系統裡的股票（原訊息）；股數正整數；**這檔有任何沒作廢的交易 → 「這檔已經記在系統裡了，請在上面的持股清單勾選」**；同一檔不能兩列；價格 > 0（「請輸入價格」）。

- [ ] **Step 1: 失敗的測試**：`createWithNewPledgesRecordsOpeningAndCollateral`（兩列 00646 3,000 股 75.15、00662 2,000 股 115.70，日期 2026-07-28 → 兩筆 OPENING、兩筆擔保、所有帳戶餘額不變、沒有 source_allocation）、`existingStockRowRejectsWholeLoan`（00646 已有一筆買進 → 例外、loan／trade／pledge_collateral 筆數都沒變）、`duplicateRowRejected`、`fillPricesUsesClose`（假 client → 75.15、priceNote「證交所 2026-07-28 收盤價」）、`fillPricesHolidayLeavesBlank`、controller：`addRowKeepsTyping`、`pricesButtonFillsAndKeepsOtherFields`、`voidMessageMentionsPledgedOpenings`。
- [ ] **Step 2–4:** 跑 `./mvnw clean test -Dtest=PledgedLoanCreateTest,FundingControllerTest` → FAIL → 實作 → PASS

---

### Task 6: 引導的借款步驟也能記質押股票

**Files:** Modify `service/OnboardingService.java`（`rowsFor(LoanForm)` 每檔一行「質押的股票　00646 3,000 股（2026-07-28，每股 75.15）」；`checkLoan` 走 Task 5 的同一份檢查）、`controller/OnboardingController.java`（`/welcome/loans/pledges?action=…` 同 Task 5 的加列／拿掉／帶入價格，沿用引導草稿、stash 機制）、`templates/onboarding/loans.html`（引導只顯示第二個表格「還沒記在系統裡的質押股票」，第一個「從持股勾選」仍在第 4 步）。
**Test:** `controller/OnboardingControllerTest.java`

- [ ] **Step 1: 失敗的測試**：`wizardLoanRecordsNewPledges`（確認畫面含「質押的股票　00646 3,000 股（2026-07-28，每股 75.15）」；存檔後第 4 步擔保頁 00646 已勾、擔保 3,000）、`wizardPledgeRowsSurviveBack`（上一步再回來，列還在）。
- [ ] **Step 2–4:** 跑 → FAIL → 實作 → PASS

---

### Task 7: 質押借款的還款／到期處理

**Files:**
- Create: `dto/SettleForm.java`（`boolean cash`、`LocalDate date`、`Long accountId`、`BigDecimal principal`、`BigDecimal interest`；`boolean sell`、`Long settlementAccountId`、`List<SettleSale> sales`（`record SettleSale(Long stockId, Long shares, BigDecimal price, BigDecimal fee, BigDecimal tax, BigDecimal amount)`，fee／tax／amount 空白時自動算）、`BigDecimal saleRepay`；`boolean extend`、`LocalDate newDueDate`）、`dto/SettlePreview.java`（確認畫面每一行文字與金額，由 Service 算好）、`templates/funding/settle.html`
- Modify: `service/LoanService.java`（`SettleForm settleDefaults(Long id)`：帶入剩餘本金、到今天應計利息、擔保股票清單；`SettlePreview previewSettle(Long id, SettleForm)`：只驗證不存；`void settle(Long id, SettleForm)`：`@Transactional`，順序：每筆賣出 `TradeService.create(SELL…)` → 擔保股數減掉賣出股數（`setCollateral`）→ 賣股還款記一筆「借款還本」（同日、交割戶、金額 `saleRepay`，預設＝賣出實際入帳合計，不超過剩餘本金）→ 現金還款沿用 `repay`（本金＋利息）→ 展延沿用 `edit` 只改到期日；任一步失敗整筆回滾）、`controller/FundingController.java`（`GET /funding/{id}/settle`、`POST /funding/{id}/settle/preview`、`POST /funding/{id}/settle`（FormTokens，按兩次只存一次；確認後才存，同引導的「存的就是確認過的」：preview 把參數存在 session、存檔只用它））、`templates/funding/index.html`（質押借款卡片的「還本」換成「還款／到期處理」；信貸維持「還本」）
- Test: `service/LoanSettleTest.java`、`controller/FundingControllerTest.java`

**規則：** 至少勾一項；還清（剩餘本金變 0）時不能同時展延（「已經還清，不需要展延」）；賣出股數 ≤ 這筆借款的擔保股數（「賣出的股數超過擔保股數」）且照賣出原有規則（持股、證交稅）；`saleRepay` ≤ 賣出實際入帳合計且 ≤ 剩餘本金；展延日要晚於今天與原到期日。

- [ ] **Step 1: 失敗的測試**：`cashSettlePaysOff`（10 萬 BULLET、現金還本 100,000＋利息 → 剩餘本金 0、卡片已還清）、`sellSettleRecordsSaleAndRepay`（賣 00646 1,000 股 × 76 → 一筆 SELL、擔保少 1,000、同日一筆借款還本＝實際入帳、交割戶餘額淨額 0）、`cashAndSellTogether`、`extendChangesDueDateOnly`（沒有任何現金異動、到期日改了）、`sellMoreThanPledgedRejected`（整筆沒存）、`settleOthersLoanIsNotFound`、`settleSaveTwiceSavesOnce`、`creditLoanStillHasRepay`（信貸卡片仍是「還本」）。
- [ ] **Step 2–4:** 跑 `./mvnw clean test -Dtest=LoanSettleTest,FundingControllerTest` → FAIL → 實作 → PASS，再跑全部。
- [ ] **Step 5:** 全部測試通過後，問使用者要不要重建 8080 的 Docker 試用（沒有 migration）；試用前整理 commit 訊息給使用者看。
