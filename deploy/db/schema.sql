-- Run once against the configured image_hub database (MySQL 8+, utf8mb4).
CREATE TABLE IF NOT EXISTS hub_settings (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL COMMENT '代码预置的唯一配置键',
    config_value TEXT NOT NULL COMMENT '配置值，由业务代码解析和校验类型',
    description VARCHAR(255) DEFAULT NULL COMMENT '配置用途说明',
    create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    UNIQUE KEY uk_hub_settings_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 重复执行不能覆盖管理员已经保存的设置。
INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'upload.free-total',
       '100',
       '所有用户的基础免费积分，签到收入另计'
WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'upload.free-total');

CREATE TABLE IF NOT EXISTS hub_admin (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    UNIQUE KEY uk_hub_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    avatar_image_id VARCHAR
(
    36
) DEFAULT NULL COMMENT '本人头像图片ID，空值使用默认头像',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    UNIQUE KEY uk_hub_user_username (username),
    invite_code VARCHAR
(
    32
) DEFAULT NULL COMMENT '稳定的公开邀请码，按需生成',
    UNIQUE KEY uk_hub_user_invite_code
(
    invite_code
),
    UNIQUE KEY uk_hub_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_image (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    type VARCHAR(8) NOT NULL,
    source_type VARCHAR
(
    12
) NOT NULL DEFAULT 'UPLOAD' COMMENT 'UPLOAD用户上传，AI生成，AVATAR专用头像',
    size BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    storage_info TEXT NOT NULL,
    upload_hash CHAR
(
    64
) DEFAULT NULL COMMENT '普通上传请求的内容SHA-256，幂等重试校验使用',
    quota_charged TINYINT NOT NULL DEFAULT 0 COMMENT '是否消耗新版上传积分：历史0，新上传1',
    points_cost INT NOT NULL DEFAULT 1 COMMENT '实际消耗积分，AI使用任务快照',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    KEY idx_hub_image_user_created (user_id, deleted, create_time, id),
    KEY idx_hub_image_user_quota (user_id, quota_charged),
    CONSTRAINT fk_hub_image_user FOREIGN KEY (user_id) REFERENCES hub_user(id),
    CONSTRAINT chk_hub_image_points_cost CHECK (points_cost > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 记录实际积分收支，预留和失败释放不记账，不补录历史图片。
CREATE TABLE IF NOT EXISTS hub_quota_usage (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id
    BIGINT
    NOT
    NULL
    COMMENT
    '积分所属用户',
    scene
    VARCHAR
(
    32
) NOT NULL COMMENT 'IMAGE_UPLOAD上传，AI_GENERATION创作，DAILY_CHECK_IN签到，CHECK_IN_BONUS连签奖励',
    biz_id VARCHAR
(
    36
) NOT NULL COMMENT '计费图片ID或签到日期，作为业务防重编号',
    amount INT NOT NULL COMMENT '积分变动数量，正整数',
    direction VARCHAR
(
    8
) NOT NULL DEFAULT 'EXPENSE' COMMENT 'INCOME收入，EXPENSE支出',
    description VARCHAR(255) NOT NULL COMMENT '业务说明快照，图片删除后仍保留',
    create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '收支时间',
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',
    UNIQUE KEY uk_hub_quota_usage_biz (user_id, scene, biz_id),
    KEY idx_hub_quota_usage_user_created (user_id, deleted, create_time, id),
    KEY idx_hub_quota_usage_income
(
    user_id,
    direction,
    deleted
),
    CONSTRAINT chk_hub_quota_usage_amount CHECK
(
    amount >
    0
),
    CONSTRAINT chk_hub_quota_usage_direction CHECK
(
    direction
    IN
(
    'INCOME',
    'EXPENSE'
))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_user_check_in
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    PRIMARY
    KEY,
    user_id
    BIGINT
    NOT
    NULL
    COMMENT
    '签到用户',
    check_in_date
    DATE
    NOT
    NULL
    COMMENT
    '北京时间签到日期',
    consecutive_days
    INT
    NOT
    NULL
    COMMENT
    '截至当日的连续签到天数',
    daily_points
    INT
    NOT
    NULL
    COMMENT
    '当日基础奖励快照',
    bonus_points
    INT
    NOT
    NULL
    DEFAULT
    0
    COMMENT
    '每连续七天额外奖励快照',
    create_time
    datetime
    DEFAULT
    CURRENT_TIMESTAMP
    COMMENT
    '创建时间',
    update_time
    datetime
    DEFAULT
    CURRENT_TIMESTAMP
    ON
    UPDATE
    CURRENT_TIMESTAMP
    COMMENT
    '更新时间',
    deleted
    TINYINT
    NOT
    NULL
    DEFAULT
    0
    COMMENT
    '逻辑删除：0未删除，1已删除',
    UNIQUE
    KEY
    uk_hub_user_check_in_day
(
    user_id,
    check_in_date
),
    CONSTRAINT chk_hub_user_check_in_rewards CHECK
(
    consecutive_days >
    0
    AND
    daily_points >
    0
    AND
    bonus_points
    >=
    0
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_unicode_ci;

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'check-in.daily-points',
       '10',
       '每日签到奖励积分' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'check-in.daily-points');

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'check-in.bonus-points',
       '30',
       '每连续签到七天额外奖励积分' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'check-in.bonus-points');

-- 模型配置只在后台开放；首次预置的模型未配置密钥，保持停用。
CREATE TABLE IF NOT EXISTS hub_ai_model (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    model_code VARCHAR(120) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    images_path VARCHAR(200) NOT NULL DEFAULT '/v1/images/generations',
    api_key_ciphertext TEXT,
    sizes VARCHAR(1000) NOT NULL,
    default_size VARCHAR(20) NOT NULL,
    qualities VARCHAR(100) NOT NULL,
    default_quality VARCHAR(20) NOT NULL,
    points_cost INT NOT NULL DEFAULT 1 COMMENT '每生成一张图片消耗的积分',
    enabled TINYINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_hub_ai_model_name (name),
    CONSTRAINT chk_hub_ai_model_points_cost CHECK (points_cost > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO hub_ai_model (name, model_code, base_url, images_path, sizes, default_size, qualities, default_quality)
SELECT 'GPT-Image-2', 'gpt-image-2', 'https://api.openai.com', '/v1/images/generations',
       '1024x1024,2048x2048,2880x2880,1536x1024,2160x1440,3456x2304,1024x1536,1440x2160,2304x3456,1280x720,2560x1440,3840x2160,720x1280,1440x2560,2160x3840,1024x768,2048x1536,3200x2400,768x1024,1536x2048,2400x3200,1344x576,2016x864,3808x1632', '1024x1024', 'low,medium,high,auto', 'medium'
WHERE NOT EXISTS (SELECT 1 FROM hub_ai_model WHERE name = 'GPT-Image-2');

CREATE TABLE IF NOT EXISTS hub_ai_generation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    active_user_id BIGINT DEFAULT NULL COMMENT '未完成时等于用户ID，终态NULL',
    model_id BIGINT NOT NULL,
    points_cost INT NOT NULL DEFAULT 1 COMMENT '提交时的积分快照',
    model_name VARCHAR(80) NOT NULL,
    model_code VARCHAR(120) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    images_path VARCHAR(200) NOT NULL,
    api_key_ciphertext TEXT,
    prompt TEXT NOT NULL,
    reference_image_url VARCHAR(2048) DEFAULT NULL COMMENT '参考图来源，Redis临时定位或历史云地址',
    image_size VARCHAR(20) NOT NULL,
    quality VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    duration_millis BIGINT DEFAULT NULL COMMENT '实际生成及保存耗时（毫秒），不含排队和失败补偿',
    work_token VARCHAR(36),
    work_deadline datetime,
    pending_storage_info TEXT COMMENT '上传前记录定位，入库成功或补偿删除后清空',
    share_id VARCHAR
(
    36
) DEFAULT NULL COMMENT '独立分享编号，撤销时清空',
    share_status VARCHAR
(
    12
) NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE未公开，PUBLIC公开，BLOCKED管理员下架',
    prompt_public TINYINT NOT NULL DEFAULT 1 COMMENT '是否公开提示词',
    published_time datetime DEFAULT NULL COMMENT '最近一次发布时间',
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_hub_ai_generation_request (user_id, request_id),
    UNIQUE KEY uk_hub_ai_generation_active (active_user_id),
    UNIQUE KEY uk_hub_ai_generation_share
(
    share_id
),
    KEY idx_hub_ai_generation_shared
(
    share_status,
    deleted,
    status,
    published_time,
    share_id
),
    KEY idx_hub_ai_generation_user (user_id, deleted, create_time, id),
    KEY idx_hub_ai_generation_work (deleted, status, work_token, create_time, id),
    CONSTRAINT chk_hub_ai_generation_points_cost CHECK (points_cost > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_user_invitation
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    PRIMARY
    KEY,
    inviter_id
    BIGINT
    NOT
    NULL
    COMMENT
    '直接邀请人',
    invitee_id
    BIGINT
    NOT
    NULL
    COMMENT
    '受邀新用户，一生只能绑定一次',
    register_ip
    VARCHAR
(
    45
) NOT NULL COMMENT '服务端取得的注册来源，仅供审核',
    inviter_points INT NOT NULL COMMENT '注册时邀请人奖励快照',
    invitee_points INT NOT NULL COMMENT '注册时新用户奖励快照',
    status VARCHAR
(
    12
) NOT NULL COMMENT 'PAID已到账，PENDING待审核，REJECTED已拒绝，DISABLED活动暂停',
    risk_reason VARCHAR
(
    255
) DEFAULT NULL,
    reviewer_id BIGINT DEFAULT NULL,
    review_note VARCHAR
(
    255
) DEFAULT NULL,
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_hub_invitation_invitee
(
    invitee_id
),
    KEY idx_hub_invitation_recent
(
    inviter_id,
    register_ip,
    deleted,
    create_time
),
    KEY idx_hub_invitation_admin
(
    status,
    deleted,
    create_time,
    id
),
    CONSTRAINT chk_hub_invitation_participants CHECK
(
    inviter_id
    <>
    invitee_id
),
    CONSTRAINT chk_hub_invitation_points CHECK
(
    inviter_points >
    0
    AND
    invitee_points >
    0
),
    CONSTRAINT chk_hub_invitation_status CHECK
(
    status
    IN
(
    'PAID',
    'PENDING',
    'REJECTED',
    'DISABLED'
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_unicode_ci;

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'invitation.enabled',
       'true',
       '是否对新的邀请注册发放奖励' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'invitation.enabled');

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'invitation.inviter-points',
       '20',
       '邀请人每次成功邀请奖励' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'invitation.inviter-points');

INSERT INTO hub_settings (config_key, config_value, description)
SELECT 'invitation.invitee-points',
       '20',
       '新用户通过邀请注册的额外奖励' WHERE NOT EXISTS (SELECT 1 FROM hub_settings WHERE config_key = 'invitation.invitee-points');

-- 场景模板库：执行前备份；仅新增表和缺失预置内容，不覆盖管理员已修改的数据。
CREATE TABLE IF NOT EXISTS hub_template_term
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    PRIMARY
    KEY,
    kind
    VARCHAR
(
    16
) NOT NULL,
    name VARCHAR
(
    30
) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_template_term_name
(
    kind,
    name
),
    CONSTRAINT chk_template_term_kind CHECK
(
    kind
    IN
(
    'CATEGORY',
    'TAG'
))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_creation_template
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    PRIMARY
    KEY,
    seed_key
    VARCHAR
(
    50
) DEFAULT NULL,
    title VARCHAR
(
    80
) NOT NULL,
    description VARCHAR
(
    300
) NOT NULL DEFAULT '',
    category_id BIGINT NOT NULL,
    prompt_pattern TEXT NOT NULL,
    fields_json TEXT NOT NULL,
    cover_url VARCHAR
(
    2048
) DEFAULT NULL COMMENT '外部封面图片URL',
    example_id VARCHAR
(
    36
) DEFAULT NULL,
    source_share_id VARCHAR
(
    36
) DEFAULT NULL,
    source_author VARCHAR
(
    32
) DEFAULT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_template_seed
(
    seed_key
),
    KEY idx_template_browse
(
    deleted,
    enabled,
    category_id,
    sort_order,
    id
),
    CONSTRAINT fk_template_category FOREIGN KEY
(
    category_id
) REFERENCES hub_template_term
(
    id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_template_tag
(
    id
    BIGINT
    NOT
    NULL
    AUTO_INCREMENT
    PRIMARY
    KEY,
    template_id
    BIGINT
    NOT
    NULL,
    term_id
    BIGINT
    NOT
    NULL,
    create_time
    datetime
    DEFAULT
    CURRENT_TIMESTAMP,
    update_time
    datetime
    DEFAULT
    CURRENT_TIMESTAMP
    ON
    UPDATE
    CURRENT_TIMESTAMP,
    deleted
    TINYINT
    NOT
    NULL
    DEFAULT
    0,
    UNIQUE
    KEY
    uk_template_tag
(
    template_id,
    term_id
),
    KEY idx_template_tag_filter
(
    term_id,
    deleted,
    template_id
),
    CONSTRAINT fk_template_tag_template FOREIGN KEY
(
    template_id
) REFERENCES hub_creation_template
(
    id
),
    CONSTRAINT fk_template_tag_term FOREIGN KEY
(
    term_id
) REFERENCES hub_template_term
(
    id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hub_template_example
(
    id
    VARCHAR
(
    36
) NOT NULL PRIMARY KEY,
    template_id BIGINT NOT NULL,
    url VARCHAR
(
    2048
) NOT NULL,
    storage_info TEXT NOT NULL,
    create_time datetime DEFAULT CURRENT_TIMESTAMP,
    update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    KEY idx_template_example_cleanup
(
    template_id,
    deleted
),
    CONSTRAINT fk_template_example_template FOREIGN KEY
(
    template_id
) REFERENCES hub_creation_template
(
    id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_unicode_ci;

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'CATEGORY',
       '个人创作',
       0 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='CATEGORY' AND name='个人创作');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'CATEGORY',
       '内容创作',
       10 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='CATEGORY' AND name='内容创作');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'CATEGORY',
       '电商创作',
       20 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='CATEGORY' AND name='电商创作');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '头像',
       0 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='头像');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '插画',
       10 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='插画');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '壁纸',
       20 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='壁纸');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '风景',
       30 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='风景');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '节日',
       40 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='节日');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '祝福',
       50 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='祝福');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '封面',
       60 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='封面');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '小红书',
       70 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='小红书');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '配图',
       80 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='配图');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '视频',
       90 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='视频');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '商品主图',
       100 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='商品主图');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '极简',
       110 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='极简');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '商品场景',
       120 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='商品场景');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '生活方式',
       130 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='生活方式');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '海报',
       140 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='海报');

