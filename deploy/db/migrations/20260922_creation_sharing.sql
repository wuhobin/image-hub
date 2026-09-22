-- 已有数据库执行一次；先备份，停止旧后端，迁移后同步部署前后端及 Nginx 路由。
-- 历史作品默认私有，不自动公开图片或提示词。
ALTER TABLE hub_ai_generation
    ADD COLUMN share_id VARCHAR(36) DEFAULT NULL COMMENT '独立分享编号，撤销时清空',
    ADD COLUMN share_status VARCHAR(12) NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE未公开，PUBLIC公开，BLOCKED管理员下架',
    ADD COLUMN prompt_public TINYINT NOT NULL DEFAULT 1 COMMENT '是否公开提示词',
    ADD COLUMN published_time datetime DEFAULT NULL COMMENT '最近一次发布时间',
    ADD UNIQUE KEY uk_hub_ai_generation_share (share_id),
    ADD KEY idx_hub_ai_generation_shared (share_status, deleted, status, published_time, share_id);
