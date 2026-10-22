# 階段二：固定收支自動記帳 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **本專案額外規則（CLAUDE.md）：** 程式由 Claude 寫；每個 Task 先寫測試再實作，做完派 opus subagent 審查並修正 Critical／Important；問題累積一次給使用者看；**commit 前給使用者看並經同意，絕不 `git push`**；V8 套用到使用者的 Docker 資料庫前先問。

**Goal：** 使用者在「固定收支」頁設定薪水、孝親費、保險、生活費等項目；固定金額的項目到了入帳日自動記成現金異動，「每月自己填」的項目（生活費）由使用者每月填金額，入帳日過了還沒填就在總資產頁提醒。

**Architecture：** 新表 `recurring_item` 存設定（不是事件，修改直接更新、不寫修改紀錄、不刪除只填結束月份）。自動產生的是一般的 `cash_movement`（存入或支出），多兩個欄位 `recurring_item_id`、`period`（該期所在月份的 1 號），資料庫保證同一項目同一期最多一筆沒作廢的。「哪幾期到期、哪天入帳、月平均」由純 Java 的 `RecurringScheduleCalculator` 算。系統排程 `RecurringPostingService` 逐一處理每個使用者的項目（DR-036），啟動時與每天 00:10 執行。

**Tech Stack：** Java 21、Spring Boot、Spring Security、Thymeleaf、MySQL 8.4、Flyway、JUnit 5、Testcontainers。

**Spec：** `docs/superpowers/specs/2026-10-22-recurring-and-roadmap-design.md` 第 4、6、7 節；`docs/spec.md` v1.6（第 3 節第 5 頁、4.3-5、DR-035、DR-036）；設計稿 https://claude.ai/artifact/WHJdwUeLPEoAYjHKAtiuXx 的 `Recurring`（版面參考；「固定結餘」「接下來的大筆支出」卡片這一版不做，見「這一版的判斷」）。

## Global Constraints

- 套件 `com.sideproject.stockledger`；Controller 不呼叫 Repository；Controller 與模板不做計算（月平均、下次入帳日、未填期數都由 Service／Calculator 算好）。
- 金額 `BigDecimal`、資料庫 `DECIMAL(19,4)`、只收整數元、上限 1 兆（同 `CashMovementService`）；月平均中間值 scale 10 `HALF_UP`，顯示時 `setScale(0, HALF_UP)`。
- 日期 `DATE`；「今天」「本月」一律用注入的 `Clock`（Asia/Taipei）；排程 `zone = "Asia/Taipei"`。
- 個人資料表都有 `user_id`；使用者操作的查詢一律帶 `CurrentUser.id()`。**唯一例外**是 `RecurringPostingService` 的排程入口（DR-036）：只讀寫每個項目自己的 `user_id`，不接受任何外部輸入。
- 所有關聯建外鍵，外鍵連同 `user_id`（比照 V5）；不用多型關聯；不硬刪除。
- Schema 只能新增 migration：本計畫用 `V8__create_recurring_item.sql`；V1～V7 不可修改。
- 錯誤訊息中文；表單載入 `/js/app.js`、送出按鈕有 `data-busy-text`；新增項目與填金額帶一次性表單代號（`FormTokens`）。
- 不新增任何套件。

## 這一版的判斷（設計文件沒寫死的，做完一起給使用者確認）

1. **每月自己填只能配「每月」週期**（資料庫 CHECK）。設計文件只有生活費這種用法。
2. **半年與每年的月份**存在 `anchor_month`（1～12）：每年 = 那個月；每半年 = 那個月和 6 個月後。和「開始月份」分開，開始月份只決定從哪一期開始。
3. **修改項目**只能改名稱、金額、入帳日、月份、帳戶、結束月份；收入或支出、金額方式、週期、開始月份不能改（要改就填結束月份、另建一個）。改了只影響還沒產生的期數。
4. **填這期金額的日期** = `min(那一期的入帳日, 今天)`：過去的期數用入帳日；本月還沒到入帳日就先填，用今天（仍在本月），不會產生未來日期。
5. **總資產頁提醒**：入帳日 ≤ 今天、還沒填的期數（入帳日當天就提醒）。固定收支頁的「未填」列另外也列出本月還沒到入帳日的那一期，可以提早填。
6. **補記提示**：表單上固定說明「開始月份選過去的月份，會補記已到期的入帳」；存檔後訊息寫「已新增，補記了 N 筆過去的入帳」（不做送出前即時計算，避免加 JavaScript 計算）。
7. **卡片**：每月固定收入、每月固定支出（月平均）、本月自己填（已填金額合計；有沒填的列出名稱）。設計稿的「固定結餘」「接下來的大筆支出」這一版不做（規格 v1.6 第 3 節已不列）。
8. **沒有設定初始現金餘額的帳戶**照樣產生（和手動記帳的規則一致：只有設了初始現金餘額才擋更早的日期）。

