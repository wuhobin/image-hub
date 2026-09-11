-- Run once against the configured image_hub database (MySQL 8+, utf8mb4).
CREATE TABLE IF NOT EXISTS hub_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_hub_user_username (username),
    UNIQUE KEY uk_hub_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_image (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    type VARCHAR(8) NOT NULL,
    size BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    storage_info TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_hub_image_user_created (user_id, created_at, id),
    CONSTRAINT fk_hub_image_user FOREIGN KEY (user_id) REFERENCES hub_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
