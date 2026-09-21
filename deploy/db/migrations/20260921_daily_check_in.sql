-- 已有数据库执行一次；新数据库使用 schema.sql。保留已有消费记录，不补录历史。
ALTER TABLE hub_quota_usage
    ADD COLUMN direction VARCHAR(8) NOT NULL DEFAULT 'EXPENSE' COMMENT 'INCOME收入，EXPENSE支出',
    ADD KEY idx_hub_quota_usage_income (user_id, direction, deleted),
    ADD CONSTRAINT chk_hub_quota_usage_direction CHECK (direction IN ('INCOME', 'EXPENSE'));

CREATE TABLE IF NOT EXISTS hub_user_check_in
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    PRIMARY
    KEY,
    user_id
    BIGINT
    NOT
    NULL
    COMMENT
    '签到用户',
    check_in_date
    DATE
    NOT
    NULL
    COMMENT
    '北京时间签到日期',
    consecutive_days
    INT
    NOT
    NULL
    COMMENT
    '截至当日的连续签到天数',
    daily_points
    INT
    NOT
    NULL
    COMMENT
    '当日基础奖励快照',
    bonus_points
    INT
    NOT
    NULL
    DEFAULT
    0
    COMMENT
    '每连续七天额外奖励快照',
    create_time
    datetime
    DEFAULT
    CURRENT_TIMESTAMP
    COMMENT
    '创建时间',
    update_time
    datetime
    DEFAULT
    CURRENT_TIMESTAMP
    ON
    UPDATE
    CURRENT_TIMESTAMP
    COMMENT
    '更新时间',
    deleted
    TINYINT
    NOT
    NULL
    DEFAULT
    0
    COMMENT
    '逻辑删除：0未删除，1已删除',
    UNIQUE
    KEY
    uk_hub_user_check_in_day
(
    user_id,
    check_in_date
),
    CONSTRAINT chk_hub_user_check_in_rewards CHECK
(
    consecutive_days >
    0
    AND
    daily_points >
    0
    AND
    bonus_points
    >=
    0
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_unicode_ci;

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'check-in.daily-points',
       '10',
       '每日签到奖励积分' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'check-in.daily-points');

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'check-in.bonus-points',
       '30',
       '每连续签到七天额外奖励积分' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'check-in.bonus-points');
