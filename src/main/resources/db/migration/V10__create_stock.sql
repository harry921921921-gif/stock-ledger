-- 股票（設計文件 2026-10-23-stocks、spec 6、7.1）。
-- 共用資料（沒有 user_id）：股票清單、每日收盤價、除權除息、更新紀錄，只來自證交所 OpenAPI（DR-004、DR-024）。
-- 個人資料：交易、交易修改紀錄、資金來源分攤、質押擔保品。持股、成本、報酬率都由事件推算，不另存。
CREATE TABLE stock (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    code               VARCHAR(10)  NOT NULL,
    name               VARCHAR(50)  NOT NULL,
    -- 依代號判斷：00 開頭是 ETF，再以 B 結尾是債券 ETF（決定證交稅率）
    kind               VARCHAR(10)  NOT NULL,
    -- 年配息次數：API 沒有，使用者手動填（spec 6 Stock）
    dividends_per_year INT          NULL,
    updated_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_code UNIQUE (code),
    CONSTRAINT ck_stock_kind CHECK (kind IN ('STOCK', 'ETF', 'BOND_ETF')),
    CONSTRAINT ck_stock_dividends_per_year CHECK (dividends_per_year IS NULL OR dividends_per_year BETWEEN 1 AND 12)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE stock_price (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    stock_id    BIGINT        NOT NULL,
    trade_date  DATE          NOT NULL,
    close_price DECIMAL(12,4) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_stock_price_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT uk_stock_price_day UNIQUE (stock_id, trade_date),
    CONSTRAINT ck_stock_price_positive CHECK (close_price > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 除權除息預告（TWT48U_ALL）：API 只給近期，所以每次抓到就存起來；現金股利公告前可能是空的。
CREATE TABLE dividend (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    stock_id       BIGINT        NOT NULL,
    ex_date        DATE          NOT NULL,
    cash_per_share DECIMAL(12,6) NULL,
    stock_ratio    DECIMAL(12,8) NULL,
    updated_at     DATETIME      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dividend_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT uk_dividend_ex_date UNIQUE (stock_id, ex_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE price_update_log (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    run_at    DATETIME     NOT NULL,
    kind      VARCHAR(20)  NOT NULL,
    data_date DATE         NULL,
    row_count INT          NOT NULL,
    result    VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_price_update_log_kind (kind, run_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 股票交易事件：期初持股、買進、賣出、配股。
-- amount：期初、買進是總成本（含手續費）；賣出是實際入帳金額；配股是 0。
CREATE TABLE trade (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    user_id    BIGINT        NOT NULL,
    type       VARCHAR(20)   NOT NULL,
    trade_date DATE          NOT NULL,
    stock_id   BIGINT        NOT NULL,
    account_id BIGINT        NULL,
    shares     BIGINT        NOT NULL,
    price      DECIMAL(12,4) NULL,
    fee        DECIMAL(19,4) NULL,
    tax        DECIMAL(19,4) NULL,
    amount     DECIMAL(19,4) NOT NULL,
    voided     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at DATETIME      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_trade_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uk_trade_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_trade_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT fk_trade_account FOREIGN KEY (account_id, user_id) REFERENCES cash_account (id, user_id),
    INDEX idx_trade_user_date (user_id, trade_date),
    CONSTRAINT ck_trade_type CHECK (type IN ('OPENING', 'BUY', 'SELL', 'STOCK_DIVIDEND')),
    -- 要寫 IS NOT NULL：CHECK 遇到 NULL 會當成通過
    CONSTRAINT ck_trade_shape CHECK (
        (type = 'OPENING' AND account_id IS NULL AND price IS NULL AND fee IS NULL AND tax IS NULL AND amount > 0)
        OR (type = 'BUY' AND account_id IS NOT NULL AND price IS NOT NULL AND fee IS NOT NULL AND tax IS NULL
            AND amount > 0)
        OR (type = 'SELL' AND account_id IS NOT NULL AND price IS NOT NULL AND fee IS NOT NULL AND tax IS NOT NULL
            AND amount > 0)
        OR (type = 'STOCK_DIVIDEND' AND account_id IS NULL AND price IS NULL AND fee IS NULL AND tax IS NULL
            AND amount = 0)),
    CONSTRAINT ck_trade_amounts CHECK (
        shares > 0
        AND (price IS NULL OR price > 0)
        AND (fee IS NULL OR (fee >= 0 AND fee = TRUNCATE(fee, 0)))
        AND (tax IS NULL OR (tax >= 0 AND tax = TRUNCATE(tax, 0)))
        AND amount = TRUNCATE(amount, 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE trade_revision (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    trade_id   BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    revised_at DATETIME     NOT NULL,
    action     VARCHAR(10)  NOT NULL,
    field      VARCHAR(30)  NOT NULL,
    old_value  VARCHAR(200) NULL,
    new_value  VARCHAR(200) NULL,
    reason     VARCHAR(200) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_trade_revision_trade FOREIGN KEY (trade_id, user_id) REFERENCES trade (id, user_id),
    CONSTRAINT fk_trade_revision_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_trade_revision_action CHECK (action IN ('EDIT', 'VOID'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 期初持股與買進的資金來源：只存借款出的錢，其餘是自有（現金帳戶不記來源，DR-002）。
CREATE TABLE source_allocation (
    id       BIGINT        NOT NULL AUTO_INCREMENT,
    trade_id BIGINT        NOT NULL,
    user_id  BIGINT        NOT NULL,
    loan_id  BIGINT        NULL,
    amount   DECIMAL(19,4) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_source_allocation_trade FOREIGN KEY (trade_id, user_id) REFERENCES trade (id, user_id),
    CONSTRAINT fk_source_allocation_loan FOREIGN KEY (loan_id, user_id) REFERENCES loan (id, user_id),
    CONSTRAINT uk_source_allocation UNIQUE (trade_id, loan_id),
    CONSTRAINT ck_source_allocation CHECK (loan_id IS NOT NULL AND amount > 0 AND amount = TRUNCATE(amount, 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 質押的擔保股票（spec 6 PledgeCollateral、5.5）
CREATE TABLE pledge_collateral (
    id       BIGINT NOT NULL AUTO_INCREMENT,
    user_id  BIGINT NOT NULL,
    loan_id  BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    shares   BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pledge_collateral_loan FOREIGN KEY (loan_id, user_id) REFERENCES loan (id, user_id),
    CONSTRAINT fk_pledge_collateral_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT uk_pledge_collateral UNIQUE (loan_id, stock_id),
    CONSTRAINT ck_pledge_collateral_shares CHECK (shares > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 維持率提醒線、追繳線也綁定借款（DR-007）
ALTER TABLE rate_setting DROP CHECK ck_rate_setting_loan;
ALTER TABLE rate_setting ADD CONSTRAINT ck_rate_setting_loan CHECK (
    (kind IN ('LOAN_ANNUAL_RATE', 'MAINTENANCE_WARN', 'MAINTENANCE_CALL')) = (loan_id IS NOT NULL)
    AND (loan_id IS NULL OR user_id IS NOT NULL));

-- 現金異動：買進股票（從交割戶扣）、賣出股票（存入交割戶）、配息（存入，記二代健保扣繳額）
ALTER TABLE cash_movement DROP CHECK ck_cash_movement_type;
ALTER TABLE cash_movement
    ADD COLUMN trade_id BIGINT NULL,
    ADD COLUMN stock_id BIGINT NULL,
    ADD COLUMN nhi_amount DECIMAL(19,4) NULL,
    ADD COLUMN trade_active_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN trade_id IS NOT NULL AND voided = FALSE THEN trade_id END) STORED,
    ADD CONSTRAINT fk_cash_movement_trade FOREIGN KEY (trade_id, user_id) REFERENCES trade (id, user_id),
    ADD CONSTRAINT fk_cash_movement_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    ADD CONSTRAINT uk_cash_movement_trade UNIQUE (trade_active_id),
    ADD CONSTRAINT ck_cash_movement_type CHECK (type IN ('OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'RECONCILE',
        'LOAN_DISBURSEMENT', 'LOAN_PRINCIPAL', 'LOAN_INTEREST', 'STOCK_BUY', 'STOCK_SELL', 'DIVIDEND')),
    ADD CONSTRAINT ck_cash_movement_stock CHECK (
        (type IN ('STOCK_BUY', 'STOCK_SELL')) = (trade_id IS NOT NULL)
        AND (type = 'DIVIDEND') = (stock_id IS NOT NULL)
        AND (nhi_amount IS NULL OR (type = 'DIVIDEND' AND nhi_amount >= 0 AND nhi_amount = TRUNCATE(nhi_amount, 0))));
