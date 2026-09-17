-- 保留完整上游错误消息；仅扩容字段，不修改已有任务数据，可重复执行。
ALTER TABLE hub_ai_generation MODIFY COLUMN error_message TEXT NULL;
