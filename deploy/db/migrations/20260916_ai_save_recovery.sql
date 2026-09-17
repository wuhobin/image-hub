-- 已有数据库在部署新版后端前执行一次；新库直接使用 schema.sql。
ALTER TABLE hub_ai_generation
    ADD COLUMN pending_storage_info TEXT COMMENT '上传前记录定位，入库成功或补偿删除后清空';
