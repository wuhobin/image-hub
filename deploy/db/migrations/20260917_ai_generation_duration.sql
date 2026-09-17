-- 已有数据库执行一次；旧任务没有实测耗时，保留 NULL，不使用审计时间回填。
ALTER TABLE hub_ai_generation
    ADD COLUMN duration_millis BIGINT DEFAULT NULL COMMENT '实际生成及保存耗时（毫秒），不含排队和失败补偿' AFTER error_message;
