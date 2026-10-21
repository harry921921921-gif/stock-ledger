# 總資產頁第一版（現金）實作計畫

> **給執行者：** 必須搭配 superpowers:subagent-driven-development 或 superpowers:executing-plans，一個 Task 一個 Task 做。步驟用 `- [ ]` 追蹤。
> **本專案額外規則（CLAUDE.md）：** 程式由 Claude 寫、每個 Task 做完派 subagent（opus）審核；每個 Task 做完先讓使用者在瀏覽器確認能跑；**commit 前給使用者看 diff，經同意才 commit；絕不 `git push`**。回覆用繁體中文。

**Goal：** 使用者可以記錄現金帳戶的初始資金、存入、領出、互轉與對帳，並在「總資產」頁看到錢放在哪裡、各占多少，以及本月淨資產變動（這一版只有現金部分）。

**Architecture：** 所有現金事件存在一張 `cash_movement` 表（類型：初始資金、存入、領出、互轉、對帳），對帳存「實際餘額」。餘額、餘額調整、每月變動一律由純 Java 的 `CashLedgerCalculator` 從事件推算，不另存。修改與作廢依規格 4.9 寫 `cash_movement_revision`（每個改到的欄位一列）。

**Tech Stack：** Java 21、Spring Boot 4.1.1、Spring Security、Thymeleaf、MySQL 8.4、Flyway、JUnit 5、Testcontainers。

**Spec：** `docs/superpowers/specs/2026-10-21-total-assets-design.md`（本計畫依它撰寫，執行者兩份都要讀）；`docs/spec.md` v1.3；設計稿 https://claude.ai/artifact/WHJdwUeLPEoAYjHKAtiuXx 的「總資產」（Assets）。

## Global Constraints

- 套件 `com.sideproject.stockledger`；分層 `controller` → `service` → `repository`，另有 `entity`、`calculator`、`dto`。Controller 不呼叫 Repository；Controller 與模板不做計算（比例、餘額、差額都由 Service／Calculator 算好）。
- 金額 `BigDecimal`、資料庫 `DECIMAL(19,4)`；**現金金額只接受整數元**（新台幣帳戶沒有角）；除法與 `setScale` 一律指定 `RoundingMode`；比例顯示到 0.01%、`HALF_UP`（spec 第 5 節）。
- 日期 `DATE`／`DATETIME`，不用 `TIMESTAMP`；「今天」「本月」一律用注入的 `Clock`（Asia/Taipei）。
- 只儲存原始事件；餘額、餘額調整、每月變動都由事件推算。
- 個人資料表都有 `user_id` 外鍵＋索引；`user_id` 只從 `CurrentUser` 取得；Repository 繼承 Spring Data `Repository`，只提供帶 `userId` 的查詢；別人的 id 一律回 404。
- 所有關聯建外鍵；不用多型關聯；不硬刪除（作廢用旗標）。
- Schema 只能新增 migration：本計畫用 `V5__create_cash_movement.sql`。
- 錯誤訊息一律中文，不露出 Java 類別名稱；各服務用自己的驗證例外（比照 `RateValidationException`）。
- 表單載入 `/js/app.js`，送出按鈕有 `data-busy-text`；新增現金異動另有伺服器端一次性表單代號（Task 6）。
- 不新增任何套件。

## Review Focus

1. **日期不合理**：未來日期、早於該帳戶初始資金日期的異動或對帳 → 留在表單並顯示中文錯誤，不新增（Task 3 `futureDateRejected`、`dateBeforeOpeningRejected`）。
2. **互轉到同一個帳戶或別人的帳戶** → 同帳戶顯示錯誤；別人的帳戶回 404（Task 3 `transferToSameAccountRejected`、`otherUsersAccountIsNotFound`）。
3. **金額怪異**：0、負數、有小數（100.5）、超大（`1e2147483647`）→ 中文錯誤，不是 500（Task 3 `invalidAmountsRejected`）。
4. **補記比對帳更早的異動**：對帳過的餘額仍等於當時輸入的實際餘額，只有那筆餘額調整的金額改變（Task 2 `backdatedMovementKeepsReconciledBalance`）。
5. **作廢的事件**：不計入餘額、每月變動、上次對帳日期；作廢後可以重新建立初始資金或同一天的對帳（Task 5 `voidedEventsIgnored`、Task 4 `voidedOpeningCanBeRecreated`）。

