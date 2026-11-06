-- 已有环境先执行此脚本，再部署普通上传幂等功能；新环境使用 schema.sql。
-- 重复执行保持幂等，不改写已有图片；该字段不对外返回。
SET
@upload_hash_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'hub_image' AND column_name = 'upload_hash'
);
SET
@upload_hash_sql = IF(@upload_hash_exists = 0,
    'ALTER TABLE hub_image ADD COLUMN upload_hash CHAR(64) DEFAULT NULL COMMENT ''普通上传请求的内容SHA-256，幂等重试校验使用'' AFTER storage_info',
    'SELECT 1');
PREPARE upload_hash_statement FROM @upload_hash_sql;
EXECUTE upload_hash_statement;
DEALLOCATE PREPARE upload_hash_statement;