INSERT INTO hub_template_term(kind, name, sort_order)
SELECT 'TAG',
       '促销',
       150 WHERE NOT EXISTS (SELECT 1 FROM hub_template_term WHERE kind='TAG' AND name='促销');

INSERT INTO hub_creation_template(seed_key, title, description, category_id, prompt_pattern, fields_json, enabled,
                                  sort_order)
SELECT 'avatar',
       '个性头像',
       '用喜欢的角色、风格和颜色，设计一张属于你的头像。',
       c.id,
       '创作一张适合社交账户展示的个性头像，主体为{{subject}}，视觉语言遵循{{style}}，以{{color}}组织主色、辅助色与少量强调色。围绕主体最有辨识度的轮廓和特征展开设计：人物或动物突出自然的神态与姿态，物件突出清晰的外形与结构，不额外添加无关角色。按照实际画布安排构图，将关键特征集中在中央安全区域，四周留出呼吸空间，圆形裁切后仍完整、易辨认。背景采用同风格的简洁色块、柔和渐变或少量呼应主体的元素，通过明暗与色彩对比分离主体，避免繁杂纹理抢占注意力。光线、笔触与材质符合所选风格，重点刻画眼神、边缘或关键装饰，次要细节适当概括，缩小后仍有鲜明识别度。若用户上传参考图，保留与主体相关的外观特征，不复制其中的文字或背景杂物。画面不出现文字、标志、水印、边框或头像界面。',
       '[{"key":"subject","label":"头像主体","example":"戴圆框眼镜的橘猫","required":true},{"key":"style","label":"画面风格","example":"温柔的手绘插画","required":true},{"key":"color","label":"配色","example":"奶油白与浅橘色","required":true}]',
       1,
       10