---

### Task 1：規格書 v1.4 與設計文件

**Files：**
- Modify: `docs/spec.md`
- Add: `docs/superpowers/specs/2026-10-21-total-assets-design.md`、`docs/superpowers/plans/2026-10-21-total-assets-cash.md`（已寫好，與本 Task 一起 commit）

- [ ] **Step 1：依設計文件第 7 節擬好 spec 修改內容，在聊天中完整列出給使用者看（不要只說「在下方」）**，包含：名詞定義新增「淨資產變動」「初始資金」「餘額調整」、改寫「每月收支」；第 3 節總覽與總資產卡片改為「本月淨資產變動」、固定收支頁註明不計入每月變動；4.3 改寫；4.6 加初始資金、第 3、4 點改成餘額調整、加「對帳存實際餘額」；第 6 節 EventRevision 改成每種事件一張表；第 8 節新增 DR-031～DR-033；版本說明加 v1.4（2026-10-21）。
- [ ] **Step 2：使用者同意後寫入 `docs/spec.md`。**
- [ ] **Step 3：給使用者看 diff，同意後 commit：`docs: 規格書 v1.4 改為每月看淨資產變動`**

---

### Task 2：`CashLedgerCalculator`（純 Java）

**Files：**
- Create: `entity/CashMovementType.java`
- Create: `calculator/CashEvent.java`、`calculator/CashLedger.java`、`calculator/CashLedgerCalculator.java`、`calculator/ShareCalculator.java`
- Test: `calculator/CashLedgerCalculatorTest.java`、`calculator/ShareCalculatorTest.java`

**Interfaces：**
- Produces：
  - `enum CashMovementType { OPENING("初始資金"), DEPOSIT("存入"), WITHDRAWAL("領出"), TRANSFER("帳戶互轉"), RECONCILE("餘額調整"); String label() }`
  - `record CashEvent(Long id, CashMovementType type, LocalDate date, Long accountId, Long toAccountId, BigDecimal amount, BigDecimal actualBalance)`：只放「沒作廢」的事件；RECONCILE 用 `actualBalance`（`amount` 為 null），其他用 `amount`。
  - `record CashLedger(Map<Long, BigDecimal> balances, Map<Long, BigDecimal> adjustments, Map<Long, CashEvent> lastReconciliation)`：`balances` 以帳戶 id 為 key；`adjustments` 以 RECONCILE 事件 id 為 key（實際 − 系統）；`lastReconciliation` 以帳戶 id 為 key。
  - `static CashLedger CashLedgerCalculator.compute(List<CashEvent> events, LocalDate asOf)`：只計 `date ≤ asOf`。依日期排序；同一天先算非 RECONCILE、再算 RECONCILE。OPENING、DEPOSIT 加；WITHDRAWAL 減；TRANSFER 從 `accountId` 減、`toAccountId` 加；RECONCILE：調整 ＝ 實際 − 目前餘額，餘額改成實際。
  - `record MonthlyCashChange(BigDecimal deposits, BigDecimal withdrawals, BigDecimal adjustments, BigDecimal total)`（放 `calculator`）；`static MonthlyCashChange CashLedgerCalculator.monthlyChange(List<CashEvent> events, YearMonth month)`：本月存入合計、領出合計（正數）、本月對帳的調整合計；`total = deposits − withdrawals + adjustments`。OPENING、TRANSFER 不計。調整用 `compute(events, month 最後一天)` 的結果。
  - `static Optional<BigDecimal> ShareCalculator.percent(BigDecimal part, BigDecimal total)`：`total ≤ 0` 回 empty；否則 `part × 100 ÷ total`，`setScale(2, HALF_UP)`（中間值 10 位 `HALF_UP`）。

- [ ] **Step 1：寫失敗的測試**

