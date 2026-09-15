-- 已有 AI 数据库升级：扩展 GPT-Image-2 的旧预置尺寸；保留管理员自定义白名单。
-- 可重复执行，不修改密钥、地址、默认尺寸或已提交任务的快照。
UPDATE hub_ai_model
SET sizes = '1024x1024,1536x1024,1024x1536,1280x720,720x1280,1536x1152,1152x1536,1792x768'
WHERE deleted = 0
  AND (model_code = 'gpt-image-2' OR model_code LIKE 'gpt-image-2-%')
  AND sizes = '1024x1024,1536x1024,1024x1536';
