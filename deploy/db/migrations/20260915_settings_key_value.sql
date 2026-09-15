-- 仅用于旧版 hub_settings（包含 free_upload_quota 列），停后端后执行一次。
-- 旧版约定仅有 id=1 一条配置，迁移保留原额度、主键、审计时间和逻辑删除状态。
ALTER TABLE hub_settings
    ADD COLUMN config_key VARCHAR(100) DEFAULT NULL COMMENT '代码预置的唯一配置键',
    ADD COLUMN config_value TEXT DEFAULT NULL COMMENT '配置值，由业务代码解析和校验类型',
    ADD COLUMN description VARCHAR(255) DEFAULT NULL COMMENT '配置用途说明';

UPDATE hub_settings
SET config_key = 'upload.free-total',
    config_value = CAST(free_upload_quota AS CHAR),
    description = '所有用户的免费累计上传总额度，0暂停上传',
    update_time = update_time
WHERE id = 1;

-- 如果存在非预期的其他旧配置行，NOT NULL 校验将终止迁移，不静默丢弃记录。
ALTER TABLE hub_settings
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
    MODIFY COLUMN config_key VARCHAR(100) NOT NULL COMMENT '代码预置的唯一配置键',
    MODIFY COLUMN config_value TEXT NOT NULL COMMENT '配置值，由业务代码解析和校验类型',
    ADD UNIQUE KEY uk_hub_settings_config_key (config_key),
    DROP CHECK ck_hub_settings_quota,
    DROP COLUMN free_upload_quota;
