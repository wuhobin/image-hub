// 浏览器回归使用虚构接口；任何生成请求都直接失败，不访问真实模型或存储。
if (!sessionStorage.getItem('template-qa-started')) {
    localStorage.removeItem('imagehub.token');
    localStorage.removeItem('imagehub.admin.token');
    sessionStorage.setItem('template-qa-started', '1');
}
window.calls = JSON.parse(sessionStorage.getItem('template-qa-calls') || '[]');
window.qaTerms = JSON.parse(sessionStorage.getItem('template-qa-terms') || 'null') || [{
    "id": 1,
    "kind": "CATEGORY",
    "name": "个人创作",
    "sortOrder": 0
}, {"id": 2, "kind": "CATEGORY", "name": "内容创作", "sortOrder": 10}, {
    "id": 3,
    "kind": "CATEGORY",
    "name": "电商创作",
    "sortOrder": 20
}, {"id": 4, "kind": "TAG", "name": "极简", "sortOrder": 0}, {"id": 5, "kind": "TAG", "name": "插画", "sortOrder": 10}];
window.qaTemplates = JSON.parse(sessionStorage.getItem('template-qa-items') || 'null') || [{
    "id": 1,
    "title": "个性头像",
    "description": "用喜欢的角色、风格和颜色，设计一张属于你的头像。",
    "category": {"id": 1, "kind": "CATEGORY", "name": "个人创作", "sortOrder": 0},
    "tags": [{"id": 4, "kind": "TAG", "name": "极简", "sortOrder": 0}],
    "fields": [{
        "key": "subject",
        "label": "头像主体",
        "example": "戴圆框眼镜的橘猫",
        "required": true
    }, {"key": "style", "label": "画面风格", "example": "温柔的手绘插画", "required": true}, {
        "key": "color",
        "label": "配色",
        "example": "奶油白与浅橘色",
        "required": true
    }],
    "promptPattern": "创作一张适合社交账户展示的个性头像，主体为{{subject}}，视觉语言遵循{{style}}，以{{color}}组织主色、辅助色与少量强调色。围绕主体最有辨识度的轮廓和特征展开设计：人物或动物突出自然的神态与姿态，物件突出清晰的外形与结构，不额外添加无关角色。按照实际画布安排构图，将关键特征集中在中央安全区域，四周留出呼吸空间，圆形裁切后仍完整、易辨认。背景采用同风格的简洁色块、柔和渐变或少量呼应主体的元素，通过明暗与色彩对比分离主体，避免繁杂纹理抢占注意力。光线、笔触与材质符合所选风格，重点刻画眼神、边缘或关键装饰，次要细节适当概括，缩小后仍有鲜明识别度。若用户上传参考图，保留与主体相关的外观特征，不复制其中的文字或背景杂物。画面不出现文字、标志、水印、边框或头像界面。",
    "exampleUrl": null,
    "sourceShareId": null,
    "sourceAuthor": null,
    "enabled": true,
    "sortOrder": 10,
    "updateTime": "2026-09-24 12:00:00"
}, {
    "id": 2,
    "title": "手机 / 电脑壁纸",
    "description": "把喜欢的风景变成日常背景，给图标与时间留出空间。",
    "category": {"id": 1, "kind": "CATEGORY", "name": "个人创作", "sortOrder": 0},
    "tags": [{"id": 5, "kind": "TAG", "name": "插画", "sortOrder": 10}],
    "fields": [{
        "key": "scene",
        "label": "画面内容",
        "example": "清晨薄雾中的山间湖泊",
        "required": true
    }, {"key": "mood", "label": "氛围与色彩", "example": "宁静、低饱和蓝绿色", "required": true}, {
        "key": "device",
        "label": "使用设备",
        "example": "手机",
        "required": true
    }],
    "promptPattern": "为{{device}}创作一张可长期使用的壁纸，画面内容为{{scene}}，整体氛围与色彩遵循{{mood}}。根据实际画布比例组织景别与视觉动线，不拉伸主体，不将横向构图机械裁成竖向。建立明确的视觉焦点，通过前景、中景与远景的疏密和明暗变化形成空间层次；如果内容偏抽象，则用形态大小、重叠关系与色彩过渡形成层次，避免强行加入风景元素。细节集中在视觉焦点附近，其余区域保持平缓的纹理和自然过渡，让画面有可停留的细节，也有安静的留白。结合使用设备，为时间显示、桌面图标等常用区域预留低对比、少杂物的空间，重要主体远离边缘与可能被遮挡的位置。光线方向统一，材质表现服务于场景气氛，避免过曝亮斑、刺眼色块和过度锐化。若用户上传参考图，延续相关场景特征与氛围，重新安排适合壁纸的构图。输出完整画面，不绘制手机、显示器、时间数字、图标、文字、边框或水印。",
    "exampleUrl": null,
    "sourceShareId": null,
    "sourceAuthor": null,
    "enabled": true,
    "sortOrder": 20,
    "updateTime": "2026-09-24 12:00:00"
}, {
    "id": 3,
    "title": "节日祝福图",
    "description": "将节日、收件人和一句祝福组合成有心意的画面。",
    "category": {"id": 1, "kind": "CATEGORY", "name": "个人创作", "sortOrder": 0},
    "tags": [{"id": 4, "kind": "TAG", "name": "极简", "sortOrder": 0}],
    "fields": [{"key": "festival", "label": "节日", "example": "中秋节", "required": true}, {
        "key": "greeting",
        "label": "祝福文字",
        "example": "愿你月圆人团圆",
        "required": true
    }, {"key": "style", "label": "画面风格", "example": "温暖的国风插画", "required": true}],
    "promptPattern": "为{{festival}}设计一张主题完整的祝福图，画面采用{{style}}。唯一需要呈现的文字为“{{greeting}}”，保持原文字词与顺序，不自行添加标题、日期、署名、英文或其他文案。先确定祝福文字的位置与阅读顺序，再围绕节日选择少量有代表性的元素形成主景与陪衬，避免将所有节庆符号堆满画面。根据实际画布比例安排文字区与画面主体，让主景、文字和留白形成清楚的主次关系，四周保留安全边距。文字置于干净的背景上，字形完整、笔画清楚、字距舒适，与背景有足够对比；必要时依语义自然换行，不用装饰遮挡文字，不把汉字拉伸或拆成零散部件。配色从节日情绪和所选风格中提炼，控制主色与点缀色的比例，以一致的光线、材质或笔触串联装饰细节，营造温暖而有仪式感的氛围。若用户上传参考图，仅借鉴相关视觉元素，不复制其中未被指定的文字。避免水印、商业标志、二维码与无关边框。",
    "exampleUrl": null,
    "sourceShareId": null,
    "sourceAuthor": null,
    "enabled": true,
    "sortOrder": 30,
    "updateTime": "2026-09-24 12:00:00"
}, {
    "id": 4,
    "title": "小红书封面",
    "description": "突出一条主题，让封面的标题与画面一起表达重点。",
    "category": {"id": 2, "kind": "CATEGORY", "name": "内容创作", "sortOrder": 10},
    "tags": [{"id": 5, "kind": "TAG", "name": "插画", "sortOrder": 10}],
    "fields": [{
        "key": "topic",
        "label": "内容主题",
        "example": "周末十分钟健康早餐",
        "required": true
    }, {"key": "headline", "label": "封面标题", "example": "好吃又省时的早餐", "required": true}, {
        "key": "style",
        "label": "风格与配色",
        "example": "明亮自然的生活方式摄影，暖白色",
        "required": true
    }],
    "promptPattern": "为内容主题“{{topic}}”设计一张具有编辑感的小红书封面，视觉风格与配色遵循：{{style}}。从主题中选择一个最有辨识度的主体，以能说明内容的动作、物件关系或局部细节形成视觉记忆点，避免把多个主题元素平均铺满画面。主体占据主要视觉区域，少量相关道具交代情境，背景保持疏密节奏；根据所选风格处理明暗、材质或色块层次，用少量强调色引导视线。封面唯一文字为“{{headline}}”，逐字准确呈现，不改写、不补充副标题。按语义自然分行，突出标题中的核心词，统一字形与对齐关系，文字与背景形成清楚的明度对比。按实际画布安排标题与主体的位置，标题放在完整留白区，不遮挡面部或关键细节，四周保留裁切余量。最终在手机信息流的小尺寸预览中，仍能一眼读到标题并认出主题。不要添加平台界面、互动数字、虚构成绩、品牌标识或水印。",
    "exampleUrl": null,
    "sourceShareId": null,
    "sourceAuthor": null,
    "enabled": true,
    "sortOrder": 40,
    "updateTime": "2026-09-24 12:00:00"
}, {
    "id": 5,
    "title": "公众号配图",
    "description": "根据文章主题生成配图，用画面解释你的观点。",
    "category": {"id": 2, "kind": "CATEGORY", "name": "内容创作", "sortOrder": 10},
    "tags": [{"id": 4, "kind": "TAG", "name": "极简", "sortOrder": 0}],
    "fields": [{"key": "topic", "label": "文章主题", "example": "城市里的慢生活", "required": true}, {
        "key": "idea",
        "label": "想表达的内容",
        "example": "在忙碌中保留属于自己的时间",
        "required": true
    }, {"key": "style", "label": "视觉风格", "example": "克制的扁平插画", "required": true}],
    "promptPattern": "为主题“{{topic}}”创作一张嵌入文章正文的叙事配图，核心表达是“{{idea}}”，整体采用{{style}}。把抽象观点转化为一个可看懂的具体场景或视觉隐喻，通过主体之间的距离、大小、方向或动作关系传达含义，避免用堆砌图标代替叙事。画面只设置一个主要视觉焦点，安排少量与观点直接相关的辅助元素，让视线从环境线索自然走向核心主体。根据实际画布组织构图，疏密有度，保留能让正文阅读节奏停顿的安静区域，关键内容不要紧贴边缘。用符合既定风格的明暗、色块或空间层次区分主体与环境，以有限主色建立统一感，在关键关系处给出适度对比。若涉及人物，以姿态和互动表达情绪，不依赖夸张表情；若涉及概念，保持隐喻简洁且符合常识。让画面脱离正文时也能读出基本含义，放回正文时服务于观点。画面不添加标题、正文段落、虚构数字图表、品牌标识或水印。",
    "exampleUrl": null,
    "sourceShareId": null,
    "sourceAuthor": null,
    "enabled": true,
    "sortOrder": 50,
    "updateTime": "2026-09-24 12:00:00"
}, {
    "id": 6,
    "title": "视频封面",
    "description": "提炼视频主题，用一个视觉重点吸引观看。",
    "category": {"id": 2, "kind": "CATEGORY", "name": "内容创作", "sortOrder": 10},
    "tags": [{"id": 5, "kind": "TAG", "name": "插画", "sortOrder": 10}],
    "fields": [{"key": "topic", "label": "视频主题", "example": "第一次独自露营", "required": true}, {
        "key": "headline",
        "label": "封面标题",
        "example": "一个人的山野周末",
        "required": true
    }, {"key": "subject", "label": "画面主体", "example": "山坡上的帐篷与背包", "required": true}],
    "promptPattern": "为视频主题“{{topic}}”设计一张有明确故事线索的封面，画面主体是{{subject}}。选取最能代表视频内容的一个瞬间，通过动作方向、物件关系或环境变化营造观看兴趣，让观众能判断内容，避免无关夸张元素。采用与主体描述和主题一致的视觉表现，将主体轮廓与背景清楚分离，利用前后层次、明暗或冷暖差异建立焦点；保留少量能解释场景的环境细节，避免密集道具和碎片拼贴。唯一标题严格使用“{{headline}}”，逐字准确呈现，按语义分为易读的短行，字形清晰、笔画完整，行距与对齐统一。标题安放在相对平静的区域，与主体构成稳定的视觉平衡，不遮住人物面部、关键动作或物件。按实际画布调整构图，为四周及底部可能出现的平台标记留出安全余量。缩小为列表缩略图后，仍能先认出主体，再读清标题。不要添加虚构的播放量、排名、惊叹文案、平台按钮、标识或水印。",
    "exampleUrl": null,
    "sourceShareId": null,
    "sourceAuthor": null,
    "enabled": true,
    "sortOrder": 60,
    "updateTime": "2026-09-24 12:00:00"
}, {
    "id": 7,
    "title": "商品主图",
    "description": "突出商品外观与材质，适合简洁清晰的商品展示。",
    "category": {"id": 3, "kind": "CATEGORY", "name": "电商创作", "sortOrder": 20},
    "tags": [{"id": 4, "kind": "TAG", "name": "极简", "sortOrder": 0}],
    "fields": [{
        "key": "product",
        "label": "商品名称与外观",
        "example": "透明玻璃瓶装的柑橘香氛",
        "required": true
    }, {
        "key": "feature",
        "label": "展示重点",
        "example": "透明质感与瓶内淡黄色液体",
        "required": true
    }, {"key": "background", "label": "背景与光线", "example": "暖白背景，柔和侧光", "required": true}],
    "promptPattern": "为{{product}}制作一张以商品本身为主角的主图，重点展示{{feature}}，背景与光线遵循{{background}}。优先选择能清楚交代外形和关键部件的观察角度，主体占据主要画面，完整保留边缘、开口、把手或包装轮廓，四周留出裁切余量，避免广角畸变。若用户上传商品参考图，以其外形、颜色、比例、结构和已有标签为依据，不擅自改款或增加配件；没有参考图时按描述制作概念展示，不虚构品牌与标签文字。用符合材质的光影表现展示重点：玻璃保留自然透射和边缘反光，金属保持受控高光，织物呈现真实纹理，哑光表面体现细腻明暗；仅应用与商品相符的表现。保持主光方向、接触阴影与反射一致，亮部不过曝，暗部仍有结构，不用过度锐化或塑料质感掩盖细节。背景简洁，少量辅助元素不得遮挡商品或让用户误认为属于售卖套装。按实际画布组织构图，不添加促销文案、参数、认证标记、边框或水印。",
    "exampleUrl": null,
    "sourceShareId": null,
    "sourceAuthor": null,
    "enabled": true,
    "sortOrder": 70,
    "updateTime": "2026-09-24 12:00:00"
}, {
    "id": 8,
    "title": "商品场景图",
    "description": "让商品出现在合适的使用环境中，展示生活方式。",
    "category": {"id": 3, "kind": "CATEGORY", "name": "电商创作", "sortOrder": 20},
    "tags": [{"id": 5, "kind": "TAG", "name": "插画", "sortOrder": 10}],
    "fields": [{
        "key": "product",
        "label": "商品名称与外观",
        "example": "米白色陶瓷咖啡杯",
        "required": true
    }, {"key": "scene", "label": "使用场景", "example": "阳光照进窗边的木质书桌", "required": true}, {
        "key": "mood",
        "label": "画面氛围",
        "example": "安静自然的周末早晨",
        "required": true
    }],
    "promptPattern": "围绕{{product}}创作一张有生活气息的商品场景图，使用环境为{{scene}}，氛围遵循{{mood}}。选择一个符合实际使用方式的摆放位置，让商品成为清楚的视觉焦点，环境线索解释它为何出现在这里。用少量与使用场景相关的物件建立前景、中景与背景，主次有别，避免把道具摆成堆满画面的陈列。根据商品真实大小安排与桌面、家具或人物的比例，透视方向一致，底部有可信的接触阴影，反射与环境光相吻合，不出现悬浮、穿模或不合理支撑。若用户上传参考图，保留商品外形、颜色、结构和已有标签；没有参考图时按描述创作概念商品，不虚构品牌或功能。用符合氛围的光源方向与柔硬程度表现材质，让环境的色彩适度呼应商品，同时保持主体轮廓与关键细节清楚，不用过强虚化掩盖商品。根据实际画布调整视觉动线与留白，关键部位远离裁切边缘。画面不新增广告文案、功效展示、价格标签、标志或水印。",
    "exampleUrl": null,
    "sourceShareId": null,
    "sourceAuthor": null,
    "enabled": true,
    "sortOrder": 80,
    "updateTime": "2026-09-24 12:00:00"
}, {
    "id": 9,
    "title": "促销海报",
    "description": "围绕商品和活动信息排版，清楚展示真实优惠。",
    "category": {"id": 3, "kind": "CATEGORY", "name": "电商创作", "sortOrder": 20},
    "tags": [{"id": 4, "kind": "TAG", "name": "极简", "sortOrder": 0}],
    "fields": [{"key": "product", "label": "商品", "example": "春季花果茶礼盒", "required": true}, {
        "key": "headline",
        "label": "主标题",
        "example": "春日好茶",
        "required": true
    }, {"key": "offer", "label": "活动信息", "example": "第二件半价", "required": true}, {
        "key": "style",
        "label": "风格与配色",
        "example": "清新的浅绿色与米白色",
        "required": true
    }],
    "promptPattern": "为{{product}}设计一张有清晰购买信息层级的促销海报，视觉风格与配色遵循{{style}}。唯一新增文案为主标题“{{headline}}”和活动信息“{{offer}}”，逐字保持原文，不改写优惠条件，不补充未提供的价格、折扣、期限、销量、功效或行动口号。先建立商品、主标题、活动信息三个层级：商品有明确的视觉焦点，主标题在第一眼可读，活动信息完整清楚且不被装饰弱化。根据实际画布安排一个主要展示区和相对安静的文字区，统一对齐线与间距，按语义自然断行，保留四周安全边距；文字与背景对比充分，不遮挡商品关键结构。用主色、辅助色和少量强调色组织画面，装饰元素呼应商品与活动主题，不堆砌贴纸、爆炸框或无关光效。商品的材质、光线和接触阴影与环境一致，保持真实比例；若用户上传参考图，保留其外观、结构和已有标签，没有参考图时按描述作概念展示。不给商品增加虚构品牌、认证、二维码或水印。",
    "exampleUrl": null,
    "sourceShareId": null,
    "sourceAuthor": null,
    "enabled": true,
    "sortOrder": 90,
    "updateTime": "2026-09-24 12:00:00"
}];
window.qaPersist = () => {
    sessionStorage.setItem('template-qa-terms', JSON.stringify(window.qaTerms));
    sessionStorage.setItem('template-qa-items', JSON.stringify(window.qaTemplates));
};
const realFetch = window.fetch;
window.fetch = async (url, options = {}) => {
    const target = new URL(String(url), location.href), path = target.pathname, method = options.method || 'GET';
    if (!path.startsWith('/api/')) return realFetch(url, options);
    window.calls.push({path, method});
    sessionStorage.setItem('template-qa-calls', JSON.stringify(window.calls));
    let code = 200, data = null, message = '';
    const page = rows => {
        const current = Number(target.searchParams.get('page') || 1),
            size = Number(target.searchParams.get('pageSize') || 12);
        return {
            records: rows.slice((current - 1) * size, current * size),
            current,
            size,
            total: rows.length,
            pages: Math.ceil(rows.length / size)
        }
    };
    const input = typeof options.body === 'string' ? JSON.parse(options.body) : {};
    if (path === '/api/app/auth/login') data = {token: 'template-user', user: {id: '1', username: 'template-test'}};
    else if (path === '/api/app/auth/me') data = {id: '1', username: 'template-test'};
    else if (path === '/api/admin/auth/me') data = {id: '1', username: 'template-admin'};
    else if (path === '/api/app/images/quota') data = {total: 100, remaining: 100, used: 0, reserved: 0};
    else if (path === '/api/app/auth/invitation') data = {
        inviteCode: null,
        inviterName: null,
        rewards: {enabled: false, inviterPoints: 10, inviteePoints: 10}
    };
    else if (path === '/api/app/generations/models') data = [
        {
            id: 1,
            name: '标准模型',
            sizes: ['1024x1024'],
            defaultSize: '1024x1024',
            qualities: ['medium'],
            defaultQuality: 'medium',
            pointsCost: 1
        },
        {
            id: 2,
            name: '精细模型',
            sizes: ['1024x1024', '1536x1024'],
            defaultSize: '1024x1024',
            qualities: ['medium', 'high'],
            defaultQuality: 'medium',
            pointsCost: 3
        }];
    else if (path === '/api/app/generations/active') data = null;
    else if (path === '/api/app/generations') {
        if (method === 'POST') throw new Error('QA cannot generate images');
        data = page([]);
    } else if (path.endsWith('/templates/terms')) {
        if (method === 'POST') {
            data = {id: Math.max(...window.qaTerms.map(t => t.id)) + 1, ...input};
            window.qaTerms.push(data);
            window.qaPersist();
        } else data = window.qaTerms;
    } else if (/\/templates\/terms\/\d+$/.test(path)) {
        const id = Number(path.split('/').pop());
        if (method === 'PUT') {
            data = {id, ...input};
            window.qaTerms = window.qaTerms.map(t => t.id === id ? data : t);
        } else window.qaTerms = window.qaTerms.filter(t => t.id !== id);
        window.qaPersist();
    } else if (path.endsWith('/templates')) {
        if (method === 'POST') {
            data = {
                ...input,
                id: Math.max(...window.qaTemplates.map(t => t.id)) + 1,
                category: window.qaTerms.find(t => t.id === input.categoryId),
                tags: window.qaTerms.filter(t => input.tagIds.includes(t.id)),
                exampleUrl: null,
                sourceAuthor: null,
                sourceShareId: null,
                updateTime: '2026-09-24 12:00:00'
            };
            window.qaTemplates.push(data);
            window.qaPersist();
        } else {
            const q = target.searchParams, publicOnly = path.includes('/public/');
            data = page(window.qaTemplates.filter(t => (!publicOnly || t.enabled) && (!q.get('enabled') || String(t.enabled) === q.get('enabled'))
                && (!q.get('categoryId') || String(t.category.id) === q.get('categoryId'))
                && (!q.get('tagId') || t.tags.some(tag => String(tag.id) === q.get('tagId')))
                && (!q.get('search') || (t.title + t.description + t.tags.map(x => x.name).join(' ')).includes(q.get('search')))));
        }
    } else if (/\/templates\/\d+$/.test(path)) {
        const id = Number(path.split('/').pop());
        data = window.qaTemplates.find(t => t.id === id);
        if (method === 'PUT') {
            data = {
                ...data, ...input,
                category: window.qaTerms.find(t => t.id === input.categoryId),
                tags: window.qaTerms.filter(t => input.tagIds.includes(t.id))
            };
            window.qaTemplates = window.qaTemplates.map(t => t.id === id ? data : t);
            window.qaPersist();
        }
        if (!data || (path.includes('/public/') && !data.enabled)) {
            code = 404;
            data = null;
            message = '模板已下架';
        }
    } else throw new Error('Unexpected API ' + method + ' ' + path);
    return new Response(JSON.stringify({code, data, message}), {headers: {'Content-Type': 'application/json'}});
};
