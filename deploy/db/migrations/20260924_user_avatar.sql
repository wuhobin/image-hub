-- 已有数据库执行一次；新增可空字段兼容未设置头像的用户，不改变已有图片与积分。
ALTER TABLE hub_user
    ADD COLUMN avatar_image_id VARCHAR(36) DEFAULT NULL COMMENT '本人头像图片ID，空值使用默认头像';
