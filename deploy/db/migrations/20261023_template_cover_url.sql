-- 已有数据库在部署新后端前执行一次；不修改现有示例图关联与存储文件。
ALTER TABLE hub_creation_template
    ADD COLUMN cover_url VARCHAR(2048) DEFAULT NULL COMMENT '外部封面图片URL' AFTER fields_json;
