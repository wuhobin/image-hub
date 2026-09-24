const records = [
    {
        id: 1,
        image: 'coast',
        title: '湖边，等一场风',
        prompt: '群山环绕的湖泊，湖水倒映着远处的山脊。沿着岸边的木屋和绿树，记录一个安静、明亮的午后。自然光，细腻的风景摄影质感。',
        date: '09.22',
        time: '16:47',
        status: 'done',
        ratio: '3:2',
        size: '1536 × 1024'
    },
    {
        id: 2,
        image: 'desert',
        title: '荒漠的地平线',
        prompt: '红色荒漠与高耸岩柱，远处的地平线在蓝天下延伸。保留岩石的纹理，让自然光呈现空旷与辽阔。',
        date: '09.22',
        time: '16:36',
        status: 'done',
        ratio: '3:2',
        size: '1536 × 1024'
    },
    {
        id: 3,
        image: 'fashion',
        title: '让一抹黄色走进日常',
        prompt: '城市街头的时装人像，明亮黄色与沉静背景形成对比。保留完整人物构图，自然的姿态，清晰的服装质感。',
        date: '09.22',
        time: '15:20',
        status: 'done',
        ratio: '4:5',
        size: '1024 × 1280'
    },
    {
        id: 4,
        image: 'mountain',
        title: '山的另一面',
        prompt: '远处的雪山，岩石与积雪组成丰富的层次。保持山峰完整，用冷色调表达宁静与辽阔。',
        date: '09.21',
        time: '20:18',
        status: 'done',
        ratio: '3:2',
        size: '1536 × 1024'
    },
    {
        id: 5,
        image: 'forest',
        title: '走进森林的光里',
        prompt: '阳光穿过高大的树木，洒在层层叠叠的绿叶上。深绿色森林，空气感，细腻的光线。',
        date: '09.21',
        time: '18:42',
        status: 'done',
        ratio: '3:2',
        size: '1536 × 1024'
    },
    {
        id: 6,
        image: 'sea',
        title: '潮水停留的片刻',
        prompt: '傍晚的大海，浪花拍打海岸，远方的天空渐渐暗下去。自然的色彩，安静的电影画面。',
        date: '09.21',
        time: '17:06',
        status: 'done',
        ratio: '3:2',
        size: '1536 × 1024'
    },
    {
        id: 7,
        title: '雨后街道的霓虹倒影',
        prompt: '雨后街道，霓虹灯在路面积水中的倒影，深蓝和柔和紫色，电影感。',
        date: '09.21',
        time: '16:32',
        status: 'running',
        ratio: '3:2',
        size: '1536 × 1024'
    },
    {
        id: 8,
        title: '静物与午后的影子',
        prompt: '窗边的一只陶瓷花瓶，一枝植物，日光在桌面投下清晰的影子。',
        date: '09.21',
        time: '16:12',
        status: 'failed',
        ratio: '1:1',
        size: '1024 × 1024'
    }
];
const labels = {done: '已完成', running: '生成中', failed: '生成失败'};
const variant = document.body.dataset.variant;
let filter = 'all', query = '', sort = 'desc', selected = 1;
const content = document.querySelector('#content'), dialog = document.querySelector('#detail');

function status(r) {
    return '<span class="status ' + r.status + '"><i class="dot" aria-hidden="true"></i>' + labels[r.status] + '</span>';
}

function photo(r, mini = false) {
    return r.image ? '<img src="assets/' + r.image + '.jpg" alt="' + r.title + '">' : '<span class="' + (mini ? 'mini-placeholder' : 'empty-image') + '"><b aria-hidden="true">' + (r.status === 'failed' ? '!' : '✳') + '</b>' + (mini ? '' : '<span>' + (r.status === 'failed' ? '本次生成未完成 · 查看原因' : '画面正在生成中…') + '</span>') + '</span>';
}

function card(r) {
    return '<article><button class="art-button" data-open="' + r.id + '" aria-haspopup="dialog">' + photo(r) + '<div class="caption"><h3>' + r.title + '</h3><div class="caption-bottom"><span>GPT-Image-2 · 1K</span><time>9 月 ' + r.date.slice(3) + ' 日 · ' + r.time + '</time></div><div class="caption-bottom">' + status(r) + '<span>查看详情 ↗</span></div></div></button></article>';
}

function info(r) {
    return '<dl class="info-grid"><div><dt>创作模型</dt><dd>GPT-Image-2</dd></div><div><dt>创建时间</dt><dd>09 / ' + r.date.slice(3) + ' · ' + r.time + '</dd></div><div><dt>输出尺寸</dt><dd>' + r.size + '</dd></div><div><dt>图片质量</dt><dd>高清 · 1K</dd></div></dl>';
}

function visible() {
    return records.filter(r => (filter === 'all' || r.status === filter) && (!query || (r.title + r.prompt).includes(query))).sort((a, b) => sort === 'desc' ? a.id - b.id : b.id - a.id);
}

