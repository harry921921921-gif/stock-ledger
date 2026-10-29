-- V12：第一次使用引導的進度（spec DR-050）
--   onboarding_step 是畫面進度，不是財務資料。新帳號一律從 WELCOME 開始。
--   既有帳號只要有任何現金帳戶，就當作已經在用，直接標成 DONE，不打擾。

ALTER TABLE app_user ADD COLUMN onboarding_step VARCHAR(20) NOT NULL DEFAULT 'WELCOME';

ALTER TABLE app_user ADD CONSTRAINT ck_app_user_onboarding_step
    CHECK (onboarding_step IN ('WELCOME', 'ACCOUNTS', 'LOANS', 'STOCKS', 'COLLATERAL', 'DONE'));

UPDATE app_user SET onboarding_step = 'DONE'
WHERE EXISTS (SELECT 1 FROM cash_account a WHERE a.user_id = app_user.id);
