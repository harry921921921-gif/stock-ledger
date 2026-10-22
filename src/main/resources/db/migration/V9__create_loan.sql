-- 資金來源：借款（設計文件 2026-10-22-funding-sources、spec 6 Loan）。這一版有信貸與質押；融資在股票階段另加 migration。
-- loan 是「設定」：修改直接更新、不寫修改紀錄。撥款、還本、利息是現金異動（cash_movement），照 spec 4.9 寫修改紀錄。
-- 剩餘本金、是否還清、攤還表、應計利息都由事件推算，不另存（CLAUDE.md、設計文件 11-8）。
CREATE TABLE loan (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    user_id             BIGINT        NOT NULL,
    name                VARCHAR(50)   NOT NULL,
    loan_type           VARCHAR(10)   NOT NULL,
    bank                VARCHAR(50)   NOT NULL,
    -- 原始借款金額（顯示用；計算從起點本金開始）
    original_amount     DECIMAL(19,4) NOT NULL,
    -- NEW：新撥款，起算日記一筆借款撥款；EXISTING：開始用系統前就有的借款，起算日只是起點、不記現金異動
    origin              VARCHAR(10)   NOT NULL,
    start_date          DATE          NOT NULL,
    start_principal     DECIMAL(19,4) NOT NULL,
    disburse_account_id BIGINT        NULL,
    -- 信貸的扣款帳戶、質押的繳息帳戶
    pay_account_id      BIGINT        NOT NULL,
    -- 每月扣款日或繳息日（1～31，沒有那天用月底）
    pay_day             INT           NOT NULL,
    -- 信貸：起算時的剩餘期數與第一期扣款月份（1 號）；質押沒有
    remaining_periods   INT           NULL,
    first_period        DATE          NULL,
    -- 質押的到期日（可不填）
    due_date            DATE          NULL,
    -- 從哪一期開始自動記帳（同 DR-037）：改了扣款日或帳戶就移到那時的月份
    posting_from        DATE          NOT NULL,
    -- 作廢（例如起算本金打錯、已經開始記帳）：連同所有借款紀錄一起作廢，不硬刪除
    voided              BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at          DATETIME      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_loan_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uk_loan_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_loan_pay_account FOREIGN KEY (pay_account_id, user_id) REFERENCES cash_account (id, user_id),
    CONSTRAINT fk_loan_disburse_account FOREIGN KEY (disburse_account_id, user_id) REFERENCES cash_account (id, user_id),
    INDEX idx_loan_user (user_id),
    CONSTRAINT ck_loan_codes CHECK (loan_type IN ('CREDIT', 'PLEDGE') AND origin IN ('NEW', 'EXISTING')),
    CONSTRAINT ck_loan_amounts CHECK (
        original_amount > 0 AND original_amount = TRUNCATE(original_amount, 0)
        AND start_principal > 0 AND start_principal = TRUNCATE(start_principal, 0)),
    CONSTRAINT ck_loan_pay_day CHECK (pay_day BETWEEN 1 AND 31),
    -- 要寫 IS NOT NULL：NULL 的比較結果是 NULL，CHECK 遇到 NULL 會當成通過
    CONSTRAINT ck_loan_credit CHECK (
        (loan_type = 'CREDIT' AND remaining_periods IS NOT NULL AND remaining_periods >= 1
            AND first_period IS NOT NULL AND DAY(first_period) = 1)
        OR (loan_type = 'PLEDGE' AND remaining_periods IS NULL AND first_period IS NULL)),
    CONSTRAINT ck_loan_origin CHECK (
        (origin = 'NEW' AND disburse_account_id IS NOT NULL)
        OR (origin = 'EXISTING' AND disburse_account_id IS NULL)),
    CONSTRAINT ck_loan_posting_from CHECK (DAY(posting_from) = 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 借款年利率：每筆借款各自的版本，依生效日期取得（CLAUDE.md：費率不可寫死）。
ALTER TABLE rate_setting
    ADD COLUMN loan_id BIGINT NULL,
    ADD CONSTRAINT fk_rate_setting_loan FOREIGN KEY (loan_id, user_id) REFERENCES loan (id, user_id),
    ADD CONSTRAINT ck_rate_setting_loan CHECK (
        (kind = 'LOAN_ANNUAL_RATE') = (loan_id IS NOT NULL)
        AND (loan_id IS NULL OR user_id IS NOT NULL));

-- 借款的現金異動：撥款（存入帳戶）、還本、利息（從帳戶扣）。都有所屬借款。
-- 自動記的還本與利息用 period（該期所在月份的 1 號）辨認：同一借款、同一類型、同一期最多一筆沒作廢的。
ALTER TABLE cash_movement DROP CHECK ck_cash_movement_type;
ALTER TABLE cash_movement DROP CHECK ck_cash_movement_recurring;
ALTER TABLE cash_movement
    ADD COLUMN loan_id BIGINT NULL,
    ADD COLUMN loan_active_key VARCHAR(40) GENERATED ALWAYS AS
        (CASE WHEN loan_id IS NOT NULL AND voided = FALSE AND period IS NOT NULL
            THEN CONCAT(loan_id, '-', type) END) STORED,
    ADD CONSTRAINT fk_cash_movement_loan FOREIGN KEY (loan_id, user_id) REFERENCES loan (id, user_id),
    ADD CONSTRAINT uk_cash_movement_loan_period UNIQUE (loan_active_key, period),
    ADD CONSTRAINT ck_cash_movement_type CHECK (type IN ('OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'RECONCILE',
        'LOAN_DISBURSEMENT', 'LOAN_PRINCIPAL', 'LOAN_INTEREST')),
    ADD CONSTRAINT ck_cash_movement_loan CHECK (
        (type IN ('LOAN_DISBURSEMENT', 'LOAN_PRINCIPAL', 'LOAN_INTEREST')) = (loan_id IS NOT NULL)),
    -- 取代 V8 的同名限制：期別現在可能屬於固定項目或借款（不能同時）；固定項目的規則不變
    ADD CONSTRAINT ck_cash_movement_recurring CHECK (
        (recurring_item_id IS NULL OR (period IS NOT NULL AND type IN ('DEPOSIT', 'WITHDRAWAL')))
        AND (period IS NULL OR ((recurring_item_id IS NOT NULL OR loan_id IS NOT NULL) AND DAY(period) = 1))
        AND (recurring_item_id IS NULL OR loan_id IS NULL));
