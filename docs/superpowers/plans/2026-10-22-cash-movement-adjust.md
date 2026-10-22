# 階段一：調整「記一筆現金異動」＋備註搜尋 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 現金異動只留存入／支出／帳戶互轉，表單依類型顯示轉出、轉入帳戶，「說明」改「備註」，取消生活費類型（V7 轉換既有資料），全部異動頁可用備註搜尋所有月份。

**Architecture:** 資料表不變（`account_id` 存「錢動到的那個帳戶」，互轉時是轉出；`to_account_id` 只有互轉用）。表單改成 `fromAccountId`／`toAccountId` 兩欄，由 `CashMovementService` 依類型轉成資料表欄位。搜尋由 Repository 帶 `user_id` 查詢，`TotalAssetsService` 轉成列表列。

**Tech Stack:** Java 21、Spring Boot、Thymeleaf、MySQL 8.4、Flyway、JUnit 5、Testcontainers。

**Spec:** `docs/superpowers/specs/2026-10-22-recurring-and-roadmap-design.md` 第 3、7、8 節；`docs/spec.md` v1.5。

## Global Constraints

- V1～V6 已執行，不可修改；新 migration 是 `V7__remove_living_expense_type.sql`。
- 資料庫代碼仍是 `WITHDRAWAL`，畫面文字改「支出」；「說明」改「備註」；表單標籤後面不加括號說明。
- 每個查詢帶 `user_id`，只從 `CurrentUser` 取得。
- 金額 `BigDecimal`、整數元；Controller 與模板不做計算。
- 已執行到使用者 Docker 資料庫的 migration 不可修改；V7 套用到 Docker 資料庫前先問使用者。
- 不 commit、不 push，最後整批給使用者看。

## Review Focus

- 沒有 JavaScript 時兩個帳戶欄位都顯示：存入只選轉入帳戶、支出只選轉出帳戶都要能存；選錯欄位要有中文訊息，不是 500。→ Task 3 `depositUsesToAccountOnly`、`withdrawalWithToAccountRejected`。
- JavaScript 隱藏的欄位不可送出舊值（切換類型後殘留的選擇）。→ Task 3 app.js 隱藏時 `disabled`；瀏覽器手動驗證。
- 舊的生活費紀錄（含已作廢、備註已有內容）轉換後金額與餘額不變，並留下修改紀錄。→ Task 2 `V7MigrationTest`。
- 搜尋字含 `%`、`_` 不被當成萬用字元；只搜得到自己的資料。→ Task 4 `searchTreatsWildcardsLiterally`、`searchOnlyMine`。
- 修改舊的存入時，表單要把帳戶帶到「轉入帳戶」，不是轉出。→ Task 3 `editDepositPrefillsToAccount`。

---

### Task 1: 規格書 v1.6

**Files:**
- Modify: `docs/spec.md`（版本說明、第 3、4.3、4.6、6、8 節）

- [ ] **Step 1:** 依設計文件第 8 節擬好修改，寫入 `docs/spec.md`：版本 v1.6；第 3 節總覽與總資產頁拆解改為「存入、支出、餘額調整」，固定收支頁改寫（自動記帳、每月自己填、未填提醒），總資產頁加生活費未填提醒；4.3-1 拆解改用「支出」、去掉生活費，加「固定收支自動產生的異動計入」；4.6-2 改成存入／支出／帳戶互轉＋備註＋依類型顯示帳戶；新增 4.6-9 備註搜尋；第 6 節 CashMovement 類型與欄位、RecurringItem 欄位；第 8 節新增 DR-035、DR-036。
- [ ] **Step 2:** 全文搜尋「領出」「生活費」「說明」，確認沒有殘留舊用法（決策紀錄的舊條目保留原文）。

### Task 2: V7 取消生活費類型

**Files:**
- Create: `src/main/resources/db/migration/V7__remove_living_expense_type.sql`
- Modify: `entity/CashMovementType.java`（刪 `LIVING_EXPENSE`，`WITHDRAWAL` label 改「支出」）、`calculator/MonthlyCashChange.java`（刪 `livingExpenses`）、`calculator/CashLedgerCalculator.java`、`service/CashMovementService.java`、`service/TotalAssetsService.java`、`controller/CashMovementController.java`、`templates/assets/index.html`
- Test: Create `src/test/java/com/sideproject/stockledger/repository/V7MigrationTest.java`；Modify `CashLedgerCalculatorTest`、`CashMovementSchemaTest`、`CashMovementControllerTest`（刪生活費測試，改「支出」用詞）

