-- 上线前执行一次。已有图片默认 0，不消耗新额度；不重置任何已有 Redis 额度。
ALTER TABLE hub_image
    ADD COLUMN quota_charged TINYINT NOT NULL DEFAULT 0 COMMENT '是否消耗新版上传额度：历史0，新上传1',
    ADD INDEX idx_hub_image_user_quota (user_id, quota_charged);
