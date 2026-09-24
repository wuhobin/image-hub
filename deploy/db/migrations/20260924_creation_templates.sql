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
       '设计一张适合社交账户使用的头像。主体是{{subject}}，采用{{style}}风格，配色为{{color}}。主体清晰居中，背景简洁，保留适合圆形裁切的安全区域，细节精致，不添加文字或水印。',
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
       '为{{device}}创作壁纸，内容为{{scene}}，氛围与色彩是{{mood}}。根据画布构图，在显示时间和图标的区域保留干净留白，视觉层次丰富且柔和，不添加文字、边框或水印。',
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
       '创作{{festival}}祝福图，使用{{style}}风格，围绕节日代表性元素构图。画面仅呈现这句祝福文字：“{{greeting}}”。让文字清晰、层级分明，保持充足留白，避免其他无关文案或水印。',
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
       '设计小红书内容封面，主题为{{topic}}。封面标题必须是“{{headline}}”，采用{{style}}。标题醒目易读，主体突出，画面简洁，文字与主体不要相互遮挡。不添加虚构数据、无关文字或水印。',
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
       '为主题“{{topic}}”的文章创作配图，重点表达“{{idea}}”。采用{{style}}，视觉叙事清晰，元素精炼，配色协调，适合嵌入正文。不生成正文段落、品牌标识或水印。',
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
       '为视频“{{topic}}”设计封面，画面主体是{{subject}}。以“{{headline}}”作为唯一标题，保证小尺寸观看时依然清晰。构图有明确焦点，主体与背景层次分明，标题放在留白区域，不添加其他文字或水印。',
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
       '制作商品主图。商品是{{product}}，重点展示{{feature}}，背景与光线为{{background}}。如提供参考图，以图中商品为依据，尽量保持外形、颜色、标签和结构；未提供参考图时按描述创作概念展示。商品主体清晰完整，细节自然，避免虚构品牌、促销文字或水印。',
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
       '创作商品场景展示图，商品为{{product}}，放置在{{scene}}中，呈现{{mood}}。如提供商品参考图，尽量保持其外形、颜色和结构；否则按描述创作概念商品。比例自然，商品为画面焦点，光线和阴影与环境一致，不添加文字或水印。',
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
       '为{{product}}制作促销海报，风格与配色为{{style}}。主标题严格使用“{{headline}}”，活动信息严格使用“{{offer}}”，不要增添未提供的价格、折扣、销量或功效。若有参考图，尽量保留商品外观；否则按描述创作概念展示。商品、标题与活动信息层级清楚，排版整洁，不添加水印。',
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