```java
// CashLedgerCalculatorTest（帳戶 1、2；金額用整數）
@Test void openingDepositWithdrawal()             // OPENING 1000、DEPOSIT 500、WITHDRAWAL 200 → balance(1) == 1300
@Test void transferMovesBetweenAccounts()         // 1: OPENING 1000；TRANSFER 1→2 300 → 1 == 700、2 == 300
@Test void reconcileSetsBalanceAndAdjustment()    // OPENING 1000、RECONCILE 實際 900 → balance 900、adjustment == -100
@Test void sameDayReconcileAfterOtherEvents()     // 同一天 DEPOSIT 500 與 RECONCILE 實際 1500（清單中 RECONCILE 排前面）→ adjustment == 0
@Test void backdatedMovementKeepsReconciledBalance() // OPENING 1000(10/1)、RECONCILE 900(10/10)；再加 WITHDRAWAL 50(10/5) → balance 900、adjustment == -50
@Test void eventsAfterAsOfIgnored()               // DEPOSIT 10/20，asOf 10/19 → 不計
@Test void lastReconciliationIsLatestDate()       // 兩筆 RECONCILE 10/1、10/15 → lastReconciliation(1).date == 10/15
@Test void monthlyChangeExcludesOpeningAndTransfer() // 10 月：OPENING 1000、DEPOSIT 500、WITHDRAWAL 200、TRANSFER 300、RECONCILE 調整 -100 → deposits 500、withdrawals 200、adjustments -100、total 200
@Test void monthlyChangeOnlyCountsThatMonth()     // 9/30 DEPOSIT 100、10/1 DEPOSIT 200 → 10 月 deposits == 200
// ShareCalculatorTest
@Test void percentRoundsHalfUp()                  // percent(2247700, 5284000) == 42.54
@Test void zeroTotalIsEmpty()                     // percent(0, 0) == empty
```

- [ ] **Step 2：`./mvnw test -Dtest=CashLedgerCalculatorTest,ShareCalculatorTest`，預期 FAIL（編譯失敗，類別不存在）。**
- [ ] **Step 3：實作上面 Interfaces 列出的類別。**
- [ ] **Step 4：預期 PASS；`./mvnw test` 全部 PASS。**
- [ ] **Step 5：subagent 審核 → 修正 → 給使用者看 diff，同意後 commit：`feat: 現金帳本計算（餘額、餘額調整、每月變動）`**

---

### Task 3：`cash_movement` 表與記一筆現金異動（Service）

**Files：**
- Create: `db/migration/V5__create_cash_movement.sql`
- Create: `entity/CashMovement.java`、`repository/CashMovementRepository.java`
- Create: `dto/CashMovementForm.java`、`dto/OpeningForm.java`、`dto/ReconcileForm.java`
- Create: `service/CashMovementService.java`、`service/CashValidationException.java`
- Test: `service/CashMovementServiceTest.java`、`repository/CashMovementSchemaTest.java`

**Interfaces：**
- Consumes：`CashMovementType`、`CashEvent`（Task 2）；`CurrentUser`、`CashAccountRepository.findByIdAndUserId`、`Clock`。
- Produces：
  - `V5`：`cash_movement(id BIGINT AUTO_INCREMENT PK, user_id BIGINT NOT NULL FK app_user, type VARCHAR(20) NOT NULL, movement_date DATE NOT NULL, account_id BIGINT NOT NULL FK cash_account, to_account_id BIGINT NULL FK cash_account, amount DECIMAL(19,4) NULL, actual_balance DECIMAL(19,4) NULL, note VARCHAR(100) NULL, voided BOOLEAN NOT NULL DEFAULT FALSE, created_at DATETIME NOT NULL)`，索引 `(user_id, movement_date)`；CHECK：TRANSFER 才有 `to_account_id` 且 `<> account_id`；RECONCILE 才有 `actual_balance` 且 `amount` 為 NULL；其他類型 `amount` 必填（OPENING `>= 0`，其他 `> 0`）。用 STORED 產生欄位＋唯一索引讓資料庫保證「每個帳戶最多一筆沒作廢的 OPENING」「每個帳戶每天最多一筆沒作廢的 RECONCILE」（`opening_account_id`、`reconcile_account_id` 在不適用時為 NULL；Entity 不對應這兩欄）。另建 `cash_movement_revision(id PK, cash_movement_id BIGINT NOT NULL FK, user_id BIGINT NOT NULL FK, revised_at DATETIME NOT NULL, action VARCHAR(10) NOT NULL, field VARCHAR(30) NOT NULL, old_value VARCHAR(200) NULL, new_value VARCHAR(200) NULL, reason VARCHAR(200) NULL)`（Task 4 使用）。
  - `CashMovementRepository extends Repository<CashMovement, Long>`：`saveAndFlush`、`flush`、`List<CashMovement> findByUserIdAndVoidedFalse(Long userId)`、`List<CashMovement> findByUserIdOrderByMovementDateDescIdDesc(Long userId)`、`Optional<CashMovement> findByIdAndUserId(Long id, Long userId)`、`Optional<CashMovement> findByUserIdAndAccountIdAndTypeAndVoidedFalse(Long userId, Long accountId, CashMovementType type)`。
  - `CashMovement.toEvent()` → `CashEvent`。
  - `record CashMovementForm(CashMovementType type, LocalDate date, Long accountId, Long toAccountId, BigDecimal amount, String note)`：type 只能 DEPOSIT／WITHDRAWAL／TRANSFER；note `strip()`、空白存 null、最多 100 字。
  - `record OpeningForm(LocalDate date, BigDecimal amount)`、`record ReconcileForm(LocalDate date, BigDecimal actualBalance)`。
  - `CashMovementService`（方法參數沒有 userId）：`CashMovement record(CashMovementForm f)`、`CashMovement setOpening(Long accountId, OpeningForm f)`、`CashMovement reconcile(Long accountId, ReconcileForm f)`、`List<CashEvent> activeEvents()`（目前登入者所有沒作廢的事件）。驗證失敗丟 `CashValidationException`（中文訊息）；帳戶不是自己的丟 `NotFoundException`。

