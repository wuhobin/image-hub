-- 已有数据库执行一次；停止旧后端后迁移，再启动新版，避免混用按张数计费的旧逻辑。
-- 原有模型、任务及计费图片均保持 1 积分，不补录历史流水，不需要清理 Redis。
ALTER TABLE hub_ai_model
    ADD COLUMN points_cost INT NOT NULL DEFAULT 1 COMMENT '每生成一张图片消耗的积分',
    ADD CONSTRAINT chk_hub_ai_model_points_cost CHECK (points_cost > 0);

ALTER TABLE hub_ai_generation
    ADD COLUMN points_cost INT NOT NULL DEFAULT 1 COMMENT '提交时的积分快照',
    ADD CONSTRAINT chk_hub_ai_generation_points_cost CHECK (points_cost > 0);

ALTER TABLE hub_image
    ADD COLUMN points_cost INT NOT NULL DEFAULT 1 COMMENT '实际消耗积分，AI使用任务快照',
    ADD CONSTRAINT chk_hub_image_points_cost CHECK (points_cost > 0);