FROM hub_template_term c
WHERE c.kind = 'CATEGORY'
  AND c.name = '个人创作'
  AND c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_creation_template WHERE seed_key = 'avatar');
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'avatar'
  AND c.kind = 'TAG'
  AND c.name = '头像'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'avatar'
  AND c.kind = 'TAG'
  AND c.name = '插画'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);

INSERT INTO hub_creation_template(seed_key, title, description, category_id, prompt_pattern, fields_json, enabled,
                                  sort_order)
SELECT 'wallpaper',
       '手机 / 电脑壁纸',
       '把喜欢的风景变成日常背景，给图标与时间留出空间。',
       c.id,
       '为{{device}}创作一张可长期使用的壁纸，画面内容为{{scene}}，整体氛围与色彩遵循{{mood}}。根据实际画布比例组织景别与视觉动线，不拉伸主体，不将横向构图机械裁成竖向。建立明确的视觉焦点，通过前景、中景与远景的疏密和明暗变化形成空间层次；如果内容偏抽象，则用形态大小、重叠关系与色彩过渡形成层次，避免强行加入风景元素。细节集中在视觉焦点附近，其余区域保持平缓的纹理和自然过渡，让画面有可停留的细节，也有安静的留白。结合使用设备，为时间显示、桌面图标等常用区域预留低对比、少杂物的空间，重要主体远离边缘与可能被遮挡的位置。光线方向统一，材质表现服务于场景气氛，避免过曝亮斑、刺眼色块和过度锐化。若用户上传参考图，延续相关场景特征与氛围，重新安排适合壁纸的构图。输出完整画面，不绘制手机、显示器、时间数字、图标、文字、边框或水印。',
       '[{"key":"scene","label":"画面内容","example":"清晨薄雾中的山间湖泊","required":true},{"key":"mood","label":"氛围与色彩","example":"宁静、低饱和蓝绿色","required":true},{"key":"device","label":"使用设备","example":"手机","required":true}]',
       1,
       20
