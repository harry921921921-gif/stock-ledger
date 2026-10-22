-- 取消「生活費」類型（DR-035，取代 DR-034）：生活費改由固定收支「每月自己填」的項目記錄，記出來的是一般的支出。
-- 既有的生活費異動（含已作廢的）改成支出（WITHDRAWAL）；備註空白的補上「生活費」，看得出原本是什麼。
-- 每個改到的欄位都寫一列修改紀錄（spec 4.9）；先寫紀錄、再改資料，紀錄裡的舊值才是改之前的。
-- 金額與日期都不變，所以餘額與每月淨資產變動合計不變；只是拆解裡原本的「生活費」併入「支出」。
-- V5、V6 已經執行過不能修改，所以用這支 migration 改類型限制。

INSERT INTO cash_movement_revision
    (cash_movement_id, user_id, revised_at, action, field, old_value, new_value, reason)
SELECT id, user_id, NOW(), 'EDIT', 'type', 'LIVING_EXPENSE', 'WITHDRAWAL', '生活費類型取消，改為支出（DR-035）'
FROM cash_movement
WHERE type = 'LIVING_EXPENSE'
ORDER BY id;

INSERT INTO cash_movement_revision
    (cash_movement_id, user_id, revised_at, action, field, old_value, new_value, reason)
SELECT id, user_id, NOW(), 'EDIT', 'note', note, '生活費', '生活費類型取消，改為支出（DR-035）'
FROM cash_movement
WHERE type = 'LIVING_EXPENSE' AND (note IS NULL OR TRIM(note) = '')
ORDER BY id;

UPDATE cash_movement
SET note = '生活費'
WHERE type = 'LIVING_EXPENSE' AND (note IS NULL OR TRIM(note) = '');

UPDATE cash_movement
SET type = 'WITHDRAWAL'
WHERE type = 'LIVING_EXPENSE';

ALTER TABLE cash_movement DROP CHECK ck_cash_movement_type;
ALTER TABLE cash_movement ADD CONSTRAINT ck_cash_movement_type
    CHECK (type IN ('OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'RECONCILE'));
