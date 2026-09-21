-- 只记录功能启用后的实际消耗，不补录历史图片。
CREATE TABLE IF NOT EXISTS hub_quota_usage (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '消耗额度的用户',
    scene VARCHAR(32) NOT NULL COMMENT 'IMAGE_UPLOAD图片上传，AI_GENERATION AI创作',
    biz_id VARCHAR(36) NOT NULL COMMENT '图片ID或AI任务ID，作为业务防重编号',
    amount INT NOT NULL COMMENT '实际消耗额度，正整数',
    description VARCHAR(255) NOT NULL COMMENT '业务说明快照，图片删除后仍保留',
    create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '消耗时间',
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    UNIQUE KEY uk_hub_quota_usage_biz (user_id, scene, biz_id),
    KEY idx_hub_quota_usage_user_created (user_id, deleted, create_time, id),
    CONSTRAINT chk_hub_quota_usage_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