- [ ] **Step 1：寫失敗的測試**

```java
// CashMovementServiceTest（Testcontainers；使用者 cm-amy、cm-ben）
@Test void depositWithdrawalTransferAreRecorded()
@Test void openingOncePerAccount()                 // 第二次 setOpening → CashValidationException「這個帳戶已經有初始資金」
@Test void reconcileStoresActualBalance()          // reconcile 實際 900 → actual_balance == 900、amount 為 null
@Test void oneReconcilePerAccountPerDay()          // 同帳戶同一天第二次 → CashValidationException
@Test void futureDateRejected()                    // 日期 = 今天 + 1 → CashValidationException「日期不能晚於今天」
@Test void dateBeforeOpeningRejected()             // 初始資金 10/1，存入 9/30 → CashValidationException
@Test void transferToSameAccountRejected()
@Test void otherUsersAccountIsNotFound()           // cm-ben 用 cm-amy 的帳戶 id 存入／互轉轉入 → NotFoundException
@ParameterizedTest @ValueSource(strings = {"0", "-1", "100.5", "1e2147483647"})
void invalidAmountsRejected(String amount)         // 存入 → CashValidationException；資料庫沒有新增
@Test void openingZeroAllowed()                    // 初始資金 0 → 成功
// CashMovementSchemaTest（直接用 JdbcTemplate 寫入，證明資料庫本身擋得住）
@Test void dbRejectsSecondActiveOpening()          // 第二筆沒作廢的 OPENING → DataIntegrityViolationException
@Test void dbRejectsTransferWithoutTarget()        // TRANSFER 且 to_account_id NULL → DataIntegrityViolationException
```

- [ ] **Step 2：預期 FAIL。**
- [ ] **Step 3：實作 V5、Entity、Repository、Form、Service（`@Transactional`）。** 唯一索引撞到時（連點），比照 `CashAccountService` 用 `saveAndFlush` 並把限制名稱轉成中文訊息。
- [ ] **Step 4：`./mvnw test` 全部 PASS。**
- [ ] **Step 5：審核 → 修正 → 使用者看 diff，同意後 commit：`feat: 記錄現金異動、初始資金與對帳`**

---

### Task 4：修改與作廢（修改紀錄）

**Files：**
- Create: `entity/CashMovementRevision.java`、`repository/CashMovementRevisionRepository.java`、`dto/VoidForm.java`
- Modify: `service/CashMovementService.java`、`entity/CashMovement.java`
- Test: `service/CashMovementRevisionTest.java`

**Interfaces：**
- Consumes：Task 3 的全部。
- Produces：
  - `CashMovementService.edit(Long id, CashMovementForm f, String reason)`（RECONCILE 用 `editReconcile(Long id, ReconcileForm f, String reason)`、OPENING 用 `editOpening(Long id, OpeningForm f, String reason)`）、`voidMovement(Long id, String reason)`。類型不可改；驗證規則同新增。
  - 修改前：每個值有變的欄位寫一列 revision（`action` = `EDIT`，`field` = 欄位英文名，舊值、新值用字串）；作廢寫一列（`action` = `VOID`、`field` = `voided`、`false` → `true`）；沒有任何欄位改變就不寫、不更新。
  - `Set<Long> CashMovementService.revisedIds()`：目前登入者有修改紀錄的異動 id（列表顯示「曾修改」）。
  - `CashMovementRevisionRepository`：`save`、`List<CashMovementRevision> findByCashMovementIdOrderByIdAsc(Long id)`、查目前使用者所有有紀錄的 `cash_movement_id`。