## Review Focus

1. **同一期記兩次**：啟動補記與 00:10 排程重疊、或連點建立項目 → 每期最多一筆沒作廢的（Task 2 `dbRejectsSecondActivePeriod`、Task 3 `postingTwiceCreatesOnce`）。
2. **作廢後又冒出來**：固定金額的那期作廢後再跑排程 → 不補（Task 3 `voidedFixedPeriodNotRegenerated`）；每月自己填的作廢後 → 回到未填、可以重填（Task 4 `voidedManualPeriodCanBeRefilled`）。
3. **月底與閏年**：31 號在 2 月、30 號在 2 月、閏年 2/29 → 該月最後一天（Task 1 `dayClampedToMonthEnd`）。
4. **開始月份在過去／早於初始現金餘額／有結束月份** → 只補到期的、略過初始現金餘額之前的、結束月份之後不產生，也不產生未來日期（Task 3 `backfillSkipsBeforeOpeningAndAfterEnd`、`neverPostsFutureDates`）。
5. **看到或改到別人的項目**：別人的項目 id 修改、填金額、看紀錄 → 404；排程替 A 產生的異動屬於 A（Task 3 `postsForEveryUserIntoOwnAccounts`、Task 5 `otherUsersItemIs404`）。

---

### Task 1：RecurringScheduleCalculator（純 Java）

**Files：**
- Create: `src/main/java/com/sideproject/stockledger/entity/RecurringCycle.java`（放 entity，和 `CashMovementType` 一樣是純 Java enum，calculator 可以引用；enum `MONTHLY("每月")`, `SEMIANNUAL("每半年")`, `YEARLY("每年")`，有 `label()`）
- Create: `src/main/java/com/sideproject/stockledger/calculator/RecurringRule.java`
- Create: `src/main/java/com/sideproject/stockledger/calculator/DuePeriod.java`
- Create: `src/main/java/com/sideproject/stockledger/calculator/RecurringScheduleCalculator.java`
- Test: `src/test/java/com/sideproject/stockledger/calculator/RecurringScheduleCalculatorTest.java`

**Interfaces：**
- Produces:
  - `record RecurringRule(RecurringCycle cycle, Integer anchorMonth, int dayOfMonth, YearMonth startMonth, YearMonth endMonth)`（`anchorMonth` 只有半年、每年用；`endMonth` 可為 null）
  - `record DuePeriod(YearMonth period, LocalDate dueDate)`
  - `static List<DuePeriod> RecurringScheduleCalculator.periodsThrough(RecurringRule rule, YearMonth lastMonth)`：從開始月份到 `min(lastMonth, endMonth)` 的每一期，舊的在前
  - `static List<DuePeriod> dueOnOrBefore(RecurringRule rule, LocalDate today)`：入帳日 ≤ today 的期數
  - `static Optional<LocalDate> nextDueAfter(RecurringRule rule, LocalDate today)`：第一個入帳日 > today 的期數；已結束則 empty
  - `static BigDecimal monthlyAverage(RecurringCycle cycle, BigDecimal amount)`：每月 = amount；每半年 ÷ 6；每年 ÷ 12；scale 10 `HALF_UP`

