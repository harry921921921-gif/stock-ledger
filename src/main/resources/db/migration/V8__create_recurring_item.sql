-- 固定收支（設計文件 4、spec 6 RecurringItem、DR-035）。
-- 這是「設定」不是交易事件：修改直接更新、不寫修改紀錄；不硬刪除，不用了就填結束月份。
-- 到期時自動產生的是一般的現金異動（存入或支出），用 cash_movement 的 recurring_item_id + period 記錄它屬於哪一期。
CREATE TABLE recurring_item (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id      BIGINT        NOT NULL,
    name         VARCHAR(50)   NOT NULL,
    direction    VARCHAR(10)   NOT NULL,
    -- FIXED：時間到自動記帳；MANUAL：每月等使用者填金額才記（例如生活費）
    amount_mode  VARCHAR(10)   NOT NULL,
    amount       DECIMAL(19,4) NULL,
    cycle        VARCHAR(12)   NOT NULL,
    -- 每年：那個月；每半年：那個月和 6 個月後；每月為 NULL
    anchor_month INT           NULL,
    -- 該月沒有那一天時用該月最後一天（由程式計算）
    day_of_month INT           NOT NULL,
    account_id   BIGINT        NOT NULL,
    -- 月份都存成那個月的 1 號
    start_month  DATE          NOT NULL,
    end_month    DATE          NULL,
    -- 從哪一期開始記帳：建立時等於開始月份；改了月份、入帳日或帳戶就改成那時的月份，
    -- 這樣修改只影響還沒產生的期數，不會用新的規則回頭補記過去（例如年繳從 1 月改成 3 月）
    posting_from DATE          NOT NULL,
    created_at   DATETIME      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_recurring_item_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uk_recurring_item_id_user UNIQUE (id, user_id),
    -- 只能用自己的帳戶
    CONSTRAINT fk_recurring_item_account FOREIGN KEY (account_id, user_id) REFERENCES cash_account (id, user_id),
    INDEX idx_recurring_item_user (user_id),
    CONSTRAINT ck_recurring_item_codes CHECK (
        direction IN ('INCOME', 'EXPENSE')
        AND amount_mode IN ('FIXED', 'MANUAL')
        AND cycle IN ('MONTHLY', 'SEMIANNUAL', 'YEARLY')),
    -- 固定金額：正的整數元；每月自己填：不存金額，而且只能每月
    CONSTRAINT ck_recurring_item_amount CHECK (
        (amount_mode = 'FIXED' AND amount IS NOT NULL AND amount > 0 AND amount = TRUNCATE(amount, 0))
        OR (amount_mode = 'MANUAL' AND amount IS NULL AND cycle = 'MONTHLY')),
    -- 要寫 IS NOT NULL：NULL BETWEEN 1 AND 12 的結果是 NULL，而 CHECK 遇到 NULL 會當成通過
    CONSTRAINT ck_recurring_item_anchor CHECK (
        (cycle = 'MONTHLY' AND anchor_month IS NULL)
        OR (cycle <> 'MONTHLY' AND anchor_month IS NOT NULL AND anchor_month BETWEEN 1 AND 12)),
    CONSTRAINT ck_recurring_item_day CHECK (day_of_month BETWEEN 1 AND 31),
    CONSTRAINT ck_recurring_item_months CHECK (
        DAY(start_month) = 1
        AND (end_month IS NULL OR (DAY(end_month) = 1 AND end_month >= start_month))
        AND DAY(posting_from) = 1 AND posting_from >= start_month)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 自動產生（或每月自己填）的異動記下屬於哪個項目、哪一期（該期所在月份的 1 號）。
-- 同一項目同一期最多一筆「沒作廢」的：作廢後那一期空出來（每月自己填的可以重填）；
-- 固定金額的項目「作廢後不再自動補」由產生程式檢查（含作廢的）。
ALTER TABLE cash_movement
    ADD COLUMN recurring_item_id BIGINT NULL,
    ADD COLUMN period DATE NULL,
    ADD COLUMN recurring_active_item_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN recurring_item_id IS NOT NULL AND voided = FALSE THEN recurring_item_id END) STORED,
    ADD CONSTRAINT fk_cash_movement_recurring_item FOREIGN KEY (recurring_item_id, user_id)
        REFERENCES recurring_item (id, user_id),
    ADD CONSTRAINT uk_cash_movement_recurring_period UNIQUE (recurring_active_item_id, period),
    ADD CONSTRAINT ck_cash_movement_recurring CHECK (
        (recurring_item_id IS NULL) = (period IS NULL)
        AND (recurring_item_id IS NULL OR (type IN ('DEPOSIT', 'WITHDRAWAL') AND DAY(period) = 1)));