- [ ] **Step 1：寫失敗的測試**

```java
@Test void editWritesOneRowPerChangedField()       // 改金額與說明 → 2 列 revision，舊值、新值正確；reason 存入
@Test void editWithoutChangesWritesNothing()
@Test void voidWritesRevisionAndExcludesFromEvents() // 作廢 → activeEvents() 不含它；revision action == VOID
@Test void voidedOpeningCanBeRecreated()           // 作廢初始資金後可以再 setOpening
@Test void editReconcileChangesActualBalance()     // 修改對帳 → actual_balance 更新
@Test void otherUserCannotEditOrVoid()             // → NotFoundException，資料不變
@Test void editRevalidates()                       // 改成未來日期 → CashValidationException，沒有寫 revision
```

- [ ] **Step 2：預期 FAIL。**
- [ ] **Step 3：實作。**
- [ ] **Step 4：`./mvnw test` 全部 PASS。**
- [ ] **Step 5：審核 → 修正 → 使用者看 diff，同意後 commit：`feat: 現金異動修改與作廢紀錄`**

---

### Task 5：總資產頁（`/assets`）

**Files：**
- Create: `dto/TotalAssetsView.java`、`dto/AllocationRow.java`、`dto/AccountCard.java`、`dto/MovementRow.java`
- Create: `service/TotalAssetsService.java`、`controller/TotalAssetsController.java`、`templates/assets/index.html`
- Modify: `templates/fragments/layout.html`（「總資產」改成可點，`active == 'assets'`）、`static/css/app.css`
- Test: `service/TotalAssetsServiceTest.java`、`controller/TotalAssetsControllerTest.java`

**Interfaces：**
- Consumes：`CashLedgerCalculator`、`ShareCalculator`（Task 2）；`CashMovementService.activeEvents()`、`revisedIds()`（Task 3、4）；`CashAccountService.listMine()`。
- Produces：
  - `record TotalAssetsView(BigDecimal totalAssets, BigDecimal stockValue, BigDecimal cash, int accountCount, BigDecimal cashSharePercent, MonthlyCashChange monthChange, List<AllocationRow> allocation, AllocationRow largest, List<AccountCard> accounts, List<MovementRow> recent)`；`stockValue` 這一版固定 `BigDecimal.ZERO`。
  - `record AllocationRow(String name, BigDecimal amount, BigDecimal sharePercent)`：依金額由大到小；總資產為 0 時 `sharePercent` 為 null。
  - `record AccountCard(Long id, String name, String typeLabel, BigDecimal balance, LocalDate lastReconciledOn, BigDecimal lastAdjustment, boolean reconcileOverdue, boolean negative)`：`reconcileOverdue` = 今天 − (上次對帳日 ?: 初始資金日 ?: 帳戶建立日) > 30 天。
  - `enum Direction { IN, OUT, NEUTRAL }`、`record MovementRow(Long id, LocalDate date, String accountLabel, String description, BigDecimal amount, Direction direction, boolean revised, boolean voided)`：互轉 `accountLabel` = 「轉出 → 轉入」、NEUTRAL；對帳顯示調整金額，方向依正負；`description` = 類型 label ＋ 說明。
  - `TotalAssetsService.view()`（今天、本月都取自 `Clock`）；`List<MovementRow> TotalAssetsService.movements(YearMonth month)`（Task 6 的全部異動頁用，含作廢的）。
  - 路由：`GET /assets`。

- [ ] **Step 1：寫失敗的測試**

