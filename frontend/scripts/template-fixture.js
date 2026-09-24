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
    "promptPattern": "设计一张适合社交账户使用的头像。主体是{{subject}}，采用{{style}}风格，配色为{{color}}。主体清晰居中，背景简洁，保留适合圆形裁切的安全区域，细节精致，不添加文字或水印。",
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
    "promptPattern": "为{{device}}创作壁纸，内容为{{scene}}，氛围与色彩是{{mood}}。根据画布构图，在显示时间和图标的区域保留干净留白，视觉层次丰富且柔和，不添加文字、边框或水印。",
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
    "promptPattern": "创作{{festival}}祝福图，使用{{style}}风格，围绕节日代表性元素构图。画面仅呈现这句祝福文字：“{{greeting}}”。让文字清晰、层级分明，保持充足留白，避免其他无关文案或水印。",
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
    "promptPattern": "设计小红书内容封面，主题为{{topic}}。封面标题必须是“{{headline}}”，采用{{style}}。标题醒目易读，主体突出，画面简洁，文字与主体不要相互遮挡。不添加虚构数据、无关文字或水印。",
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
    "promptPattern": "为主题“{{topic}}”的文章创作配图，重点表达“{{idea}}”。采用{{style}}，视觉叙事清晰，元素精炼，配色协调，适合嵌入正文。不生成正文段落、品牌标识或水印。",
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
    "promptPattern": "为视频“{{topic}}”设计封面，画面主体是{{subject}}。以“{{headline}}”作为唯一标题，保证小尺寸观看时依然清晰。构图有明确焦点，主体与背景层次分明，标题放在留白区域，不添加其他文字或水印。",
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
    "promptPattern": "制作商品主图。商品是{{product}}，重点展示{{feature}}，背景与光线为{{background}}。如提供参考图，以图中商品为依据，尽量保持外形、颜色、标签和结构；未提供参考图时按描述创作概念展示。商品主体清晰完整，细节自然，避免虚构品牌、促销文字或水印。",
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
    "promptPattern": "创作商品场景展示图，商品为{{product}}，放置在{{scene}}中，呈现{{mood}}。如提供商品参考图，尽量保持其外形、颜色和结构；否则按描述创作概念商品。比例自然，商品为画面焦点，光线和阴影与环境一致，不添加文字或水印。",
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
    "promptPattern": "为{{product}}制作促销海报，风格与配色为{{style}}。主标题严格使用“{{headline}}”，活动信息严格使用“{{offer}}”，不要增添未提供的价格、折扣、销量或功效。若有参考图，尽量保留商品外观；否则按描述创作概念展示。商品、标题与活动信息层级清楚，排版整洁，不添加水印。",
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