FROM hub_template_term c
WHERE c.kind = 'CATEGORY'
  AND c.name = '个人创作'
  AND c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_creation_template WHERE seed_key = 'wallpaper');
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'wallpaper'
  AND c.kind = 'TAG'
  AND c.name = '壁纸'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'wallpaper'
  AND c.kind = 'TAG'
  AND c.name = '风景'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);

INSERT INTO hub_creation_template(seed_key, title, description, category_id, prompt_pattern, fields_json, enabled,
                                  sort_order)
SELECT 'greeting',
       '节日祝福图',
       '将节日、收件人和一句祝福组合成有心意的画面。',
       c.id,
       '为{{festival}}设计一张主题完整的祝福图，画面采用{{style}}。唯一需要呈现的文字为“{{greeting}}”，保持原文字词与顺序，不自行添加标题、日期、署名、英文或其他文案。先确定祝福文字的位置与阅读顺序，再围绕节日选择少量有代表性的元素形成主景与陪衬，避免将所有节庆符号堆满画面。根据实际画布比例安排文字区与画面主体，让主景、文字和留白形成清楚的主次关系，四周保留安全边距。文字置于干净的背景上，字形完整、笔画清楚、字距舒适，与背景有足够对比；必要时依语义自然换行，不用装饰遮挡文字，不把汉字拉伸或拆成零散部件。配色从节日情绪和所选风格中提炼，控制主色与点缀色的比例，以一致的光线、材质或笔触串联装饰细节，营造温暖而有仪式感的氛围。若用户上传参考图，仅借鉴相关视觉元素，不复制其中未被指定的文字。避免水印、商业标志、二维码与无关边框。',
       '[{"key":"festival","label":"节日","example":"中秋节","required":true},{"key":"greeting","label":"祝福文字","example":"愿你月圆人团圆","required":true},{"key":"style","label":"画面风格","example":"温暖的国风插画","required":true}]',
       1,
       30