- [ ] **Step 1：寫測試**
  - `monthlyPeriods`：每月 5 號、開始 2026-08、today 2026-10-04 → `dueOnOrBefore` 得 08-05、09-05（10-05 還沒到）。
  - `dayClampedToMonthEnd`：31 號 → 2026-02 是 02-28、2028-02 是 02-29、2026-04 是 04-30；30 號在 2026-02 是 02-28。
  - `semiannualFromAnchor`：每半年、anchor 1、10 號、開始 2026-01 → 期別 2026-01、2026-07、2027-01；開始 2026-03 → 第一期 2026-07。
  - `yearly`：每年、anchor 3、15 號、開始 2026-01 → 2026-03-15、2027-03-15。
  - `endMonthStops`：結束 2026-09 → today 2026-12-31 只到 09；`nextDueAfter` empty。
  - `startInFutureHasNoDue`：開始 2026-11、today 2026-10-22 → `dueOnOrBefore` 空，`nextDueAfter` = 2026-11-05。
  - `monthlyAverage`：每年 10,932 → 911.0000000000；每半年 9,600 → 1,600；每月 8,000 → 8,000。
  - `ruleValidation`：建立 `RecurringRule` 時 day 0／32、MONTHLY 帶 anchor、YEARLY 沒 anchor、end 早於 start → `IllegalArgumentException`。
- [ ] **Step 2：** `./mvnw test -Dtest=RecurringScheduleCalculatorTest` → 編譯失敗。
- [ ] **Step 3：實作**（`YearMonth.atDay(min(day, lengthOfMonth()))`；半年的期別是「與 anchor 月份相差 6 的倍數」的月份）。
- [ ] **Step 4：** 測試通過。

### Task 2：V8、RecurringItem entity、CashMovement 新欄位

**Files：**
- Create: `src/main/resources/db/migration/V8__create_recurring_item.sql`
- Create: `entity/RecurringItem.java`、`entity/RecurringDirection.java`（`INCOME("收入")`, `EXPENSE("支出")`；`movementType()` → `DEPOSIT`／`WITHDRAWAL`）、`entity/AmountMode.java`（`FIXED("固定金額")`, `MANUAL("每月自己填")`）
- Create: `repository/RecurringItemRepository.java`
- Modify: `entity/CashMovement.java`（`recurringItemId`、`period` 兩欄，`updatable = false`；新工廠 `ofRecurring(...)`）、`repository/CashMovementRepository.java`
- Test: Create `repository/RecurringItemSchemaTest.java`

**V8 內容：**
- `recurring_item`：`id`, `user_id`, `name VARCHAR(50)`, `direction VARCHAR(10)`, `amount_mode VARCHAR(10)`, `amount DECIMAL(19,4) NULL`, `cycle VARCHAR(12)`, `anchor_month TINYINT NULL`, `day_of_month TINYINT`, `account_id`, `start_month DATE`, `end_month DATE NULL`, `created_at DATETIME`；`UNIQUE (id, user_id)`；外鍵 `(account_id, user_id) → cash_account (id, user_id)`；CHECK：方向、金額方式、週期的代碼；`FIXED` 金額 > 0 且整數、`MANUAL` 金額 NULL 且週期 MONTHLY；`MONTHLY` anchor NULL、其他 anchor 1～12；day 1～31；`start_month`、`end_month` 是 1 號；`end_month >= start_month`。
- `cash_movement` 加 `recurring_item_id BIGINT NULL`、`period DATE NULL`；外鍵 `(recurring_item_id, user_id) → recurring_item (id, user_id)`；CHECK 兩欄同時有或同時沒有、有的話類型是 DEPOSIT 或 WITHDRAWAL、`period` 是 1 號；產生欄位 `recurring_active_item_id = CASE WHEN recurring_item_id IS NOT NULL AND voided = FALSE THEN recurring_item_id END STORED`，`UNIQUE (recurring_active_item_id, period)`。

**Interfaces：**
- Produces:
  - `RecurringItem.create(Long userId, String name, RecurringDirection direction, AmountMode mode, BigDecimal amount, RecurringCycle cycle, Integer anchorMonth, int dayOfMonth, Long accountId, YearMonth startMonth, YearMonth endMonth, LocalDateTime now)`；`change(String name, BigDecimal amount, Integer anchorMonth, int dayOfMonth, Long accountId, YearMonth endMonth)`；`rule(): RecurringRule`（資料表存 `LocalDate` 1 號，getter 轉成 `YearMonth`）
  - `RecurringItemRepository`：`save`、`findByIdAndUserId`、`findByUserIdOrderByDirectionAscNameAsc(Long userId)`、`findAllActiveForPosting(LocalDate firstOfMonth)`（DR-036 唯一不帶 user_id 的查詢：結束月份是 null 或 ≥ 那個月 1 號的所有項目）
  - `CashMovement.ofRecurring(Long userId, RecurringItem item, LocalDate date, BigDecimal amount, YearMonth period, LocalDateTime now)`（類型依方向、備註 = 項目名稱、帳戶 = 項目帳戶）；`getRecurringItemId()`、`getPeriod()`
  - `CashMovementRepository`：`existsByUserIdAndRecurringItemIdAndPeriod(Long userId, Long itemId, LocalDate period)`（含作廢的）、`findByUserIdAndRecurringItemIdAndVoidedFalse(Long userId, Long itemId)`、`findByUserIdAndRecurringItemIdOrderByMovementDateDescIdDesc(Long userId, Long itemId)`

