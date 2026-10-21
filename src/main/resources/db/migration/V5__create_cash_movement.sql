-- 現金事件（spec 4.6、6 CashMovement、DR-032）：初始資金、存入、領出、帳戶互轉、對帳。
-- 只存原始事件：餘額、餘額調整、每月變動都由 CashLedgerCalculator 推算，不另存結果（spec 7.2）。
-- 對帳存的是使用者輸入的「實際餘額」（actual_balance），差額每次重新推算。
-- 不硬刪除：作廢用 voided 旗標；修改與作廢都寫 cash_movement_revision（spec 4.9、DR-033）。
-- 金額只存正數（初始資金可以是 0），方向由 type 決定。
-- 之後的階段（借款、股票交易、配息）會用新的 migration 加關聯欄位，不修改這個檔案。
-- 異動、帳戶、修改紀錄一定屬於同一個使用者：外鍵連同 user_id 一起指過去，資料庫本身就擋得住「用別人的帳戶記帳」。
-- 為此 cash_account 需要 (id, user_id) 的唯一索引（id 本身已唯一，這個索引只是讓外鍵可以指向這一對）。
ALTER TABLE cash_account ADD CONSTRAINT uk_cash_account_id_user UNIQUE (id, user_id);

CREATE TABLE cash_movement (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    type            VARCHAR(20)   NOT NULL,
    movement_date   DATE          NOT NULL,
    account_id      BIGINT        NOT NULL,
    to_account_id   BIGINT        NULL,
    amount          DECIMAL(19,4) NULL,
    actual_balance  DECIMAL(19,4) NULL,
    note            VARCHAR(100)  NULL,
    voided          BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      DATETIME      NOT NULL,
    -- 讓資料庫保證「每個帳戶最多一筆沒作廢的初始資金」「每個帳戶每天最多一筆沒作廢的對帳」：
    -- 不適用的列算出 NULL，而唯一索引允許多個 NULL。程式（Entity）不對應這兩欄。
    opening_account_id   BIGINT GENERATED ALWAYS AS
        (CASE WHEN type = 'OPENING' AND voided = FALSE THEN account_id END) STORED,
    reconcile_account_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN type = 'RECONCILE' AND voided = FALSE THEN account_id END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT fk_cash_movement_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uk_cash_movement_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_cash_movement_account FOREIGN KEY (account_id, user_id) REFERENCES cash_account (id, user_id),
    -- to_account_id 為 NULL 時（不是互轉）外鍵不檢查
    CONSTRAINT fk_cash_movement_to_account FOREIGN KEY (to_account_id, user_id) REFERENCES cash_account (id, user_id),
    INDEX idx_cash_movement_user_date (user_id, movement_date),
    CONSTRAINT uk_cash_movement_opening UNIQUE (opening_account_id),
    CONSTRAINT uk_cash_movement_reconcile_day UNIQUE (reconcile_account_id, movement_date),
    CONSTRAINT ck_cash_movement_type CHECK (type IN ('OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'RECONCILE')),
    -- 只有互轉有轉入帳戶，而且不能轉給自己
    CONSTRAINT ck_cash_movement_transfer CHECK (
        (type = 'TRANSFER') = (to_account_id IS NOT NULL)
        AND (to_account_id IS NULL OR to_account_id <> account_id)),
    -- 只有對帳有實際餘額，而且對帳不存金額
    CONSTRAINT ck_cash_movement_reconcile CHECK (
        (type = 'RECONCILE') = (actual_balance IS NOT NULL)
        AND (type <> 'RECONCILE' OR amount IS NULL)),
    -- 新台幣只記整數元
    CONSTRAINT ck_cash_movement_whole_dollars CHECK (
        (amount IS NULL OR amount = TRUNCATE(amount, 0))
        AND (actual_balance IS NULL OR actual_balance = TRUNCATE(actual_balance, 0))),
    -- 其他類型一定有金額：初始資金 ≥ 0，存入、領出、互轉 > 0
    CONSTRAINT ck_cash_movement_amount CHECK (
        type = 'RECONCILE'
        OR (amount IS NOT NULL AND (amount > 0 OR (type = 'OPENING' AND amount = 0))))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 現金事件的修改紀錄（spec 4.9、DR-033）：每個改到的欄位一列；作廢也寫一列（field = voided）。
-- 只指向現金事件（外鍵），不用「事件類型 + id」的多型關聯。
CREATE TABLE cash_movement_revision (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    cash_movement_id BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    revised_at       DATETIME     NOT NULL,
    action           VARCHAR(10)  NOT NULL,
    field            VARCHAR(30)  NOT NULL,
    old_value        VARCHAR(200) NULL,
    new_value        VARCHAR(200) NULL,
    reason           VARCHAR(200) NULL,
    PRIMARY KEY (id),
    -- 修改紀錄的使用者一定是那筆異動的擁有者
    CONSTRAINT fk_cash_movement_revision_movement FOREIGN KEY (cash_movement_id, user_id) REFERENCES cash_movement (id, user_id),
    CONSTRAINT fk_cash_movement_revision_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_cash_movement_revision_action CHECK (action IN ('EDIT', 'VOID'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