FROM hub_template_term c
WHERE c.kind = 'CATEGORY'
  AND c.name = '个人创作'
  AND c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_creation_template WHERE seed_key = 'greeting');
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'greeting'
  AND c.kind = 'TAG'
  AND c.name = '节日'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'greeting'
  AND c.kind = 'TAG'
  AND c.name = '祝福'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);

INSERT INTO hub_creation_template(seed_key, title, description, category_id, prompt_pattern, fields_json, enabled,
                                  sort_order)
SELECT 'xiaohongshu',
       '小红书封面',
       '突出一条主题，让封面的标题与画面一起表达重点。',
       c.id,
       '为内容主题“{{topic}}”设计一张具有编辑感的小红书封面，视觉风格与配色遵循：{{style}}。从主题中选择一个最有辨识度的主体，以能说明内容的动作、物件关系或局部细节形成视觉记忆点，避免把多个主题元素平均铺满画面。主体占据主要视觉区域，少量相关道具交代情境，背景保持疏密节奏；根据所选风格处理明暗、材质或色块层次，用少量强调色引导视线。封面唯一文字为“{{headline}}”，逐字准确呈现，不改写、不补充副标题。按语义自然分行，突出标题中的核心词，统一字形与对齐关系，文字与背景形成清楚的明度对比。按实际画布安排标题与主体的位置，标题放在完整留白区，不遮挡面部或关键细节，四周保留裁切余量。最终在手机信息流的小尺寸预览中，仍能一眼读到标题并认出主题。不要添加平台界面、互动数字、虚构成绩、品牌标识或水印。',
       '[{"key":"topic","label":"内容主题","example":"周末十分钟健康早餐","required":true},{"key":"headline","label":"封面标题","example":"好吃又省时的早餐","required":true},{"key":"style","label":"风格与配色","example":"明亮自然的生活方式摄影，暖白色","required":true}]',
       1,
       40
