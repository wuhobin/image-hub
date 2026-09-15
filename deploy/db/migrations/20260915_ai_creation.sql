-- 升级前停止旧实例；先备份数据库，再执行本迁移。
ALTER TABLE hub_image ADD COLUMN source_type VARCHAR(12) NOT NULL DEFAULT 'UPLOAD' COMMENT 'UPLOAD用户上传，AI生成';
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
    error_message VARCHAR(255),
    work_token VARCHAR(36),
    work_deadline datetime,
    result_expires_at datetime,
    result_data MEDIUMBLOB COMMENT '最多10MB，保存完成或24小时过期时清理',
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_hub_ai_generation_request (user_id, request_id),
    UNIQUE KEY uk_hub_ai_generation_active (active_user_id),
    KEY idx_hub_ai_generation_user (user_id, deleted, create_time, id),
    KEY idx_hub_ai_generation_work (deleted, status, work_token, work_deadline),
    KEY idx_hub_ai_generation_expiry (deleted, result_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