function render() {
    const list = visible();
    document.querySelector('.count').textContent = records.length;
    if (!list.length) {
        content.innerHTML = '<p class="empty-result">没有匹配的记录，试试其他关键词。</p>';
        return;
    }
    if (variant === 'a') {
        content.innerHTML = '<div class="dayline"><h2>2026 年 9 月</h2><span>' + list.length + ' 次创作</span></div><div class="masonry">' + list.map(card).join('') + '</div>';
    } else if (variant === 'b') {
        const days = [...new Set(list.map(r => r.date))];
        content.innerHTML = days.map(day => '<section class="archive-day"><header class="archive-date"><small>2026 / SEP</small><strong>' + day.replace('.', ' / ') + '</strong><p>' + list.filter(r => r.date === day).length + ' 次创作<br> 灵感存档</p></header><div class="archive-grid">' + list.filter(r => r.date === day).map(card).join('') + '</div></section>').join('');
    } else {
        if (!list.some(r => r.id === selected)) selected = list[0].id;
        const r = list.find(r => r.id === selected), index = list.indexOf(r);
        content.innerHTML = '<div class="viewer-layout"><section class="stage"><button class="art-button stage-image" data-open="' + r.id + '" aria-label="查看' + r.title + '详情">' + photo(r) + '</button><div class="stage-bottom"><div><h2>' + r.title + '</h2><p>' + String(index + 1).padStart(2, '0') + ' / ' + String(list.length).padStart(2, '0') + ' &nbsp; · &nbsp; 2026 年 9 月 ' + r.date.slice(3) + ' 日</p></div><div class="arrow-buttons"><button data-step="-1" aria-label="上一张">←</button><button data-step="1" aria-label="下一张">→</button></div></div><div class="filmstrip" aria-label="选择作品">' + list.map(x => '<button data-select="' + x.id + '" aria-pressed="' + (x.id === selected) + '" aria-label="' + x.title + '">' + photo(x, true) + '<span>' + x.time + '</span></button>').join('') + '</div></section><aside class="viewer-info">' + status(r) + '<h2>' + r.title + '</h2><p>' + r.prompt + '</p>' + info(r) + '<button class="primary" data-open="' + r.id + '">查看完整详情 ↗</button><div class="record-list"><h3>同一天的创作</h3>' + list.filter(x => x.date === r.date).map(x => '<button data-select="' + x.id + '" aria-pressed="' + (x.id === selected) + '">' + photo(x, true) + '<span><span class="list-name">' + x.title + '</span><small>' + x.time + ' · ' + labels[x.status] + '</small></span></button>').join('') + '</div></aside></div>';
    }
}

document.querySelectorAll('[data-filter]').forEach(b => b.addEventListener('click', () => {
    filter = b.dataset.filter;
    document.querySelectorAll('[data-filter]').forEach(x => x.setAttribute('aria-pressed', String(x === b)));
    render();
}));
document.querySelector('#search').addEventListener('input', e => {
    query = e.target.value.trim();
    render();
});
document.querySelector('#sort').addEventListener('change', e => {
    sort = e.target.value;
    render();
});
content.addEventListener('click', e => {
    const open = e.target.closest('[data-open]'), select = e.target.closest('[data-select]'),
        step = e.target.closest('[data-step]');
    if (open) showDetail(Number(open.dataset.open));
    if (select) {
        selected = Number(select.dataset.select);
        render();
        content.querySelector('.filmstrip [aria-pressed=true]')?.focus({preventScroll: true});
    }
    if (step) {
        const list = visible(), i = list.findIndex(r => r.id === selected);
        selected = list[(i + Number(step.dataset.step) + list.length) % list.length].id;
        render();
        content.querySelector('[data-step="' + step.dataset.step + '"]')?.focus({preventScroll: true});
    }
});

function showDetail(id) {
    const r = records.find(x => x.id === id);
    document.querySelector('#detail-body').innerHTML = '<div class="modal-body"><div>' + photo(r) + '</div><div class="modal-copy">' + status(r) + '<h2>' + r.title + '</h2><p>' + r.prompt + '</p>' + info(r) + (r.status === 'failed' ? '<p>模型服务暂时不可用，请稍后重新尝试。</p>' : r.status === 'running' ? '<p>演示状态：作品正在生成中。</p>' : '') + '<p class="select-tip">当前为设计预览，展示的是示例图片与记录。</p></div></div>';
    dialog.showModal();
}

document.querySelector('#close-detail').addEventListener('click', () => dialog.close());
dialog.addEventListener('click', e => {
    if (e.target === dialog) {
        const b = dialog.getBoundingClientRect();
        if (e.clientX < b.left || e.clientX > b.right || e.clientY < b.top || e.clientY > b.bottom) dialog.close();
    }
});
document.querySelector('#new-creation').addEventListener('click', () => {
    document.querySelector('#detail-body').innerHTML = '<div style="padding:10px 24px 35px"><h2 style="font-size:23px;font-weight:500;margin-bottom:16px">开始下一次创作</h2><p class="muted" style="line-height:2">这里是创作入口的设计预览。选定方案后，此按钮将连接现有的创作页面。</p></div>';
    dialog.showModal();
});
render();
