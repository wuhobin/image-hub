-- 部署前停止全部旧实例，确认没有正在执行的生成任务；本脚本删除图片暂存字段，不可回退结果字节。
-- 旧版待保存任务不再支持恢复，保留历史并释放预留；云文件定位保留供人工核查。
UPDATE hub_ai_generation
SET status = 'FAILED', active_user_id = NULL, work_token = NULL, work_deadline = NULL,
    api_key_ciphertext = NULL, error_message = '生成结果暂存已取消，请重新提交创作',
    update_time = CURRENT_TIMESTAMP
WHERE status IN ('GENERATING', 'SAVING', 'SAVE_FAILED');

ALTER TABLE hub_ai_generation
    DROP INDEX idx_hub_ai_generation_expiry,
    DROP INDEX idx_hub_ai_generation_work,
    DROP COLUMN result_data,
    DROP COLUMN result_expires_at,
    ADD INDEX idx_hub_ai_generation_work (deleted, status, work_token, create_time, id);