FROM hub_template_term c
WHERE c.kind = 'CATEGORY'
  AND c.name = '内容创作'
  AND c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_creation_template WHERE seed_key = 'xiaohongshu');
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'xiaohongshu'
  AND c.kind = 'TAG'
  AND c.name = '封面'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'xiaohongshu'
  AND c.kind = 'TAG'
  AND c.name = '小红书'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);

INSERT INTO hub_creation_template(seed_key, title, description, category_id, prompt_pattern, fields_json, enabled,
                                  sort_order)
SELECT 'article',
       '公众号配图',
       '根据文章主题生成配图，用画面解释你的观点。',
       c.id,
       '为主题“{{topic}}”创作一张嵌入文章正文的叙事配图，核心表达是“{{idea}}”，整体采用{{style}}。把抽象观点转化为一个可看懂的具体场景或视觉隐喻，通过主体之间的距离、大小、方向或动作关系传达含义，避免用堆砌图标代替叙事。画面只设置一个主要视觉焦点，安排少量与观点直接相关的辅助元素，让视线从环境线索自然走向核心主体。根据实际画布组织构图，疏密有度，保留能让正文阅读节奏停顿的安静区域，关键内容不要紧贴边缘。用符合既定风格的明暗、色块或空间层次区分主体与环境，以有限主色建立统一感，在关键关系处给出适度对比。若涉及人物，以姿态和互动表达情绪，不依赖夸张表情；若涉及概念，保持隐喻简洁且符合常识。让画面脱离正文时也能读出基本含义，放回正文时服务于观点。画面不添加标题、正文段落、虚构数字图表、品牌标识或水印。',
       '[{"key":"topic","label":"文章主题","example":"城市里的慢生活","required":true},{"key":"idea","label":"想表达的内容","example":"在忙碌中保留属于自己的时间","required":true},{"key":"style","label":"视觉风格","example":"克制的扁平插画","required":true}]',
       1,
       50
