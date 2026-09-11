-- 仅适用于仍使用 created_at 的旧版 MySQL 8 数据库，执行一次。
-- 执行前停止旧版后端；不要在刚用新版 schema.sql 初始化的库重复执行。
-- CHANGE COLUMN 保留历史创建时间，唯一约束及外键保持不变。
ALTER TABLE hub_user
    CHANGE COLUMN created_at create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    ADD COLUMN update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除';
UPDATE hub_user SET update_time = create_time WHERE deleted = 0 AND update_time <> create_time;

ALTER TABLE hub_image
    CHANGE COLUMN created_at create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    ADD COLUMN update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    DROP INDEX idx_hub_image_user_created,
    ADD KEY idx_hub_image_user_created (user_id, deleted, create_time, id);
UPDATE hub_image SET update_time = create_time WHERE deleted = 0 AND update_time <> create_time;