- [ ] **Step 1：寫 `RecurringItemSchemaTest`**（比照 `CashMovementSchemaTest`，用 JdbcTemplate 直接寫入）
  - `dbRejectsSecondActivePeriod`：同項目同期兩筆沒作廢的 → `uk_cash_movement_recurring_period`；第一筆作廢後可以再寫一筆。
  - `dbRejectsManualWithAmountOrNonMonthly`、`dbRejectsFixedWithoutAmount`、`dbRejectsBadAnchorAndDay`、`dbRejectsEndBeforeStart`、`dbRejectsPeriodNotFirstDay`。
  - `dbRejectsOtherUsersAccountOrItem`：項目用別人的帳戶、異動指向別人的項目 → 外鍵錯誤。
  - `dbRejectsRecurringTransfer`：`recurring_item_id` 配 TRANSFER → CHECK 錯誤。
- [ ] **Step 2：** 跑測試 → FAIL（沒有表）。
- [ ] **Step 3：** 寫 V8、entity、enum、repository；既有測試的清表順序加上 `recurring_item`（在 `cash_movement` 之後、`cash_account` 之前）。
- [ ] **Step 4：** `./mvnw test` 全部通過（Hibernate `validate` 也會檢查 entity 對得上）。

### Task 3：RecurringItemService（新增、修改、結束）與自動記帳

**Files：**
- Create: `dto/RecurringItemForm.java`、`service/RecurringItemService.java`、`service/RecurringPostingService.java`、`service/RecurringValidationException.java`、`config/SchedulingConfig.java`（`@EnableScheduling`）
- Test: Create `service/RecurringItemServiceTest.java`、`service/RecurringPostingServiceTest.java`

**Interfaces：**
- Consumes: Task 1 calculator；Task 2 entity／repository。
- Produces:
  - `record RecurringItemForm(String name, RecurringDirection direction, AmountMode amountMode, BigDecimal amount, RecurringCycle cycle, Integer anchorMonth, Integer dayOfMonth, Long accountId, YearMonth startMonth, YearMonth endMonth)`（`@DateTimeFormat(pattern = "yyyy-MM")` 給月份）
  - `RecurringItemService.create(RecurringItemForm form): Created`（`record Created(RecurringItem item, int backfilled)`）、`edit(Long id, RecurringItemForm form): RecurringItem`、`find(Long id)`、`listMine()`、`newForm()`（方向支出、固定金額、每月、開始月份 = 本月）、`form(Long id)`
  - `RecurringPostingService.postDue(): int`（排程：全部使用者）、`postDue(RecurringItem item, LocalDate today): int`（單一項目；新增項目時由 `create` 呼叫）
  - 觸發：`@EventListener(ApplicationReadyEvent.class)` 與 `@Scheduled(cron = "0 10 0 * * *", zone = "Asia/Taipei")` 都呼叫 `postDue()`；每個項目各自一個交易（`TransactionTemplate`），一個失敗只記 `log.error`（含項目 id）並繼續下一個；唯一限制衝突（同時跑兩次）視為已產生、略過。

**規則：**
- 檢查（中文訊息）：名稱必填、去前後空白、最多 50 字；固定金額 > 0 整數、< 1 兆；每月自己填不填金額、只能每月；入帳日 1～31；半年與每年要選月份；帳戶是自己的（別人的 → `NotFoundException`）；開始月份 2000-01 以後；結束月份不早於開始月份；修改時不能改方向、金額方式、週期、開始月份（表單帶了不同值 →「要改收入／支出、金額方式、週期或開始月份，請結束這個項目後另建一個」）。
- 只處理 `FIXED` 項目；期數 = `dueOnOrBefore(rule, today)`；略過「已經有異動（含作廢）」的期數；略過入帳日早於該帳戶初始現金餘額日期的期數（不算已產生）；入帳日早於 2000-01-01 不會發生（開始月份已擋）。
- 產生的異動：`CashMovement.ofRecurring(item.userId, item, dueDate, item.amount, period, now)`。