```java
// TotalAssetsServiceTest
@Test void emptyUserShowsZeros()                   // 沒有帳戶 → totalAssets 0、allocation 空、cashSharePercent null
@Test void cardsAndAllocation()                    // 帳戶 A 初始 600、B 初始 400 → total 1000、A 60.00%、B 40.00%、largest == A
@Test void monthChangeFromMovements()              // 本月存入 500、領出 200、對帳調整 -100 → total 200；初始資金不計
@Test void overdueReminderAfter30Days()            // 上次對帳 31 天前 → reconcileOverdue；30 天前 → 否
@Test void negativeBalanceFlagged()
@Test void recentShowsLatest10WithRevisedFlag()
@Test void onlyMyDataInView()                      // 別人的帳戶與異動都不出現
@Test void voidedEventsIgnored()                   // 作廢一筆存入與一筆對帳 → 餘額、本月變動、上次對帳日期都不含它們
// TotalAssetsControllerTest
@Test void pageShowsCardsAndSections()             // GET /assets → 200，含「總資產」「資產配置」「最近的現金異動」「尚未有持股」
@Test void sidebarAssetsLinkEnabled()              // 含 href="/assets"
```

- [ ] **Step 2：預期 FAIL。**
- [ ] **Step 3：實作 Service、Controller、模板（依設計稿 Assets：四張卡片、資產配置、帳戶卡片、最近異動；卡片副標「股票、配息、利息完成後才會完整」；數字格式用 Thymeleaf `#numbers` 加千分位，不做計算）。**
- [ ] **Step 4：`./mvnw test` 全部 PASS。**
- [ ] **Step 5：審核 → 修正 → Docker 重建、使用者在瀏覽器確認 → 看 diff，同意後 commit：`feat: 總資產頁（現金）`**

---

### Task 6：現金異動的畫面（新增、對帳、初始資金、全部異動、修改／作廢）

**Files：**
- Create: `controller/CashMovementController.java`、`controller/FormTokens.java`
- Create: `templates/assets/movement-form.html`、`templates/assets/reconcile-form.html`、`templates/assets/movements.html`、`templates/assets/opening-form.html`
- Modify: `templates/settings/account-form.html`（修改帳戶時顯示初始資金與「設定初始資金」連結）、`messages.properties`
- Test: `controller/CashMovementControllerTest.java`

**Interfaces：**
- Consumes：Task 3、4 的 Service 方法；`TotalAssetsService.movements(YearMonth)`。
- Produces：
  - `FormTokens`（`@Component`）：`String issue(HttpSession s)`、`boolean consume(HttpSession s, String token)`；每個 session 最多保留 20 個未用代號。
  - 路由：`GET/POST /assets/movements/new`、`GET /assets/movements?month=2026-10`（預設本月）、`GET/POST /assets/movements/{id}/edit`、`POST /assets/movements/{id}/void`、`GET/POST /assets/accounts/{accountId}/reconcile`、`GET/POST /assets/accounts/{accountId}/opening`。成功後導回 `/assets` 或全部異動頁，並顯示 flash 訊息。
  - 新增現金異動的表單帶 `formToken` 隱藏欄位；代號已用過時不新增、直接導回 `/assets`。

- [ ] **Step 1：寫失敗的測試**

```java
@Test void recordDepositViaForm()                  // POST → redirect /assets；列表出現
@Test void duplicateTokenDoesNotCreateTwice()       // 同一個 formToken 送兩次 → 只有 1 筆
@Test void validationErrorsShownInChinese()        // 金額 abc／100.5／空白日期 → 200，中文訊息，不含 "java."
@Test void reconcileFormShowsSystemBalance()       // GET 對帳表單 → 顯示目前系統餘額
@Test void otherUsersMovementIs404()               // 別人的異動 edit／void → 404
@Test void movementsPageFiltersByMonth()           // ?month=2026-09 只顯示 9 月
@Test void voidedShownAsVoided()                   // 作廢的顯示「已作廢」，不計入
@Test void formsRequireCsrfAndLoadGuard()          // 沒 csrf → 403；頁面含 src="/js/app.js" 與 data-busy-text
@Test void invalidMonthParamFallsBackToCurrent()   // ?month=abc → 200，顯示本月
```

- [ ] **Step 2：預期 FAIL。**
- [ ] **Step 3：實作。**
- [ ] **Step 4：`./mvnw test` 全部 PASS。**
- [ ] **Step 5：審核 → 修正 → Docker 重建 → 使用者驗收（下方） → 看 diff，同意後 commit：`feat: 現金異動畫面與防重複送出`**

**驗收（請使用者操作）：** 兩個帳戶各設初始資金 → 記一筆存入、一筆領出、一筆互轉 → 對其中一個帳戶輸入實際餘額 → 總資產頁的數字、資產配置、本月淨資產變動（不含初始資金與互轉）都正確 → 修改一筆、作廢一筆，列表出現「曾修改」「已作廢」，金額跟著變。
