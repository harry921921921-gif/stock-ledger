-- 費率與門檻（spec 4.5、6 RateSetting）。每次變動新增一列、附生效日期，不修改舊列（spec 4.5-2）。
-- user_id 為 NULL：法定費率或全域預設（所有人共用）；有值：該使用者自己設定的版本。
-- 哪一筆適用由 EffectiveRateSelector 決定：生效日期 ≤ 查詢日、個人優先於全域、同日期 id 大的優先。
-- value 用 DECIMAL(19,6)：spec 沒規定費率型別；6 位小數放得下 0.001425，整數部分放得下 20000 這類門檻。
-- 「適用對象：某筆借款」的 loan_id 留到階段 3 用新的 migration 加。
CREATE TABLE rate_setting (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    user_id        BIGINT        NULL,
    kind           VARCHAR(40)   NOT NULL,
    value          DECIMAL(19,6) NOT NULL,
    effective_date DATE          NOT NULL,
    note           VARCHAR(200)  NULL,
    created_at     DATETIME      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_rate_setting_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    INDEX idx_rate_kind_user (kind, user_id, effective_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
