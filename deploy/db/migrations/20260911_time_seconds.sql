-- 已完成 audit_and_logic_delete 迁移的 MySQL 8 数据库执行此脚本。
-- 先截去毫秒再降低列精度，避免 .535 四舍五入到下一秒。
-- 两个时间字段同时赋值，防止 ON UPDATE 将历史更新时间改成迁移时刻。
UPDATE hub_user
SET create_time = DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s'),
    update_time = DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s')
WHERE MICROSECOND(create_time) <> 0 OR MICROSECOND(update_time) <> 0;
ALTER TABLE hub_user
    MODIFY COLUMN create_time TIMESTAMP(0) NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '创建时间',
    MODIFY COLUMN update_time TIMESTAMP(0) NOT NULL DEFAULT CURRENT_TIMESTAMP(0) ON UPDATE CURRENT_TIMESTAMP(0) COMMENT '更新时间';

UPDATE hub_image
SET create_time = DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s'),
    update_time = DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s')
WHERE MICROSECOND(create_time) <> 0 OR MICROSECOND(update_time) <> 0;
ALTER TABLE hub_image
    MODIFY COLUMN create_time TIMESTAMP(0) NOT NULL DEFAULT CURRENT_TIMESTAMP(0) COMMENT '创建时间',
    MODIFY COLUMN update_time TIMESTAMP(0) NOT NULL DEFAULT CURRENT_TIMESTAMP(0) ON UPDATE CURRENT_TIMESTAMP(0) COMMENT '更新时间';
