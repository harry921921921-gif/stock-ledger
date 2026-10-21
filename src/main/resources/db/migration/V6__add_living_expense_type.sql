-- 生活費（DR-034）：使用者每月直接輸入一筆生活費，和領出一樣減少餘額，但在本月淨資產變動裡單獨列一行。
-- V5 已經執行過不能修改，所以用這支 migration 放寬類型限制；金額規則（> 0、整數）沿用 V5 的 CHECK。
ALTER TABLE cash_movement DROP CHECK ck_cash_movement_type;
ALTER TABLE cash_movement ADD CONSTRAINT ck_cash_movement_type
    CHECK (type IN ('OPENING', 'DEPOSIT', 'WITHDRAWAL', 'LIVING_EXPENSE', 'TRANSFER', 'RECONCILE'));
