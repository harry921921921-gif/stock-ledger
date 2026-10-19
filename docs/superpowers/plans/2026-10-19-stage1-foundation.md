# 階段 1「基礎」實作計畫

> **給執行者：** 必須搭配 superpowers:subagent-driven-development（建議）或 superpowers:executing-plans，一個 Task 一個 Task 做。步驟用 `- [ ]` 追蹤。
> **本專案額外規則（CLAUDE.md）：** 每個 Task 做完先讓使用者自己執行、確認能跑，才做下一個；**commit 前先給使用者看 diff，經同意才 commit；絕不 `git push`**。回覆用繁體中文。

**Goal：** 完成使用者與登入、現金帳戶、費率設定（含法定費率 seed），達成「兩個帳號各自登入，互相看不到對方的帳戶」。

**Architecture：** Spring Security 表單登入 + BCrypt，帳密由資料庫的 `app_user` 提供；`CurrentUser` 元件是 Service 取得登入者 id 的唯一入口，Repository 每個查詢都帶 `userId`。費率以「事件」方式存放（每次變動新增一列、附生效日期），「取哪一筆」的規則寫成純 Java 的 `EffectiveRateSelector`。Schema 全部由 Flyway 建立，Hibernate 只做 `validate`。

**Tech Stack：** Java 21、Spring Boot 4.1.1（webmvc、security、data-jpa、thymeleaf、validation、flyway）、MySQL 8.4、JUnit 5、Testcontainers（MySQL 8.4）。

**Spec：** `docs/spec.md`（v1.2），主要章節：3（設定頁、登入頁）、4.5 費率、4.6-1 帳戶類型、4.10 使用者與登入、5.1/5.2 法定費率、6 資料模型、7.2 長期使用。

## 本計畫已確定的決定（2026-10-19 與使用者確認）

1. 初始管理者：由環境變數 `ADMIN_USERNAME`、`ADMIN_PASSWORD` 建立；只在 `app_user` 表為空時執行。
2. 「記住我 30 天」與「連續失敗 5 次鎖 15 分鐘」都在階段 1 完成（路線圖階段 6 只剩雙重驗證）。
3. 整合測試用 Testcontainers 開 MySQL 8.4 容器；跑測試時 Docker Desktop 必須開著。
4. 「記住我」用 Spring Security 的雜湊 token（`TokenBasedRememberMeServices`），金鑰來自環境變數 `REMEMBER_ME_KEY`。不用 `persistent_logins` 表，因為它沒有內部流水號主鍵，預設 schema 還用 `TIMESTAMP`，兩點都違反 7.2。
5. 證交稅率依種類拆成四種 `RateKind`（一般股票、一般 ETF、債券 ETF、現股當沖）。同種類、同生效日期有多筆時，**id 較大（較晚新增）的優先**，這樣法規追溯修改時只要新增一列。
6. 個人費率中有預設值的項目（整股最低手續費 20 元、零股 1 元）以 `user_id = NULL` 的「全域預設」存放；使用者自己設定的值優先於全域預設。
7. 法定費率查證結果（2026-10-19），寫進 seed 的註解：
   - 現股當沖 0.15%，實施到 2027-12-31，之後回到 0.3%（2024-12-31 立法院三讀）。
   - 債券 ETF 停徵證交稅，到 2026-12-31 為止（106/1/1–115/12/31）。延長 10 年的草案已在 2026-10-01 由行政院通過，**還沒三讀**，所以 seed 先依現行法寫成 2027-01-01 起 0.1%。三讀後新增一支 migration，寫一筆 2027-01-01、0% 的紀錄，由決定 5 讓它優先。
   - 二代健保補充保費：2013-01-01 起 2%，2016-01-01 起 1.91%，2021-01-01 起 2.11%；單次門檻 2013-01-01 起 5,000 元，2016-01-01 起 20,000 元。改成年度累計的修法已暫緩。
   - 手續費率上限 0.1425%、一般股票 0.3%、一般 ETF 0.1%：生效日期用 `2000-01-01` 當起點（系統不會有更早的交易）。

**假設：** 密碼至少 8 個字元；帳號去除前後空白並轉成小寫後比對，不可重複。

## Global Constraints

