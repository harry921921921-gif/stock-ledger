-- 使用者（spec 6、4.10）。登入用的帳號表本身，不帶 user_id。
-- 日期一律 DATETIME，不用 TIMESTAMP（上限 2038 年）。
-- 明確指定 collation，不依賴伺服器預設值：50 年內還原到別台伺服器時，比對規則才不會悄悄改變。
-- username 用 utf8mb4_bin 做精確比對（大小寫已由程式正規化），避免 "jose" 和 "josé" 被當成同一個帳號。
CREATE TABLE app_user (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    username           VARCHAR(50)  COLLATE utf8mb4_bin NOT NULL,
    password_hash      VARCHAR(100) NOT NULL,
    display_name       VARCHAR(50)  NOT NULL,
    role               VARCHAR(10)  NOT NULL,
    disabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_count INT          NOT NULL DEFAULT 0,
    locked_until       DATETIME     NULL,
    created_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_username UNIQUE (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
