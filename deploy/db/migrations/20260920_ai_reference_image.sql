-- 已有数据库执行一次；无参考图的历史任务保持 NULL。
ALTER TABLE hub_ai_generation
    ADD COLUMN reference_image_url VARCHAR(2048) DEFAULT NULL COMMENT '参考图来源，Redis临时定位或历史云地址' AFTER prompt;
