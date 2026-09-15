CREATE TABLE IF NOT EXISTS hub_settings (
    id BIGINT NOT NULL PRIMARY KEY,
    free_upload_quota INT NOT NULL DEFAULT 100 COMMENT '所有用户的免费累计上传总额度，0暂停上传',
    create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    CONSTRAINT ck_hub_settings_quota CHECK (free_upload_quota >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 重复执行不能覆盖管理员已经保存的设置。
INSERT INTO hub_settings (id, free_upload_quota)
SELECT 1, 100 WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE id = 1);