- 套件 `com.sideproject.stockledger`；分層 `controller` → `service` → `repository`，另有 `entity`、`calculator`、`dto`、`security`、`config`。Controller 不可呼叫 Repository；Controller 與模板不做計算。
- 金額、費率一律 `BigDecimal`；除法與 `setScale` 都要指定 `RoundingMode`。
- Schema 只能透過 `src/main/resources/db/migration/V{n}__說明.sql` 變更；已執行過的檔案不修改。
- 日期用 `DATE`／`DATETIME`，禁用 `TIMESTAMP`；時區 Asia/Taipei。每張表都用 `BIGINT AUTO_INCREMENT` 主鍵；關聯都建外鍵。
- 費率欄位 `DECIMAL(19,6)`（spec 沒有規定費率的型別；19,6 足以存放 0.001425 和 20000）。
- 個人資料表都有 `user_id` 外鍵加索引；`user_id` 只能從 `CurrentUser` 取得，不接受畫面傳入。
- 帳密、金鑰只放 `.env`；`application.properties` 不寫任何帳密。
- 不新增本計畫以外的套件。本計畫只新增 Testcontainers（`spring-boot-testcontainers`、`org.testcontainers:testcontainers-mysql`、`org.testcontainers:testcontainers-junit-jupiter`，版本由 Boot 管理；如果 Boot 4.1.1 管理的 artifact 名稱不同，以 `./mvnw dependency:tree` 的結果為準）。
- 畫面風格依 DR-012「穩健明亮」：淺色、左側選單（參考設計畫布 https://claude.ai/artifact/WHJdwUeLPEoAYjHKAtiuXx 的登入頁與設定頁）。CSS 放 `src/main/resources/static/css/app.css`，不引入前端框架。

## Review Focus

1. **用網址直接存取別人的資料**（例如 `/settings/accounts/{別人的 id}/edit`）：應回 404，不是 403，避免洩漏「這個 id 存在」→ Task 4 的 `otherUsersAccountReturns404`。
2. **帳號大小寫或多了空白**（`Alice `、`alice`）：視為同一個帳號，登入和建立帳號都一致 → Task 1 的 `usernameIsNormalized`、Task 7 的 `duplicateUsernameIgnoringCaseIsRejected`。
3. **被鎖定時輸入正確密碼**：仍然要拒絕，並顯示「已鎖定」；鎖定到期後可以登入，登入成功後失敗次數歸零 → Task 3。
4. **費率填錯單位**（把 2.8 折填成 2.8，或填負數）：折扣輸入的是百分比，限制 0 < x ≤ 100；最低手續費要是 ≥ 0 的整數；生效日期在未來的費率可以存，但到那天之前不生效 → Task 6。
5. **被停用的帳號**：密碼正確也不能登入 → Task 2 的 `disabledUserCannotLogin`。

---

### Task 1：測試基礎設施、基本設定、`app_user` 表

