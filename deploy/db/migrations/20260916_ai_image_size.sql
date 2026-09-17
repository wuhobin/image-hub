-- AI 图片上限可配置，使用 LONGBLOB 避免超过 MEDIUMBLOB 的 16 MiB 容量。
-- 可重复执行；仅扩展字段容量，不修改现有任务。应用启动前执行。
ALTER TABLE hub_ai_generation
    MODIFY COLUMN result_data LONGBLOB COMMENT '大小由AI配置限制，保存完成或24小时过期时清理';