**Interfaces:**
- Produces: `MonthlyCashChange(BigDecimal deposits, BigDecimal withdrawals, BigDecimal adjustments, BigDecimal total)`；`CashMovementType` = `OPENING, DEPOSIT, WITHDRAWAL, TRANSFER, RECONCILE`。

- [ ] **Step 1: 寫 `V7MigrationTest`**（不啟動 Spring；自己開一個 `MySQLContainer("mysql:8.4")`，Flyway `target("6")` → 插入資料 → `target("7")`）
  - `livingExpenseBecomesWithdrawal`：一筆生活費 27000、備註 null → 類型 `WITHDRAWAL`、備註「生活費」、金額 27000；修改紀錄兩列：`type` LIVING_EXPENSE→WITHDRAWAL、`note` null→生活費，reason 都是「生活費類型取消，改為支出（DR-035）」。
  - `existingNoteKept`：備註「10 月」→ 備註不變，只有 `type` 一列修改紀錄。
  - `voidedLivingExpenseAlsoConverted`：已作廢的也轉換，仍是作廢。
  - `typeCheckRejectsLivingExpense`：轉換後插入 `LIVING_EXPENSE` 被 `ck_cash_movement_type` 擋下。
- [ ] **Step 2:** 跑 `./mvnw test -Dtest=V7MigrationTest`，預期 FAIL（沒有 V7）。
- [ ] **Step 3: 寫 V7**：先 `INSERT … SELECT` 兩種修改紀錄（`revised_at` 用 `NOW()`，`action` = 'EDIT'），再 `UPDATE` 類型與空白備註，最後 DROP/ADD `ck_cash_movement_type`（不含 LIVING_EXPENSE）。
- [ ] **Step 4:** 刪除程式裡的 `LIVING_EXPENSE`，修正所有用到的地方；更新計算器測試（刪 `livingExpense…`）、schema 測試（`dbAcceptsLivingExpense` 改成 `dbRejectsLivingExpense`）、controller 測試（刪兩個生活費測試，`movementTypes` 只有三種）。
- [ ] **Step 5:** `./mvnw test` 全部通過。

### Task 3: 表單改版（支出、備註、轉出／轉入帳戶）

**Files:**
- Modify: `dto/CashMovementForm.java`、`service/CashMovementService.java`、`templates/assets/movement-form.html`、`static/js/app.js`、`service/TotalAssetsService.java`（描述用「備註」不變，只改註解）
- Test: `CashMovementServiceTest`、`CashMovementRevisionTest`、`CashMovementControllerTest`、`CashMovementDuplicateRaceTest`、`TotalAssetsServiceTest`（建表單的地方改新欄位）

**Interfaces:**
- Produces: `CashMovementForm(CashMovementType type, LocalDate date, Long fromAccountId, Long toAccountId, BigDecimal amount, String note)`；存入只用 `toAccountId`，支出只用 `fromAccountId`，互轉兩個都用。

- [ ] **Step 1: 寫測試**
  - Service `depositUsesToAccountOnly`：存入 `toAccountId=bank` → 存成 `account_id=bank`、`to_account_id=null`。
  - Service `depositWithFromAccountRejected` →「存入不用選轉出帳戶」；`withdrawalWithToAccountRejected` →「支出不用選轉入帳戶」；`depositWithoutToAccount` →「請選擇轉入帳戶」；`withdrawalWithoutFromAccount` →「請選擇轉出帳戶」。
  - Service `noteTooLong` 訊息改「備註最多 100 個字」。
  - Service `editDepositPrefillsToAccount`：`movementForm(id)` 對存入回傳 `fromAccountId=null, toAccountId=bank`；支出回傳 `fromAccountId=bank, toAccountId=null`。
  - Controller：新增頁有 `轉出帳戶`、`轉入帳戶`、`備註` 標籤，沒有「說明」「領出」；兩個帳戶 select 都有空白選項「請選擇」；有 `data-show-for` 屬性。
