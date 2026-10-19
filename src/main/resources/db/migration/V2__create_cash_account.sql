-- 現金帳戶（spec 4.6、6 CashAccount）：證券交割戶、銀行活存。
-- 個人資料：一定帶 user_id 外鍵與索引；同一個人的帳戶名稱不可重複（不同人可以同名）。
-- 餘額不存在這裡：一律由之後的現金異動事件推算（spec 7.2）。
-- 不提供刪除：之後的現金異動會參照帳戶。
-- name 用表格預設的 utf8mb4_0900_ai_ci：比對重複時不分大小寫、不分重音（"Cathay" 和 "cathay" 算同名），這是刻意的；
-- 和 app_user.username 用 utf8mb4_bin 精確比對不同。
-- idx_cash_account_user 和 UNIQUE (user_id, name) 的前綴重複，但保留它：萬一之後拿掉唯一限制，外鍵仍有索引。
CREATE TABLE cash_account (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    name        VARCHAR(50) NOT NULL,
    type        VARCHAR(20) NOT NULL,
    institution VARCHAR(50) NOT NULL,
    created_at  DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cash_account_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    INDEX idx_cash_account_user (user_id),
    CONSTRAINT uk_cash_account_user_name UNIQUE (user_id, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