- [ ] **Step 1：寫測試**
  - Service：`createValidatesFields`（上列每條訊息各一個斷言）、`otherUsersAccountIsNotFound`、`createBackfillsPastPeriods`（開始月份 = 3 個月前、每月 1 號 → `backfilled` = 3 或 4（依今天是否 ≥ 1 號，寫成用 calculator 算出的期待值）、異動備註是項目名稱、類型依方向）、`editOnlyAffectsFuture`（改金額後已產生的不變，下一期用新金額）、`editCannotChangeCycleOrDirection`、`endMonthStopsPosting`、`listOnlyMine`。
  - Posting（用可設定的 `MutableClock` 測試 bean，或直接呼叫 `postDue(item, today)` 傳日期）：`postingTwiceCreatesOnce`、`voidedFixedPeriodNotRegenerated`、`manualItemsNotPosted`、`backfillSkipsBeforeOpeningAndAfterEnd`、`neverPostsFutureDates`、`postsForEveryUserIntoOwnAccounts`（兩個使用者各一個項目，沒有登入者也能跑，異動的 `user_id` 是各自的）、`oneBrokenItemDoesNotStopOthers`（第一個項目的帳戶設了晚於入帳日的初始現金餘額 → 略過，第二個照樣產生）。
- [ ] **Step 2：** 跑測試 → 編譯失敗。
- [ ] **Step 3：實作。**
- [ ] **Step 4：** `./mvnw test` 全部通過。

### Task 4：每月自己填與未填提醒

**Files：**
- Create: `dto/FillForm.java`（`YearMonth period`, `BigDecimal amount`）、`dto/UnfilledPeriod.java`（`Long itemId, String itemName, YearMonth period, LocalDate dueDate, boolean overdue`）
- Modify: `service/RecurringItemService.java`
- Test: `service/RecurringItemServiceTest.java`

**Interfaces：**
- Produces:
  - `RecurringItemService.unfilled(): List<UnfilledPeriod>`：所有 `MANUAL`、沒結束（或結束月份 ≥ 該期）項目，從開始月份到本月、沒有「沒作廢的異動」的期數；略過入帳日早於帳戶初始現金餘額的；`overdue = dueDate ≤ today`；舊的在前。
  - `RecurringItemService.overdueReminders(): List<UnfilledPeriod>`：上面 `overdue = true` 的（總資產頁用）。
  - `RecurringItemService.fill(Long itemId, FillForm form): CashMovement`：只限 `MANUAL`；期別必須在 `unfilled()` 裡（否則「這一期已經填過」或「這一期不用填」）；金額 > 0 整數；日期 = `min(dueDate, today)`；同時連點由唯一限制擋下，轉成「這一期已經填過」。

- [ ] **Step 1：寫測試**
  - `unfilledListsPastAndCurrentMonth`：生活費每月 31 號、開始 2 個月前 → 3 期未填；前兩期 `overdue`，本月依今天是否月底。
  - `fillUsesDueDateOrToday`：過去期別 → 日期 = 該期入帳日（例如 2026-09-30）；本月 → 日期 = 今天。
  - `fillTwiceRejected`、`fillFixedItemRejected`、`fillFuturePeriodRejected`、`fillOtherUsersItemIsNotFound`。
  - `voidedManualPeriodCanBeRefilled`：填了再作廢 → 回到 `unfilled()`，可以再填。
  - `overdueRemindersOnlyPastDue`：本月入帳日還沒到 → 不在 `overdueReminders()`。
  - `fillBeforeOpeningSkipped`：帳戶初始現金餘額晚於某期入帳日 → 那期不出現在 `unfilled()`。
- [ ] **Step 2：** 跑測試 → FAIL。
- [ ] **Step 3：實作。**
- [ ] **Step 4：** `./mvnw test` 全部通過。

### Task 5：固定收支頁、總資產提醒、依項目看異動