**Files：**
- Modify: `pom.xml`（加 Testcontainers 三個 test 依賴）
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/db/migration/V1__create_app_user.sql`
- Create: `src/main/java/com/sideproject/stockledger/entity/AppUser.java`、`entity/Role.java`
- Create: `src/main/java/com/sideproject/stockledger/repository/AppUserRepository.java`
- Create: `src/main/java/com/sideproject/stockledger/config/ClockConfig.java`
- Create: `src/test/java/com/sideproject/stockledger/TestcontainersConfig.java`
- Modify: `src/test/java/com/sideproject/stockledger/StockLedgerApplicationTests.java`
- Test: `src/test/java/com/sideproject/stockledger/repository/AppUserRepositoryTest.java`

**Interfaces：**
- Produces：
  - `enum Role { ADMIN, USER }`
  - `AppUser`：`Long id`、`String username`、`String passwordHash`、`String displayName`、`Role role`、`boolean disabled`、`int failedLoginCount`、`LocalDateTime lockedUntil`、`LocalDateTime createdAt`；靜態工廠 `AppUser.create(String username, String passwordHash, String displayName, Role role, LocalDateTime now)`；`static String normalizeUsername(String raw)`（trim + `toLowerCase(Locale.ROOT)`）。
  - `AppUserRepository extends JpaRepository<AppUser, Long>`：`Optional<AppUser> findByUsername(String normalizedUsername)`、`boolean existsByUsername(String normalizedUsername)`。（`app_user` 是登入用的帳號表，不是「個人資料表」，所以不帶 `user_id`。）
  - `@Bean Clock clock()` = `Clock.system(ZoneId.of("Asia/Taipei"))`；之後所有「現在時間」都從這個 `Clock` 取，測試可以替換。
  - `TestcontainersConfig`：`@TestConfiguration(proxyBeanMethods = false)`，`@Bean @ServiceConnection MySQLContainer mysql()` 用 image `mysql:8.4`。所有整合測試 `@Import(TestcontainersConfig.class)`。

- [ ] **Step 1：在 `pom.xml` 加入 Testcontainers 依賴，`./mvnw -q dependency:tree | grep testcontainers` 看得到三個 artifact。**

- [ ] **Step 2：寫 `application.properties`**

```properties
spring.application.name=stock-ledger
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.jdbc.time_zone=Asia/Taipei
spring.flyway.enabled=true
server.servlet.session.timeout=30m
app.admin.username=${ADMIN_USERNAME:}
app.admin.password=${ADMIN_PASSWORD:}
app.remember-me.key=${REMEMBER_ME_KEY:}
```

（資料庫連線由 `docker-compose.yml` 的 `SPRING_DATASOURCE_*` 環境變數提供，測試則由 `@ServiceConnection` 提供。）

- [ ] **Step 3：寫失敗的測試 `AppUserRepositoryTest`（`@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Import(TestcontainersConfig.class)`）**

```java
@Test void savesAndFindsByUsername() {
    repo.save(AppUser.create("alice", "{bcrypt}x", "Alice", Role.USER, NOW));
    assertThat(repo.findByUsername("alice")).isPresent();
}
@Test void usernameIsNormalized() {
    assertThat(AppUser.normalizeUsername("  Alice ")).isEqualTo("alice");
}
@Test void duplicateUsernameViolatesUniqueConstraint() {
    repo.saveAndFlush(AppUser.create("alice", "h", "A", Role.USER, NOW));
    assertThatThrownBy(() -> repo.saveAndFlush(AppUser.create("alice", "h", "B", Role.USER, NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);
}
```

- [ ] **Step 4：`./mvnw test -Dtest=AppUserRepositoryTest`，預期 FAIL（找不到類別）。**

- [ ] **Step 5：寫 `V1__create_app_user.sql`**：`id BIGINT AUTO_INCREMENT PRIMARY KEY`、`username VARCHAR(50) NOT NULL UNIQUE`、`password_hash VARCHAR(100) NOT NULL`、`display_name VARCHAR(50) NOT NULL`、`role VARCHAR(10) NOT NULL`、`disabled BOOLEAN NOT NULL DEFAULT FALSE`、`failed_login_count INT NOT NULL DEFAULT 0`、`locked_until DATETIME NULL`、`created_at DATETIME NOT NULL`；`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`。接著寫 `AppUser`（`@Enumerated(STRING)`）、`Role`、`AppUserRepository`、`ClockConfig`、`TestcontainersConfig`；`StockLedgerApplicationTests` 加上 `@Import(TestcontainersConfig.class)`。

- [ ] **Step 6：`./mvnw test`，預期全部 PASS（包含 `contextLoads`）。**

- [ ] **Step 7：給使用者看 diff，同意後 commit：`feat: 加入 app_user 表與 Testcontainers 測試環境`**

---

### Task 2：登入、初始管理者、記住我、版面

**Files：**
- Create: `security/AppUserDetails.java`、`security/AppUserDetailsService.java`、`security/CurrentUser.java`
- Create: `config/SecurityConfig.java`
- Create: `service/AdminBootstrap.java`
- Create: `controller/LoginController.java`、`controller/HomeController.java`
- Create: `templates/login.html`、`templates/layout.html`（Thymeleaf fragment：左側選單 + 目前使用者 + 登出）、`templates/settings/index.html`、`static/css/app.css`
- Modify: `.env.example`、`docker-compose.yml`（把 `ADMIN_USERNAME`、`ADMIN_PASSWORD`、`REMEMBER_ME_KEY` 傳進 app 容器）
- Test: `src/test/java/com/sideproject/stockledger/security/LoginTest.java`、`service/AdminBootstrapTest.java`

**Interfaces：**
- Consumes：`AppUser`、`AppUserRepository`、`Clock`（Task 1）
- Produces：
  - `AppUserDetails implements UserDetails`：`Long getId()`、`String getDisplayName()`；`isEnabled()` = `!disabled`；`isAccountNonLocked()` = `lockedUntil == null || lockedUntil` 早於現在；權限 `ROLE_ADMIN`／`ROLE_USER`。
  - `AppUserDetailsService implements UserDetailsService`：查詢前先 `AppUser.normalizeUsername`。
  - `CurrentUser`（`@Component`）：`Long id()`、`boolean isAdmin()`，從 `SecurityContextHolder` 讀取；沒有登入時丟 `IllegalStateException`。**這是 Service 取得 user_id 的唯一入口。**
  - `@Bean PasswordEncoder` = `PasswordEncoderFactories.createDelegatingPasswordEncoder()`（預設 BCrypt）。
  - 路由：`GET /login`（自訂頁）、`POST /login`、`POST /logout`、`GET /` → redirect `/settings`、`GET /settings`。`/css/**` 不需要登入；`/settings/users/**` 需要 `ROLE_ADMIN`；其他都要登入。
  - 記住我：`rememberMe().key(${app.remember-me.key}).tokenValiditySeconds(30 * 24 * 3600)`，參數名稱 `remember-me`。
  - `AdminBootstrap implements ApplicationRunner`：`app_user` 為空時，用 `app.admin.*` 建立 `Role.ADMIN`、顯示名稱「管理者」；表為空但帳密沒設定時，啟動失敗並顯示「請在 .env 設定 ADMIN_USERNAME 與 ADMIN_PASSWORD」。`app.remember-me.key` 是空的也要啟動失敗。
  - `layout.html` 的左側選單依 spec 第 3 節順序列出六頁：總覽、總資產、股票、資金來源、固定收支、設定。階段 1 只有「設定」可以點，其他五個顯示成灰色、不可點的文字。

- [ ] **Step 1：寫失敗的測試 `LoginTest`（`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)`；`@BeforeEach` 建立 `alice`／密碼 `password123` 的 USER）**

```java
@Test void anonymousIsRedirectedToLogin()       // GET /settings → 302，Location 以 /login 結尾
@Test void loginWithCorrectPassword()           // formLogin("/login").user("alice").password("password123") → authenticated()
@Test void loginIsCaseAndSpaceInsensitive()     // user(" Alice ") → authenticated()
@Test void wrongPasswordFails()                 // → unauthenticated()，redirect /login?error
@Test void disabledUserCannotLogin()            // disabled=true → unauthenticated()
@Test void rememberMeCookieIssuedFor30Days()    // 帶 remember-me=on → cookie "remember-me" 的 maxAge == 2592000
@Test void normalUserCannotOpenUserAdmin()      // @WithUserDetails("alice") GET /settings/users → 403
@Test void loginPageIsPublic()                  // GET /login → 200，頁面含「帳號」「密碼」「記住我」
```

`AdminBootstrapTest`：

```java
@Test void createsAdminWhenNoUsers()            // 空表 + 有帳密 → findByUsername 得到 ROLE ADMIN，密碼可用 encoder.matches 驗證
@Test void doesNothingWhenUsersExist()          // 已有一個使用者 → 筆數不變
@Test void failsWhenNoUsersAndNoCredentials()   // 空表 + 帳密空字串 → 丟 IllegalStateException，訊息含 ADMIN_USERNAME
```

（`AdminBootstrapTest` 直接 new `AdminBootstrap` 並傳入參數，不必啟動整個 context。）

- [ ] **Step 2：`./mvnw test -Dtest=LoginTest,AdminBootstrapTest`，預期 FAIL。**
- [ ] **Step 3：實作上面 Interfaces 列出的類別與模板。** 測試環境的 `app.remember-me.key` 放在 `src/test/resources/application-test.properties` 或 `@SpringBootTest(properties=...)`。
- [ ] **Step 4：`./mvnw test`，預期全部 PASS。**
- [ ] **Step 5：在 `.env` 加上三個新變數（給使用者自己填），執行 `docker compose up -d --build`，用管理者帳號登入，看到設定頁和左側選單。請使用者確認。**
- [ ] **Step 6：給使用者看 diff，同意後 commit：`feat: 表單登入、初始管理者與記住我`**

---

### Task 3：連續失敗 5 次鎖定 15 分鐘

**Files：**
- Create: `security/LoginAttemptListener.java`
- Modify: `entity/AppUser.java`（加兩個 Entity 方法）
- Modify: `templates/login.html`（錯誤訊息）
- Test: `src/test/java/com/sideproject/stockledger/entity/AppUserLockTest.java`、`security/LoginLockoutTest.java`

**Interfaces：**
- Consumes：`AppUser`、`Clock`、`AppUserDetails.isAccountNonLocked()`
- Produces：
  - `AppUser.recordLoginFailure(LocalDateTime now)`：`failedLoginCount++`；達到 `MAX_FAILED = 5` 時設定 `lockedUntil = now + LOCK_MINUTES(15)`，並把次數歸零。
  - `AppUser.recordLoginSuccess()`：`failedLoginCount = 0`、`lockedUntil = null`。
  - `boolean AppUser.isLockedAt(LocalDateTime now)`。
  - `LoginAttemptListener`：監聽 `AuthenticationFailureBadCredentialsEvent` 和 `AuthenticationSuccessEvent`，在 `@Transactional` 中更新使用者。帳號不存在時什麼都不做。
  - 登入頁訊息：密碼錯誤顯示「帳號或密碼錯誤」（不透露帳號存不存在）；鎖定中（`LockedException`）顯示「登入失敗次數過多，請 15 分鐘後再試」。

（5 次和 15 分鐘是 spec 4.10 的安全規則，不是費率，所以寫成 `AppUser` 的常數，不放 RateSetting。）

- [ ] **Step 1：寫失敗的單元測試 `AppUserLockTest`（純 Java，不需要 Spring）**

```java
@Test void fourFailuresDoNotLock()          // 失敗 4 次 → isLockedAt(now) == false
@Test void fifthFailureLocksFor15Minutes()  // 第 5 次 → isLockedAt(now) true；isLockedAt(now+14:59) true；isLockedAt(now+15:00) false
@Test void successResetsCounter()           // 失敗 4 次 → 成功 → 再失敗 4 次 → 沒有鎖定
```

- [ ] **Step 2：寫失敗的整合測試 `LoginLockoutTest`（用 `@MockitoBean` 或 `@TestConfiguration` 提供可以調整時間的 `Clock`）**

```java
@Test void correctPasswordRejectedWhileLocked()  // 錯 5 次 → 正確密碼 → unauthenticated()，redirect 帶 locked 參數
@Test void canLoginAfterLockExpires()            // 錯 5 次 → 時間 +15 分 → 正確密碼 → authenticated()，failedLoginCount == 0
@Test void unknownUsernameDoesNotThrow()         // 不存在的帳號錯 6 次 → 每次都 unauthenticated()，沒有例外
```

- [ ] **Step 3：`./mvnw test -Dtest=AppUserLockTest,LoginLockoutTest`，預期 FAIL。**
- [ ] **Step 4：實作。** 用 `failureHandler` 判斷例外種類：`LockedException` 導到 `/login?locked`，其他導到 `/login?error`。
- [ ] **Step 5：`./mvnw test`，預期全部 PASS。**
- [ ] **Step 6：給使用者看 diff，同意後 commit：`feat: 連續登入失敗鎖定`**

---

### Task 4：現金帳戶與資料隔離

**Files：**
- Create: `db/migration/V2__create_cash_account.sql`
- Create: `entity/CashAccount.java`、`entity/CashAccountType.java`
- Create: `repository/CashAccountRepository.java`
- Create: `dto/CashAccountForm.java`
- Create: `service/CashAccountService.java`、`service/NotFoundException.java`
- Create: `controller/CashAccountController.java`
- Create: `templates/settings/accounts.html`、`templates/settings/account-form.html`
- Modify: `templates/settings/index.html`（連到現金帳戶）
- Test: `service/CashAccountServiceTest.java`、`controller/CashAccountControllerTest.java`

**Interfaces：**
- Consumes：`CurrentUser.id()`（Task 2）
- Produces：
  - `enum CashAccountType { SETTLEMENT("證券交割戶"), BANK_DEMAND("銀行活存") }`，含 `String label()`。
  - `V2`：`cash_account(id BIGINT AUTO_INCREMENT PK, user_id BIGINT NOT NULL, name VARCHAR(50) NOT NULL, type VARCHAR(20) NOT NULL, institution VARCHAR(50) NOT NULL, created_at DATETIME NOT NULL, FOREIGN KEY (user_id) REFERENCES app_user(id), INDEX idx_cash_account_user (user_id), UNIQUE (user_id, name))`。
  - `CashAccountRepository`：**只提供有帶 userId 的方法**：`List<CashAccount> findByUserIdOrderByIdAsc(Long userId)`、`Optional<CashAccount> findByIdAndUserId(Long id, Long userId)`、`boolean existsByUserIdAndName(Long userId, String name)`。
  - `CashAccountForm`（record）：`@NotBlank @Size(max=50) String name`、`@NotNull CashAccountType type`、`@NotBlank @Size(max=50) String institution`。
  - `CashAccountService`：`List<CashAccount> listMine()`、`CashAccount getMine(Long id)`（找不到就丟 `NotFoundException`）、`CashAccount create(CashAccountForm f)`、`CashAccount update(Long id, CashAccountForm f)`。**方法參數沒有 userId**，一律用 `currentUser.id()`。同一個使用者的帳戶名稱重複時丟 `IllegalArgumentException("帳戶名稱已存在")`。
  - `NotFoundException` 加 `@ResponseStatus(NOT_FOUND)`。
  - 路由：`GET /settings/accounts`、`GET /settings/accounts/new`、`POST /settings/accounts`、`GET /settings/accounts/{id}/edit`、`POST /settings/accounts/{id}`。
  - 不提供刪除：spec 沒有寫刪除帳戶，而且之後的現金異動會參照帳戶（不硬刪除原則）。

- [ ] **Step 1：寫失敗的測試 `CashAccountServiceTest`（`@SpringBootTest` + Testcontainers；用 `@WithUserDetails("alice")`／`"bob"` 切換登入者）**

```java
@Test void createdAccountBelongsToCurrentUser()  // alice 建立 → listMine() 有 1 筆，userId == alice.id
@Test void otherUserCannotSeeAccounts()          // alice 建立 → 切到 bob → listMine() 為空
@Test void otherUserCannotGetById()              // bob 呼叫 getMine(alice 的帳戶 id) → NotFoundException
@Test void otherUserCannotUpdate()               // bob 呼叫 update(alice 的帳戶 id, ...) → NotFoundException，資料沒變
@Test void duplicateNameRejectedPerUser()        // alice 建兩個「國泰交割戶」→ 第二個 IllegalArgumentException；bob 建同名稱 → 成功
```

`CashAccountControllerTest`（MockMvc）：

```java
@Test void otherUsersAccountReturns404()   // bob GET /settings/accounts/{alice 的 id}/edit → 404
@Test void createRequiresCsrf()            // 沒有 csrf() 的 POST → 403
@Test void blankNameShowsValidationError() // name="" → 200，留在表單，頁面含錯誤訊息
@Test void cannotInjectUserId()            // POST 多帶 userId=<bob 的 id> → 建立的帳戶 userId 仍是登入者
```

- [ ] **Step 2：`./mvnw test -Dtest=CashAccount*`，預期 FAIL。**
- [ ] **Step 3：實作 V2、Entity、Repository、Form、Service（`@Transactional`）、Controller、模板。** 列表欄位：名稱、類型、券商或銀行。餘額和對帳日期是階段 4 的內容，這次不做。
- [ ] **Step 4：`./mvnw test`，預期全部 PASS。**
- [ ] **Step 5：給使用者看 diff，同意後 commit：`feat: 現金帳戶與使用者資料隔離`**

---

### Task 5：`RateKind` 與 `EffectiveRateSelector`（純 Java）

**Files：**
- Create: `entity/RateKind.java`
- Create: `calculator/EffectiveRateSelector.java`、`calculator/RateCandidate.java`
- Test: `src/test/java/com/sideproject/stockledger/calculator/EffectiveRateSelectorTest.java`

**Interfaces：**
- Produces：
  - `enum RateKind`，每個值帶 `String label`、`Scope scope`（`LEGAL`／`PERSONAL`）、`Unit unit`（`RATIO` 顯示成百分比，`AMOUNT` 顯示成元）：
    `BROKERAGE_FEE_RATE`（手續費率上限，LEGAL，RATIO）、`SECURITIES_TAX_STOCK`、`SECURITIES_TAX_ETF`、`SECURITIES_TAX_BOND_ETF`、`SECURITIES_TAX_DAY_TRADE`（LEGAL，RATIO）、`NHI_SUPPLEMENT_RATE`（LEGAL，RATIO）、`NHI_SUPPLEMENT_THRESHOLD`（LEGAL，AMOUNT）、`BROKERAGE_DISCOUNT`（PERSONAL，RATIO）、`MIN_FEE_BOARD_LOT`、`MIN_FEE_ODD_LOT`（PERSONAL，AMOUNT）。
    （融資利率、維持率門檻要綁定某一筆借款，階段 3 再加。）
  - `record RateCandidate(Long id, Long userId, LocalDate effectiveDate, BigDecimal value)`。
  - `EffectiveRateSelector`：`static Optional<RateCandidate> select(List<RateCandidate> candidates, LocalDate on)`。規則：只看 `effectiveDate <= on`；**有 userId 的候選優先於 userId 為 null 的**；同一層裡取 `effectiveDate` 最晚的，日期相同取 `id` 最大的。不依賴 Spring。

- [ ] **Step 1：寫失敗的測試**

```java
@Test void picksLatestOnOrBeforeDate()     // 2016-01-01=0.0191、2021-01-01=0.0211；on=2020-12-31 → 0.0191；on=2021-01-01 → 0.0211
@Test void futureRateNotYetEffective()     // 只有 2027-01-01 那一筆，on=2026-12-31 → empty
@Test void sameDateHigherIdWins()          // 兩筆都是 2027-01-01，id 5=0.001、id 9=0 → 0
@Test void userValueOverridesGlobal()      // 全域 2000-01-01=20、使用者 2026-10-01=1；on=2026-10-19 → 1；on=2026-09-30 → 20
@Test void emptyListReturnsEmpty()
```

- [ ] **Step 2：`./mvnw test -Dtest=EffectiveRateSelectorTest`，預期 FAIL。**
- [ ] **Step 3：實作。**
- [ ] **Step 4：預期 PASS。**
- [ ] **Step 5：給使用者看 diff，同意後 commit：`feat: 費率種類與生效日期選擇規則`**

---

### Task 6：`rate_setting` 表、法定費率 seed、費率設定頁

**Files：**
- Create: `db/migration/V3__create_rate_setting.sql`、`db/migration/V4__seed_legal_rates.sql`
- Create: `entity/RateSetting.java`、`repository/RateSettingRepository.java`
- Create: `dto/RateSettingForm.java`、`dto/RateRow.java`
- Create: `service/RateSettingService.java`
- Create: `controller/RateSettingController.java`、`templates/settings/rates.html`
- Test: `service/RateSettingServiceTest.java`、`controller/RateSettingControllerTest.java`

**Interfaces：**
- Consumes：`RateKind`、`EffectiveRateSelector`、`RateCandidate`（Task 5）；`CurrentUser`（Task 2）；`Clock`
- Produces：
  - `V3`：`rate_setting(id BIGINT AUTO_INCREMENT PK, user_id BIGINT NULL, kind VARCHAR(40) NOT NULL, value DECIMAL(19,6) NOT NULL, effective_date DATE NOT NULL, note VARCHAR(200) NULL, created_at DATETIME NOT NULL, FOREIGN KEY (user_id) REFERENCES app_user(id), INDEX idx_rate_kind_user (kind, user_id, effective_date))`。`user_id` 為 NULL 代表法定費率或全域預設。（「適用對象：某筆借款」的 `loan_id` 欄位留到階段 3，用新的 migration 加。）
  - `V4` seed（所有列 `user_id = NULL`，`created_at = '2026-10-19 00:00:00'`，`note` 寫出處）：

    | kind | value | effective_date |
    | --- | --- | --- |
    | BROKERAGE_FEE_RATE | 0.001425 | 2000-01-01 |
    | SECURITIES_TAX_STOCK | 0.003 | 2000-01-01 |
    | SECURITIES_TAX_ETF | 0.001 | 2000-01-01 |
    | SECURITIES_TAX_BOND_ETF | 0.001 | 2000-01-01 |
    | SECURITIES_TAX_BOND_ETF | 0 | 2017-01-01 |
    | SECURITIES_TAX_BOND_ETF | 0.001 | 2027-01-01 |
    | SECURITIES_TAX_DAY_TRADE | 0.003 | 2000-01-01 |
    | SECURITIES_TAX_DAY_TRADE | 0.0015 | 2017-04-28 |
    | SECURITIES_TAX_DAY_TRADE | 0.003 | 2028-01-01 |
    | NHI_SUPPLEMENT_RATE | 0.02 | 2013-01-01 |
    | NHI_SUPPLEMENT_RATE | 0.0191 | 2016-01-01 |
    | NHI_SUPPLEMENT_RATE | 0.0211 | 2021-01-01 |
    | NHI_SUPPLEMENT_THRESHOLD | 5000 | 2013-01-01 |
    | NHI_SUPPLEMENT_THRESHOLD | 20000 | 2016-01-01 |
    | MIN_FEE_BOARD_LOT | 20 | 2000-01-01 |
    | MIN_FEE_ODD_LOT | 1 | 2000-01-01 |

  - `RateSettingRepository`：`List<RateSetting> findByKindAndUserIdIsNullOrKindAndUserId(RateKind k1, RateKind k2, Long userId)`，或用 `@Query`「`kind = :kind AND (user_id IS NULL OR user_id = :userId)`」，兩種寫法都可以；`List<RateSetting> findByUserIdIsNullOrUserIdOrderByKindAscEffectiveDateDescIdDesc(Long userId)`（設定頁的歷史版本）。
  - `RateSettingService`：
    - `Optional<BigDecimal> findEffective(RateKind kind, LocalDate on)`：只用 `currentUser.id()` 和全域列，交給 `EffectiveRateSelector`。LEGAL 種類忽略使用者自己的列。
    - `List<RateRow> currentTable()`：每個 `RateKind` 一列，內容是今天（`LocalDate.now(clock)`）的生效值、生效日期、來源（「法定」／「預設」／「個人」）、歷史版本；沒有值就顯示「未設定」。
    - `void addPersonalVersion(RateSettingForm f)`：只接受 `scope == PERSONAL` 的種類，否則丟 `IllegalArgumentException`。
  - `RateSettingForm`（record）：`@NotNull RateKind kind`、`@NotNull BigDecimal value`、`@NotNull LocalDate effectiveDate`、`@Size(max=200) String note`。換算放在 Service：RATIO 種類的畫面輸入是百分比，`value ÷ 100`，`setScale(6, HALF_UP)`；檢查：`BROKERAGE_DISCOUNT` 要 0 < 輸入 ≤ 100，AMOUNT 種類要 ≥ 0 而且是整數。
  - `RateRow`（record）：`RateKind kind`、`String displayValue`（Service 先格式化好：RATIO 顯示成 `0.1425%`，AMOUNT 顯示成 `20 元`，沒有值顯示 `未設定`）、`LocalDate effectiveDate`、`String sourceLabel`、`List<RateRow> history`。
  - 路由：`GET /settings/rates`、`POST /settings/rates`。頁面分成「法定費率」（唯讀）和「個人費率」（有新增版本的表單）兩個區塊。

- [ ] **Step 1：寫失敗的測試 `RateSettingServiceTest`（Testcontainers；資料來自真的 V4 seed）**

```java
@Test void nhiRateToday()                    // findEffective(NHI_SUPPLEMENT_RATE, 2026-10-19) == 0.021100
@Test void nhiThreshold()                    // (NHI_SUPPLEMENT_THRESHOLD, 2026-10-19) == 20000
@Test void dayTradeHalfRateEndsAfter2027()   // 2027-12-31 → 0.0015；2028-01-01 → 0.003
@Test void bondEtfExemptUntil2026()          // 2026-12-31 → 0；2027-01-01 → 0.001
@Test void minFeeDefaultsThenPersonal()      // alice 查 MIN_FEE_BOARD_LOT → 20；新增個人版本 1、2026-10-01 → 查詢 2026-10-19 得到 1；bob 查還是 20
@Test void discountUnsetIsEmpty()            // BROKERAGE_DISCOUNT → Optional.empty()
@Test void percentInputConverted()           // 新增 BROKERAGE_DISCOUNT 輸入 28 → 存成 0.280000
@Test void cannotAddLegalKind()              // addPersonalVersion(SECURITIES_TAX_STOCK, ...) → IllegalArgumentException
@Test void discountOutOfRangeRejected()      // 輸入 0、-5、150 → IllegalArgumentException
@Test void minFeeMustBeWholeNonNegative()    // 輸入 -1、1.5 → IllegalArgumentException
@Test void futurePersonalRateNotYetApplied() // 新增 2026-12-01 的版本 → 查詢 2026-10-19 仍然是舊值
@Test void tableShowsUnsetLabel()            // currentTable() 裡 BROKERAGE_DISCOUNT 那一列 displayValue == "未設定"
```

`RateSettingControllerTest`：

```java
@Test void pageShowsLegalAndPersonalSections()  // GET /settings/rates → 200，含「法定費率」「個人費率」「0.1425%」
@Test void legalKindPostRejected()              // POST kind=SECURITIES_TAX_STOCK → 400，或留在頁面並顯示錯誤；資料庫沒有新增列
```

- [ ] **Step 2：`./mvnw test -Dtest=RateSetting*`，預期 FAIL。**
- [ ] **Step 3：實作 V3、V4、Entity、Repository、DTO、Service、Controller、模板。**
- [ ] **Step 4：`./mvnw test`，預期全部 PASS。**
- [ ] **Step 5：給使用者看 diff，同意後 commit：`feat: 費率設定與法定費率 seed`**

---

### Task 7：使用者管理（只有管理者能用）

**Files：**
- Create: `dto/NewUserForm.java`、`dto/ResetPasswordForm.java`
- Create: `service/UserAdminService.java`
- Create: `controller/UserAdminController.java`、`templates/settings/users.html`
- Test: `service/UserAdminServiceTest.java`、`controller/UserAdminControllerTest.java`

**Interfaces：**
- Consumes：`AppUser`、`AppUserRepository`、`PasswordEncoder`、`CurrentUser.isAdmin()`、`Clock`
- Produces：
  - `NewUserForm`（record）：`@NotBlank @Size(max=50) String username`、`@NotBlank @Size(max=50) String displayName`、`@NotBlank @Size(min=8, max=72) String password`、`@NotNull Role role`。（72 是 BCrypt 的長度上限。）
  - `ResetPasswordForm`（record）：`@NotBlank @Size(min=8, max=72) String newPassword`。
  - `UserAdminService`（class 加 `@PreAuthorize("hasRole('ADMIN')")`，`SecurityConfig` 加 `@EnableMethodSecurity`；網址和方法兩層都擋）：`List<AppUser> listAll()`、`AppUser create(NewUserForm f)`（帳號先正規化，重複時丟 `IllegalArgumentException("帳號已存在")`）、`void resetPassword(Long userId, ResetPasswordForm f)`（同時把失敗次數歸零、解除鎖定）。
  - 路由：`GET /settings/users`、`POST /settings/users`、`POST /settings/users/{id}/password`。列表顯示帳號、顯示名稱、角色、是否鎖定；不顯示密碼雜湊。

- [ ] **Step 1：寫失敗的測試**

```java
// UserAdminServiceTest
@Test void adminCreatesUserWithHashedPassword()      // 建立 bob → passwordHash 以 "{bcrypt}" 開頭，encoder.matches 為 true
@Test void duplicateUsernameIgnoringCaseIsRejected() // 已有 alice，建立 "ALICE " → IllegalArgumentException
@Test void resetPasswordUnlocksUser()                // bob 鎖定中 → resetPassword → 新密碼可登入，lockedUntil == null
@Test void nonAdminCallIsDenied()                    // @WithUserDetails("alice")（USER）呼叫 create → AccessDeniedException
// UserAdminControllerTest
@Test void newUserCanLoginAndSeesNoOtherData()       // 管理者建立 bob → bob 登入 → GET /settings/accounts 不含 alice 的帳戶名稱
@Test void shortPasswordShowsError()                 // password="1234567" → 留在頁面並顯示錯誤，沒有新增使用者
```

- [ ] **Step 2：`./mvnw test -Dtest=UserAdmin*`，預期 FAIL。**
- [ ] **Step 3：實作。**
- [ ] **Step 4：`./mvnw test`，預期全部 PASS。**
- [ ] **Step 5：給使用者看 diff，同意後 commit：`feat: 管理者建立使用者與重設密碼`**

---

### Task 8：更新規格書，完成階段 1 驗收

**Files：**
- Modify: `docs/spec.md`（第 8 節加 DR-026～DR-029；第 9 節勾選已查證項目；版本說明加 v1.3）

**內容：**
- DR-026：證交稅率依種類拆成四種 `RateKind`；同日期多筆時 id 大的優先（附查證來源與日期）。
- DR-027：「記住我」用雜湊 token 加 `REMEMBER_ME_KEY`，不用 `persistent_logins`（原因：違反主鍵與 `TIMESTAMP` 規則）。
- DR-028：整合測試用 Testcontainers MySQL 8.4，不用 H2。
- DR-029：初始管理者由 `.env` 的 `ADMIN_USERNAME`／`ADMIN_PASSWORD` 建立；登入鎖定移到階段 1。
- 第 9 節：勾選「查證證交稅與二代健保費率」，並新增一項「債券 ETF 停徵延長案三讀後，新增 migration（2027-01-01，0%）」。

- [ ] **Step 1：給使用者看 spec 的修改內容，同意後再寫入。**
- [ ] **Step 2：`./mvnw test` 全部 PASS（Docker Desktop 要開著）。**
- [ ] **Step 3：驗收（請使用者操作）：** `docker compose up -d --build` → 用管理者登入，建立 `bob` 和 `carol` → 分別登入，各自建立一個現金帳戶 → 確認互相看不到；在其中一個帳號連續輸錯密碼 5 次，確認被鎖定；在費率頁看到法定費率，並新增一筆手續費折扣。
- [ ] **Step 4：給使用者看 diff，同意後 commit：`docs: 規格書 v1.3 加入階段 1 的決策紀錄`**