FROM hub_template_term c
WHERE c.kind = 'CATEGORY'
  AND c.name = '内容创作'
  AND c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_creation_template WHERE seed_key = 'article');
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'article'
  AND c.kind = 'TAG'
  AND c.name = '配图'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'article'
  AND c.kind = 'TAG'
  AND c.name = '插画'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);

INSERT INTO hub_creation_template(seed_key, title, description, category_id, prompt_pattern, fields_json, enabled,
                                  sort_order)
SELECT 'video',
       '视频封面',
       '提炼视频主题，用一个视觉重点吸引观看。',
       c.id,
       '为视频主题“{{topic}}”设计一张有明确故事线索的封面，画面主体是{{subject}}。选取最能代表视频内容的一个瞬间，通过动作方向、物件关系或环境变化营造观看兴趣，让观众能判断内容，避免无关夸张元素。采用与主体描述和主题一致的视觉表现，将主体轮廓与背景清楚分离，利用前后层次、明暗或冷暖差异建立焦点；保留少量能解释场景的环境细节，避免密集道具和碎片拼贴。唯一标题严格使用“{{headline}}”，逐字准确呈现，按语义分为易读的短行，字形清晰、笔画完整，行距与对齐统一。标题安放在相对平静的区域，与主体构成稳定的视觉平衡，不遮住人物面部、关键动作或物件。按实际画布调整构图，为四周及底部可能出现的平台标记留出安全余量。缩小为列表缩略图后，仍能先认出主体，再读清标题。不要添加虚构的播放量、排名、惊叹文案、平台按钮、标识或水印。',
       '[{"key":"topic","label":"视频主题","example":"第一次独自露营","required":true},{"key":"headline","label":"封面标题","example":"一个人的山野周末","required":true},{"key":"subject","label":"画面主体","example":"山坡上的帐篷与背包","required":true}]',
       1,
       60
FROM hub_template_term c
WHERE c.kind = 'CATEGORY'
  AND c.name = '内容创作'
  AND c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_creation_template WHERE seed_key = 'video');
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'video'
  AND c.kind = 'TAG'
  AND c.name = '封面'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'video'
  AND c.kind = 'TAG'
  AND c.name = '视频'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);

INSERT INTO hub_creation_template(seed_key, title, description, category_id, prompt_pattern, fields_json, enabled,
                                  sort_order)
SELECT 'product',
       '商品主图',
       '突出商品外观与材质，适合简洁清晰的商品展示。',
       c.id,
       '为{{product}}制作一张以商品本身为主角的主图，重点展示{{feature}}，背景与光线遵循{{background}}。优先选择能清楚交代外形和关键部件的观察角度，主体占据主要画面，完整保留边缘、开口、把手或包装轮廓，四周留出裁切余量，避免广角畸变。若用户上传商品参考图，以其外形、颜色、比例、结构和已有标签为依据，不擅自改款或增加配件；没有参考图时按描述制作概念展示，不虚构品牌与标签文字。用符合材质的光影表现展示重点：玻璃保留自然透射和边缘反光，金属保持受控高光，织物呈现真实纹理，哑光表面体现细腻明暗；仅应用与商品相符的表现。保持主光方向、接触阴影与反射一致，亮部不过曝，暗部仍有结构，不用过度锐化或塑料质感掩盖细节。背景简洁，少量辅助元素不得遮挡商品或让用户误认为属于售卖套装。按实际画布组织构图，不添加促销文案、参数、认证标记、边框或水印。',
       '[{"key":"product","label":"商品名称与外观","example":"透明玻璃瓶装的柑橘香氛","required":true},{"key":"feature","label":"展示重点","example":"透明质感与瓶内淡黄色液体","required":true},{"key":"background","label":"背景与光线","example":"暖白背景，柔和侧光","required":true}]',
       1,
       70
FROM hub_template_term c
WHERE c.kind = 'CATEGORY'
  AND c.name = '电商创作'
  AND c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_creation_template WHERE seed_key = 'product');
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'product'
  AND c.kind = 'TAG'
  AND c.name = '商品主图'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'product'
  AND c.kind = 'TAG'
  AND c.name = '极简'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);

INSERT INTO hub_creation_template(seed_key, title, description, category_id, prompt_pattern, fields_json, enabled,
                                  sort_order)
