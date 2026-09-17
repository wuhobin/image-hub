-- Run once against the configured image_hub database (MySQL 8+, utf8mb4).
CREATE TABLE IF NOT EXISTS hub_settings (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL COMMENT '代码预置的唯一配置键',
    config_value TEXT NOT NULL COMMENT '配置值，由业务代码解析和校验类型',
    description VARCHAR(255) DEFAULT NULL COMMENT '配置用途说明',
    create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    UNIQUE KEY uk_hub_settings_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 重复执行不能覆盖管理员已经保存的设置。
INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'upload.free-total', '100', '所有用户的免费累计上传总额度，0暂停上传'
WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'upload.free-total');

CREATE TABLE IF NOT EXISTS hub_admin (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    UNIQUE KEY uk_hub_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    UNIQUE KEY uk_hub_user_username (username),
    UNIQUE KEY uk_hub_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_image (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    type VARCHAR(8) NOT NULL,
    source_type VARCHAR(12) NOT NULL DEFAULT 'UPLOAD' COMMENT 'UPLOAD用户上传，AI生成',
    size BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    storage_info TEXT NOT NULL,
    quota_charged TINYINT NOT NULL DEFAULT 0 COMMENT '是否消耗新版上传额度：历史0，新上传1',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    KEY idx_hub_image_user_created (user_id, deleted, create_time, id),
    KEY idx_hub_image_user_quota (user_id, quota_charged),
    CONSTRAINT fk_hub_image_user FOREIGN KEY (user_id) REFERENCES hub_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模型配置只在后台开放；首次预置的模型未配置密钥，保持停用。
CREATE TABLE IF NOT EXISTS hub_ai_model (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    model_code VARCHAR(120) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    images_path VARCHAR(200) NOT NULL DEFAULT '/v1/images/generations',
    api_key_ciphertext TEXT,
    sizes VARCHAR(1000) NOT NULL,
    default_size VARCHAR(20) NOT NULL,
    qualities VARCHAR(100) NOT NULL,
    default_quality VARCHAR(20) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_hub_ai_model_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO hub_ai_model (name, model_code, base_url, images_path, sizes, default_size, qualities, default_quality)
SELECT 'GPT-Image-2', 'gpt-image-2', 'https://api.openai.com', '/v1/images/generations',
       '1024x1024,2048x2048,2880x2880,1536x1024,2160x1440,3456x2304,1024x1536,1440x2160,2304x3456,1280x720,2560x1440,3840x2160,720x1280,1440x2560,2160x3840,1024x768,2048x1536,3200x2400,768x1024,1536x2048,2400x3200,1344x576,2016x864,3808x1632', '1024x1024', 'low,medium,high,auto', 'medium'
WHERE NOT EXISTS (SELECT 1 FROM hub_ai_model WHERE name = 'GPT-Image-2');

CREATE TABLE IF NOT EXISTS hub_ai_generation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    active_user_id BIGINT DEFAULT NULL COMMENT '未完成时等于用户ID，终态NULL',
    model_id BIGINT NOT NULL,
    model_name VARCHAR(80) NOT NULL,
    model_code VARCHAR(120) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    images_path VARCHAR(200) NOT NULL,
    api_key_ciphertext TEXT,
    prompt TEXT NOT NULL,
    image_size VARCHAR(20) NOT NULL,
    quality VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    work_token VARCHAR(36),
    work_deadline datetime,
    pending_storage_info TEXT COMMENT '上传前记录定位，入库成功或补偿删除后清空',
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_hub_ai_generation_request (user_id, request_id),
    UNIQUE KEY uk_hub_ai_generation_active (active_user_id),
    KEY idx_hub_ai_generation_user (user_id, deleted, create_time, id),
    KEY idx_hub_ai_generation_work (deleted, status, work_token, create_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