**Files：**
- Create: `dto/RecurringPageView.java`、`dto/RecurringRow.java`、`controller/RecurringController.java`、`templates/recurring/index.html`、`templates/recurring/item-form.html`
- Modify: `service/RecurringItemService.java`（`pageView()`）、`service/TotalAssetsService.java`（`TotalAssetsView` 加 `List<UnfilledPeriod> reminders`；`byItem(Long itemId): List<MovementRow>`）、`dto/TotalAssetsView.java`、`controller/CashMovementController.java`（`/assets/movements?item={id}`）、`templates/assets/index.html`、`templates/assets/movements.html`、`templates/fragments/layout.html`（側欄「固定收支」改成連結 `/recurring`，`active == 'recurring'`）、`static/js/app.js`（`data-show-for` 支援 `data-show-source="欄位 id"`，預設 `type`）、`static/css/app.css`
- Test: Create `controller/RecurringControllerTest.java`；Modify `TotalAssetsControllerTest`、`CashMovementControllerTest`

**Interfaces：**
- Produces:
  - `record RecurringRow(Long id, String name, String directionLabel, String amountLabel /* 金額或「每月自己填」 */, String cycleLabel /* 例如「每月 5 號」「每年 3 月 15 號」「每半年 1、7 月 10 號」 */, BigDecimal monthlyAverage /* 整數元，自己填為 null */, LocalDate nextDue, String accountName, boolean ended)`
  - `record RecurringPageView(BigDecimal monthlyIncome, BigDecimal monthlyExpense, BigDecimal manualFilledThisMonth, List<String> manualUnfilledNames, List<UnfilledPeriod> unfilled, List<RecurringRow> income, List<RecurringRow> expense, List<RecurringRow> ended)`（月平均合計 = 各項精確值相加後才 `setScale(0, HALF_UP)`）
  - 路由：`GET /recurring`、`GET|POST /recurring/new`、`GET|POST /recurring/{id}/edit`、`POST /recurring/{id}/fill`、`GET /assets/movements?item={id}`

- [ ] **Step 1：寫測試**
  - `pageShowsCardsTablesAndUnfilled`：一個薪水（收入每月 62,000）、壽險（每年 48,000）、生活費（自己填）→ 頁面有「每月固定收入」62,000、「每月固定支出」4,000、未填列「生活費」與填金額表單、側欄「固定收支」是連結。
  - `createViaFormWithBackfillMessage`：開始月份選上個月 → 轉頁後訊息含「補記了」。
  - `formHasConditionalFields`：金額欄在 `data-show-for="FIXED"`、月份欄在 `data-show-for="SEMIANNUAL YEARLY"`；標籤沒有括號說明。
  - `fillViaFormThenGone`：填生活費 27000 → 總資產頁的提醒消失、本月淨資產變動的支出含 27,000。
  - `assetsPageShowsOverdueReminder`：上個月生活費沒填 → 總資產頁有「生活費還沒填」與連到 `/recurring` 的連結。
  - `movementsByItem`：`/assets/movements?item={id}` 只列這個項目的異動、標題寫項目名稱；別人的項目 → 404。
  - `otherUsersItemIs404`：別人的項目修改頁、修改 POST、填金額 POST → 404。
  - `duplicateTokenDoesNotCreateTwice`：新增項目與填金額連送兩次只存一筆。
- [ ] **Step 2：** 跑測試 → FAIL。
- [ ] **Step 3：實作**（版面依設計稿 Recurring：上方卡片、未填列、固定收入表、固定支出表、已結束的項目；每列有「修改」「看紀錄」）。
- [ ] **Step 4：** `./mvnw test` 全部通過。

### Task 6：審查與驗收

- [ ] **Step 1：** 派 opus subagent 審查全部改動（對照設計文件、本計畫 Review Focus 與「這一版的判斷」），修正 Critical／Important。
- [ ] **Step 2：** 問使用者可否把 V8 套用到 Docker 資料庫；同意後 `docker compose up -d --build`，用內建瀏覽器截圖講解（固定收支頁、新增項目、填生活費、總資產提醒）。只看畫面；要在使用者的資料庫新增資料前先問。
- [ ] **Step 3：** 整理 diff 與 commit 建議，連同「這一版的判斷」給使用者確認，同意後才 commit。