SELECT 'product-scene',
       '商品场景图',
       '让商品出现在合适的使用环境中，展示生活方式。',
       c.id,
       '围绕{{product}}创作一张有生活气息的商品场景图，使用环境为{{scene}}，氛围遵循{{mood}}。选择一个符合实际使用方式的摆放位置，让商品成为清楚的视觉焦点，环境线索解释它为何出现在这里。用少量与使用场景相关的物件建立前景、中景与背景，主次有别，避免把道具摆成堆满画面的陈列。根据商品真实大小安排与桌面、家具或人物的比例，透视方向一致，底部有可信的接触阴影，反射与环境光相吻合，不出现悬浮、穿模或不合理支撑。若用户上传参考图，保留商品外形、颜色、结构和已有标签；没有参考图时按描述创作概念商品，不虚构品牌或功能。用符合氛围的光源方向与柔硬程度表现材质，让环境的色彩适度呼应商品，同时保持主体轮廓与关键细节清楚，不用过强虚化掩盖商品。根据实际画布调整视觉动线与留白，关键部位远离裁切边缘。画面不新增广告文案、功效展示、价格标签、标志或水印。',
       '[{"key":"product","label":"商品名称与外观","example":"米白色陶瓷咖啡杯","required":true},{"key":"scene","label":"使用场景","example":"阳光照进窗边的木质书桌","required":true},{"key":"mood","label":"画面氛围","example":"安静自然的周末早晨","required":true}]',
       1,
       80
FROM hub_template_term c
WHERE c.kind = 'CATEGORY'
  AND c.name = '电商创作'
  AND c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_creation_template WHERE seed_key = 'product-scene');
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'product-scene'
  AND c.kind = 'TAG'
  AND c.name = '商品场景'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'product-scene'
  AND c.kind = 'TAG'
  AND c.name = '生活方式'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);

INSERT INTO hub_creation_template(seed_key, title, description, category_id, prompt_pattern, fields_json, enabled,
                                  sort_order)
SELECT 'promotion',
       '促销海报',
       '围绕商品和活动信息排版，清楚展示真实优惠。',
       c.id,
       '为{{product}}设计一张有清晰购买信息层级的促销海报，视觉风格与配色遵循{{style}}。唯一新增文案为主标题“{{headline}}”和活动信息“{{offer}}”，逐字保持原文，不改写优惠条件，不补充未提供的价格、折扣、期限、销量、功效或行动口号。先建立商品、主标题、活动信息三个层级：商品有明确的视觉焦点，主标题在第一眼可读，活动信息完整清楚且不被装饰弱化。根据实际画布安排一个主要展示区和相对安静的文字区，统一对齐线与间距，按语义自然断行，保留四周安全边距；文字与背景对比充分，不遮挡商品关键结构。用主色、辅助色和少量强调色组织画面，装饰元素呼应商品与活动主题，不堆砌贴纸、爆炸框或无关光效。商品的材质、光线和接触阴影与环境一致，保持真实比例；若用户上传参考图，保留其外观、结构和已有标签，没有参考图时按描述作概念展示。不给商品增加虚构品牌、认证、二维码或水印。',
       '[{"key":"product","label":"商品","example":"春季花果茶礼盒","required":true},{"key":"headline","label":"主标题","example":"春日好茶","required":true},{"key":"offer","label":"活动信息","example":"第二件半价","required":true},{"key":"style","label":"风格与配色","example":"清新的浅绿色与米白色","required":true}]',
       1,
       90
FROM hub_template_term c
WHERE c.kind = 'CATEGORY'
  AND c.name = '电商创作'
  AND c.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_creation_template WHERE seed_key = 'promotion');
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'promotion'
  AND c.kind = 'TAG'
  AND c.name = '海报'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
INSERT INTO hub_template_tag(template_id, term_id)
SELECT t.id, c.id
FROM hub_creation_template t,
     hub_template_term c
WHERE t.seed_key = 'promotion'
  AND c.kind = 'TAG'
  AND c.name = '促销'
  AND c.deleted = 0
  AND t.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM hub_template_tag WHERE template_id = t.id AND term_id = c.id);
