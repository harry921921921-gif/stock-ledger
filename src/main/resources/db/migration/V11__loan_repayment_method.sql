-- V11：借款依「還款方式」記錄（spec 4.8a、DR-048）
--   MONTHLY_FIXED：每月固定還款（本金＋利息一起扣；扣款金額照銀行，存成 rate_setting 的 LOAN_MONTHLY_PAYMENT 版本）
--   BULLET：到期一次還本金＋利息（平常不扣，只算應計利息）
--   INTEREST_ONLY：每月繳息、到期還本
-- 「股票質押」沿用 loan_type：PLEDGE＝股票質押（有擔保股票與維持率），CREDIT＝一般借款；和怎麼還分開。
-- 新增開辦費現金異動類型 LOAN_FEE（撥款日記一筆，算進借款成本）。

-- 1. 還款方式：舊借款照原本的行為對應（信貸 → 每月固定還款、質押 → 每月繳息到期還本）
ALTER TABLE loan ADD COLUMN repayment_method VARCHAR(20) NULL AFTER loan_type;
UPDATE loan SET repayment_method = CASE loan_type WHEN 'CREDIT' THEN 'MONTHLY_FIXED' ELSE 'INTEREST_ONLY' END;
ALTER TABLE loan MODIFY COLUMN repayment_method VARCHAR(20) NOT NULL;

-- 2. 期數與到期日改由還款方式決定（取代 V9 依 loan_type 的 ck_loan_credit）
--    要寫 IS NOT NULL：NULL 的比較結果是 NULL，CHECK 遇到 NULL 會當成通過
--    INTEREST_ONLY 的到期日可以是 NULL：V11 以前建立的質押沒有填到期日（新表單會要求填）
ALTER TABLE loan DROP CHECK ck_loan_credit;
ALTER TABLE loan ADD CONSTRAINT ck_loan_method CHECK (
    (repayment_method = 'MONTHLY_FIXED' AND remaining_periods IS NOT NULL AND remaining_periods >= 1
        AND first_period IS NOT NULL AND DAY(first_period) = 1)
    OR (repayment_method = 'BULLET' AND remaining_periods IS NULL AND first_period IS NULL AND due_date IS NOT NULL)
    OR (repayment_method = 'INTEREST_ONLY' AND remaining_periods IS NULL AND first_period IS NULL));

-- 3. 開辦費：和其他借款異動一樣要有所屬借款
ALTER TABLE cash_movement DROP CHECK ck_cash_movement_type;
ALTER TABLE cash_movement DROP CHECK ck_cash_movement_loan;
ALTER TABLE cash_movement
    ADD CONSTRAINT ck_cash_movement_type CHECK (type IN ('OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'RECONCILE',
        'LOAN_DISBURSEMENT', 'LOAN_PRINCIPAL', 'LOAN_INTEREST', 'LOAN_FEE', 'STOCK_BUY', 'STOCK_SELL', 'DIVIDEND')),
    ADD CONSTRAINT ck_cash_movement_loan CHECK (
        (type IN ('LOAN_DISBURSEMENT', 'LOAN_PRINCIPAL', 'LOAN_INTEREST', 'LOAN_FEE')) = (loan_id IS NOT NULL));

-- 4. 每月扣款金額（照銀行）是綁借款的版本，和利率一樣有生效日
ALTER TABLE rate_setting DROP CHECK ck_rate_setting_loan;
ALTER TABLE rate_setting ADD CONSTRAINT ck_rate_setting_loan CHECK (
    (kind IN ('LOAN_ANNUAL_RATE', 'MAINTENANCE_WARN', 'MAINTENANCE_CALL', 'LOAN_MONTHLY_PAYMENT')) = (loan_id IS NOT NULL)
    AND (loan_id IS NULL OR user_id IS NOT NULL));
