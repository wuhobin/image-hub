-- 已完成 20260911_time_seconds.sql 的 MySQL 8 数据库执行此脚本。
-- TIMESTAMP 转 DATETIME 按会话时区保留显示值，与本项目 Asia/Shanghai 配置一致。
SET @image_hub_previous_time_zone = @@session.time_zone;
SET SESSION time_zone = '+08:00';

ALTER TABLE hub_user
    MODIFY COLUMN `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE hub_image
    MODIFY COLUMN `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

SET SESSION time_zone = @image_hub_previous_time_zone;