- [ ] **Step 2:** 跑測試，預期編譯失敗或 FAIL。
- [ ] **Step 3: 實作**
  - `CashMovementForm`：`fromAccountId`、`toAccountId` 都不加 `@NotNull`；`@Size` 訊息改「備註最多 100 個字」。
  - `validateAmountMovement`：依類型檢查必填與多餘欄位（訊息見 Step 1），回傳 `Accounts(from = 錢動到的帳戶, to = 互轉的轉入)`；`newForm()` 帳戶都是 null。
  - 模板：說明文字改成「存入：錢從系統外進來（例如薪水）。支出：錢離開系統（例如生活費、繳保險）。帳戶互轉：自己的兩個帳戶之間。」；標籤「類型」「日期」「轉出帳戶」「轉入帳戶」「金額」「備註」；每個帳戶欄位包在 `<div data-show-for="WITHDRAWAL TRANSFER">`／`<div data-show-for="DEPOSIT TRANSFER">`；修改原因標籤改「修改原因」，提示放 placeholder。
  - `app.js`：讀 `#type` 的值（新增是 select，修改是 hidden input），不符合的 `[data-show-for]` 加 `hidden`，裡面的 select 設 `disabled`（不送出）；`#type` 改變時重新套用。
- [ ] **Step 4:** `./mvnw test` 全部通過。

### Task 4: 備註搜尋

**Files:**
- Modify: `repository/CashMovementRepository.java`、`service/TotalAssetsService.java`、`controller/CashMovementController.java`、`templates/assets/movements.html`
- Test: `TotalAssetsServiceTest`、`CashMovementControllerTest`

**Interfaces:**
- Produces: `CashMovementRepository.findByUserIdAndNoteContainingIgnoreCaseOrderByMovementDateDescIdDesc(Long userId, String text)`；`CashMovementService.searchByNote(String text): List<CashMovement>`；`TotalAssetsService.search(String query): List<MovementRow>`（strip 後超過 50 字丟 `CashValidationException("搜尋字最多 50 個字")`）。

- [ ] **Step 1: 寫測試**
  - Service `searchAcrossMonthsNewestFirst`：9 月「保險」、10 月「年繳保險」、10 月「薪水」→ 搜「保險」得 2 筆，10 月在前。
  - `searchIgnoresCaseAndTrims`：備註「ETF 定期定額」，搜「  etf 」得 1 筆。
  - `searchIncludesVoided`：作廢的也列出，`voided()=true`。
  - `searchTreatsWildcardsLiterally`：備註「100%」「1000」，搜「%」只得「100%」。
  - `searchOnlyMine`：別人的「保險」不出現。
  - `searchTooLong`：51 字 →「搜尋字最多 50 個字」。
  - Controller `searchShowsResultsWithoutMonthNav`：`GET /assets/movements?q=保險` → 有結果、有「清除搜尋」連結、沒有月份切換；`q` 是空白 → 照舊顯示本月；太長 → 顯示錯誤訊息、不是 500。
- [ ] **Step 2:** 跑測試，預期 FAIL。
- [ ] **Step 3: 實作**：Controller `list(@RequestParam month, @RequestParam q)`：`q` 不是空白就呼叫 `totalAssets.search(q)`，model 放 `query`；模板上方加 GET 搜尋框（`name="q"`、`maxlength="50"`、placeholder「搜尋備註」），搜尋模式顯示「搜尋「x」的結果」與「清除搜尋」，沒結果顯示「沒有符合的異動」。
- [ ] **Step 4:** `./mvnw test` 全部通過。

### Task 5: 審查與驗收

- [ ] **Step 1:** 派 opus subagent 審查 Task 2～4 的 diff，修正 Critical／Important。
- [ ] **Step 2:** 問使用者可否把 V7 套用到 Docker 資料庫；同意後 `docker compose up -d --build`，用內建瀏覽器截圖講解（新增表單三種類型、修改存入、搜尋）。
- [ ] **Step 3:** 整理 diff 與 commit 建議給使用者，同意後才 commit。
