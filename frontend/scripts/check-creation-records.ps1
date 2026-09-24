param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'

function B { $r = & agent-browser --session imagehub-records @args; if ($LASTEXITCODE -ne 0) { throw "Browser failed: $args" }; return ($r -join "\n") }

function E([string]$source) { B eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($source))) }

function WaitRecords { B wait --fn 'document.querySelector(".creation-history")?.getAttribute("aria-busy")==="false"' | Out-Null }

function A([string]$source) { if ((E $source) -ne 'true') { throw $source } }

$dir = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force $dir | Out-Null
$init = Join-Path $dir 'records-init.js'
@'
localStorage.setItem('imagehub.token', 'records-local-qa');
window.mode = 'records';
window.hold = false;
window.pending = [];
const base = {requestId:'qa',modelName:'GPT-Image-2',quality:'medium',size:'1536x1024',status:'SUCCEEDED',durationSeconds:42,shareStatus:'PRIVATE',shareId:null,promptPublic:false};
const art = (color, width=1200, height=800) => {
 const svg='<svg xmlns="http://www.w3.org/2000/svg" width="'+width+'" height="'+height+'" viewBox="0 0 1200 800" preserveAspectRatio="none"><rect width="1200" height="800" fill="'+color+'"/><circle cx="640" cy="240" r="95" fill="#f2dfbd"/><path d="M0 580Q280 300 540 580T1200 480V800H0" fill="#2f494a"/><path d="M0 730Q390 500 800 680T1200 610V800H0" fill="#162e35"/></svg>';
 const url='data:image/svg+xml,'+encodeURIComponent(svg);
 return {id:color,name:'测试作品.png',url,preview:url,width,height,size:102400,type:'PNG',sourceType:'AI'};
};
window.records = [
 {...base,id:'one',createTime:'2026-09-23 15:32:00',prompt:'海边的静谧午后，远山与落日，低饱和的胶片色彩。',image:art('#9baca9',800,800),size:'1024x1024'},
 {...base,id:'two',createTime:'2026-09-23 14:08:00',prompt:'山风经过的地方，温暖的夕阳落在层叠的山间。',image:art('#bc9c89'),size:'1536x1024'},
 {...base,id:'three',createTime:'2026-09-23 10:16:00',prompt:'雨后的城市街角，一间安静的小书店。'.repeat(25),image:art('#798f9f',800,1200),size:'1024x1536',modelName:'A very long model name that should remain inside the card'},
 {...base,id:'four',createTime:'2025-12-31 23:59:00',prompt:'雾中的森林，晨光正在缓缓亮起。',image:null,status:'GENERATING'},
 {...base,id:'five',createTime:'2025-12-31 20:00:00',prompt:'一次尚未完成的灵感，等待下次再试。',image:null,status:'FAILED',errorMessage:'模型暂时不可用'},
 {...base,id:'six',createTime:'2025-12-31 18:00:00',prompt:'已删除图片的创作仍保留描述。',image:null},
 {...base,id:'seven',createTime:'2025-12-30 17:00:00',prompt:'跨月作品也接在较短的列下方。',image:art('#927ca2',1200,600)},
 {...base,id:'eight',createTime:'2025-11-29 16:00:00',prompt:'另一张方形作品。',image:art('#b29973',800,800)},
 {...base,id:'nine',createTime:'2025-11-28 15:00:00',prompt:'最早的竖幅作品。',image:art('#729ea0',600,900)},
 {...base,id:'ten',createTime:'2025-11-27 14:00:00',prompt:'保存失败的记录。',image:null,status:'SAVE_FAILED'},
 {...base,id:'eleven',createTime:'2025-11-26 13:00:00',prompt:'结果过期的记录。',image:null,status:'EXPIRED'},
 {...base,id:'twelve',createTime:'2025-11-25 12:00:00',prompt:'已放弃的记录。',image:null,status:'ABANDONED'},
];
window.records.push(...Array.from({length:5},(_,i)=>({...window.records[0],id:'extra-'+i,createTime:'2025-01-0'+(5-i)+' 10:00:00',prompt:'更早的作品 '+i})),{...window.records[0],id:'older',createTime:'2024-01-01 08:00:00'});
window.requests=[];
window.deletions=[];
window.deleteMode='success';
window.deleteHold=false;
window.deletePending=[];
window.fetch = async (url, options={}) => {
 const target=new URL(String(url),location.href);
 const path=target.pathname.replace('/api/app','');
 let data;
 if(options.method==='DELETE' && /^\/generations\/[^/]+$/.test(path)) {
  const id=decodeURIComponent(path.split('/').at(-1));
  window.deletions.push(id);
  if(window.deleteHold) await new Promise(resolve=>window.deletePending.push(resolve));
  if(window.deleteMode==='error') return new Response(JSON.stringify({code:502,message:'存储文件删除失败，请重试'}));
  window.records=window.records.filter(record=>record.id!==id);
  return new Response(JSON.stringify({code:window.deleteMode==='gone'?404:200}));
 }
 if(options.method && options.method!=='GET') throw Error('Unexpected mutation');
 if(path==='/auth/me') data={username:'创作者'};
 else if(path==='/images/quota') data={total:100,remaining:98,used:2,reserved:0};
 else if(path==='/generations/active') data=null;
 else if(path==='/generations') {
  window.requests.push(Object.fromEntries(target.searchParams));
  if(window.hold) await new Promise(resolve=>window.pending.push(resolve));
  if(window.mode==='error') return new Response(JSON.stringify({code:500,message:'QA unavailable'}));
  const page=Number(target.searchParams.get('page'));
  const status=target.searchParams.get('status'),keyword=(target.searchParams.get('keyword')||'').toLowerCase();
  let filtered=window.mode==='empty'?[]:window.records.filter(r=>(status==='done'?r.status==='SUCCEEDED':status==='running'?['QUEUED','GENERATING','SAVING'].includes(r.status):status==='failed'?['FAILED','SAVE_FAILED','EXPIRED','ABANDONED'].includes(r.status):true)&&(!keyword||(r.prompt+' '+r.modelName).toLowerCase().includes(keyword)));
  filtered.sort((a,b)=>target.searchParams.get('order')==='asc'?a.createTime.localeCompare(b.createTime):b.createTime.localeCompare(a.createTime));
  data={records:filtered.slice((page-1)*12,page*12),current:page,total:filtered.length,size:12,pages:Math.ceil(filtered.length/12)};
 } else throw Error('Unexpected API '+path);
 return new Response(JSON.stringify({code:200,data}),{headers:{'Content-Type':'application/json'}});
};
'@ | Set-Content -LiteralPath $init -Encoding UTF8
try {
 & agent-browser --session imagehub-records --init-script $init open "$BaseUrl/creations"
 if ($LASTEXITCODE -ne 0) { throw "Could not open browser" }
 B set viewport 1440 1100 | Out-Null
 B wait '.creation-history-item' | Out-Null
 WaitRecords
 A '[...document.querySelectorAll(".creation-history-filters button")].map(b=>b.textContent).join(",")==="已完成,进行中,失败" && document.querySelector(".creation-history-filters button[aria-pressed=true]").textContent==="已完成"'
 A '!document.querySelector(".creation-history-model, .creation-history-footer, .creation-history-item .creation-status")'
 A 'window.requests[0].status==="done" && window.requests[0].page==="1"'
 A 'document.querySelectorAll(".creation-history-grid").length===1 && document.querySelectorAll(".creation-history-item").length===12'
 A 'document.querySelector(".creation-history-total").textContent.includes("13 次创作") && !document.querySelector(".creation-history-date")'
 A '!document.querySelector(".creation-history-item.is-running") && !document.querySelector(".creation-history-item.is-failed") && document.body.textContent.includes("图片已删除")'
 E 'window.masonry = () => { const grid=document.querySelector(".creation-history-grid"),box=grid.getBoundingClientRect(),columns=getComputedStyle(grid).gridTemplateColumns.split(" ").length,gap=parseFloat(getComputedStyle(grid).columnGap),width=(box.width-gap*(columns-1))/columns,tops=Array(columns).fill(box.top); return [...grid.children].every(card=>{const r=card.getBoundingClientRect(),col=Math.round((r.left-box.left)/(width+gap)),expected=Math.min(...tops); if(Math.abs(r.top-expected)>1.5 || Math.abs(r.top-tops[col])>1.5) return false; tops[col]=r.top+Math.ceil(card.offsetHeight+32); return true;}); }' | Out-Null
 E 'window.fullArtwork = () => [...document.querySelectorAll(".creation-history-thumb img")].every(img => { const r=img.getBoundingClientRect(), box=img.parentElement.getBoundingClientRect(); return img.naturalWidth>0 && Math.abs(r.width/r.height-img.naturalWidth/img.naturalHeight)<0.01 && Math.abs(box.height-r.height)<1 && Math.abs(box.width-r.width)<1 && getComputedStyle(img).transform==="none" && !img.parentElement.querySelector(".creation-status"); })' | Out-Null
 B wait --fn 'window.fullArtwork()' | Out-Null
 B hover '.creation-history-item' | Out-Null
 A 'window.fullArtwork()'
 B wait --fn '[...document.querySelectorAll(".creation-history-item")].every(el=>getComputedStyle(el).opacity==="1")' | Out-Null
 A 'getComputedStyle(document.querySelector(".creation-history-grid")).gridTemplateColumns.split(" ").length===4 && window.masonry()'
 B click '.creation-history-filters button:nth-of-type(2)' | Out-Null
 WaitRecords
 A 'document.querySelectorAll(".creation-history-item").length===1 && !!document.querySelector(".creation-history-item.is-running") && !document.querySelector(".creation-history-delete")'
 B click '.creation-history-filters button:last-child' | Out-Null
 WaitRecords
 A 'document.querySelectorAll(".creation-history-item").length===4 && document.querySelectorAll(".creation-history-item.is-failed").length===4 && document.querySelector(".creation-history-total").textContent.includes("4 次创作")'
 B click '.creation-history-filters button:first-of-type' | Out-Null
 WaitRecords
 B fill '.creation-history-search input' 'GPT-image-2' | Out-Null
 WaitRecords
 A 'document.querySelectorAll(".creation-history-item").length===12'
 B fill '.creation-history-search input' '找不到的作品' | Out-Null
 WaitRecords
 A 'document.querySelector(".creation-history-empty").textContent.includes("没有符合条件") && !document.querySelector(".library-pagination")'
 B click '.creation-history-empty button' | Out-Null
 WaitRecords
 A '!!document.querySelector(".creation-history-sort-trigger")'
 B click '.creation-history-sort-trigger' | Out-Null
 B wait '.creation-history-sort-popover:popover-open' | Out-Null
 A 'document.activeElement.matches("input[value=desc]")'
 B press ArrowDown | Out-Null
 A '!!document.querySelector(".creation-history-sort-popover:popover-open") && document.activeElement.matches("input[value=asc]")'
 B press Enter | Out-Null
 A '!document.querySelector(".creation-history-sort-popover:popover-open") && document.activeElement.matches(".creation-history-sort-trigger")'
 WaitRecords
 A 'window.requests.at(-1).order==="asc" && document.querySelector(".creation-history-item .creation-history-prompt").textContent===window.records[17].prompt'
 B click '.creation-history-sort-trigger' | Out-Null
 B click '.creation-history-sort-option:has(input[value=desc])' | Out-Null
 A '!document.querySelector(".creation-history-sort-popover:popover-open")'
 WaitRecords
 B wait --fn '[...document.querySelectorAll(".creation-history-item")].every(el=>getComputedStyle(el).opacity==="1")' | Out-Null
 A 'document.querySelector(".creation-history-filters button[aria-pressed=true]").textContent==="已完成" && document.querySelectorAll(".creation-history-item").length===12 && window.masonry()'
 B screenshot (Join-Path $dir 'records-desktop.png') --full | Out-Null
 B click '.creation-history-item' | Out-Null
 B wait '.creation-detail-modal[open]' | Out-Null
 A 'document.querySelector(".creation-detail-prompt p").textContent===window.records[0].prompt'
 B press Escape | Out-Null
 B wait --fn 'document.activeElement.matches(".creation-history-item")' | Out-Null
 A '[document.activeElement, document.activeElement.querySelector(".creation-history-prompt")].every(el=>getComputedStyle(el).outlineStyle==="none")'
 B focus '.creation-history-item' | Out-Null
 B press Enter | Out-Null
 B wait '.creation-detail-modal[open]' | Out-Null
 B focus '.creation-image' | Out-Null
 B press Tab | Out-Null
 A 'getComputedStyle(document.activeElement).outlineStyle==="none"'
 B click '.creation-image' | Out-Null
 B wait '.preview-modal[open]' | Out-Null
 B focus '.preview-image-wrap' | Out-Null
 A 'getComputedStyle(document.activeElement).outlineStyle==="none"'
 B press Tab | Out-Null
 A 'getComputedStyle(document.activeElement).outlineStyle==="none" && !!document.activeElement.closest(".preview-toolbar")'
 B press Escape | Out-Null
 B press Escape | Out-Null
 B wait --fn 'document.activeElement.matches(".creation-history-item")' | Out-Null
 A 'getComputedStyle(document.activeElement.querySelector(".creation-history-prompt")).outlineStyle==="none"'
 B screenshot (Join-Path $dir 'records-focus-no-outline.png') | Out-Null
 foreach($width in @(1100,768,390,320)) {
  B set viewport $width 844 | Out-Null
  B wait --fn 'document.documentElement.scrollWidth<=innerWidth && window.fullArtwork() && window.masonry()' | Out-Null
  B click '.creation-history-sort-trigger' | Out-Null
  B wait '.creation-history-sort-popover:popover-open' | Out-Null
  A '(()=>{const r=document.querySelector(".creation-history-sort-popover").getBoundingClientRect(); return r.left>=0 && r.right<=innerWidth && r.bottom<=innerHeight;})()'
  B screenshot (Join-Path $dir "records-sort-$width.png") | Out-Null
  B press Escape | Out-Null
  A '!document.querySelector(".creation-history-sort-popover:popover-open")'
  B click '.creation-history-sort-trigger' | Out-Null
  B click '.creation-history-search input' | Out-Null
  A '!document.querySelector(".creation-history-sort-popover:popover-open")'
  B screenshot (Join-Path $dir "records-$width.png") --full | Out-Null
 }
 E 'window.hold=true' | Out-Null
 B click '.library-pagination button:last-child' | Out-Null
 B wait --fn 'window.pending.length>0' | Out-Null
 A 'document.querySelector(".creation-history").getAttribute("aria-busy")==="true" && [...document.querySelectorAll(".library-pagination button")].every(b=>b.disabled)'
 E 'window.hold=false; window.pending.splice(0).forEach(resolve=>resolve())' | Out-Null
 B wait --fn 'document.querySelector(".creation-history-meta time")?.dateTime==="2024-01-01T08:00:00"' | Out-Null
 A 'document.querySelectorAll(".creation-history-item").length===1 && document.querySelector(".library-pagination").textContent.includes("2 / 2")'
 B click '.creation-history-filters button:last-child' | Out-Null
 WaitRecords
 A 'window.requests.at(-1).page==="1" && window.requests.at(-1).status==="failed" && !document.querySelector(".library-pagination")'
 B click '.creation-history-filters button:first-of-type' | Out-Null
 WaitRecords
 # 同画幅作品混合单行、双行说明：按实际高度填入短列，日期下不保留空白。
 E 'window.savedRecords=window.records; window.records=[0,1,2,3,4,5].map((_,i)=>({...window.savedRecords[0],id:"square-"+i,prompt:i===1?"两行说明".repeat(30):"短说明",createTime:"2026-09-23 10:00:0"+(6-i)})); window.dispatchEvent(new Event("focus"))' | Out-Null
 B wait --fn 'document.querySelectorAll(".creation-history-item").length===6' | Out-Null
 B set viewport 1440 1100 | Out-Null
 B wait --fn '[...document.querySelectorAll(".creation-history-item")].every(el=>getComputedStyle(el).opacity==="1")' | Out-Null
 A 'window.masonry() && (()=>{const cards=[...document.querySelectorAll(".creation-history-item")], c=cards.map(e=>e.getBoundingClientRect()); return c[1].height-c[0].height>20 && Math.abs(c[4].top-c[0].bottom-32)<1 && cards.every(card=>Math.abs(card.getBoundingClientRect().bottom-card.querySelector(".creation-history-meta").getBoundingClientRect().bottom)<1);})()'
 A '(()=>{const cards=[...document.querySelectorAll(".creation-history-item")], short=cards[0].querySelector(".creation-history-prompt"), long=cards[1].querySelector(".creation-history-prompt"); return Math.abs(short.offsetHeight-parseFloat(getComputedStyle(short).lineHeight))<1 && long.offsetHeight>short.offsetHeight && cards.every(card=>{const prompt=card.querySelector(".creation-history-prompt").getBoundingClientRect(), date=card.querySelector(".creation-history-meta").getBoundingClientRect(); return Math.abs(date.top-prompt.bottom-9)<1;});})()'
 B screenshot (Join-Path $dir 'records-six-square.png') --full | Out-Null
 E 'window.records=window.savedRecords' | Out-Null
 E 'window.mode="empty"; window.dispatchEvent(new Event("focus"))' | Out-Null
 B wait '.creation-history-empty a' | Out-Null
 A '!document.querySelector(".library-pagination") && document.querySelector(".creation-history-total").textContent.includes("0 次创作")'
 E 'window.mode="error"; window.dispatchEvent(new Event("focus"))' | Out-Null
 B wait '[role="alert"]' | Out-Null
 A '!document.querySelector(".creation-history-empty a") && document.querySelector(".creation-history-empty").textContent.includes("未能加载")'
 E 'window.mode="records"; window.hold=true' | Out-Null
 B click '.creation-alert button' | Out-Null
 B wait '.creation-history-skeleton' | Out-Null
 A 'document.documentElement.scrollWidth<=innerWidth && document.querySelector(".creation-history").getAttribute("aria-busy")==="true"'
 E 'window.hold=false; window.pending.splice(0).forEach(resolve=>resolve())' | Out-Null
 B wait '.creation-history-item' | Out-Null
 # 删除确认可取消，长提示词在手机端仍可操作。
 B set viewport 320 844 | Out-Null
 B click '.creation-history-card:nth-child(3) .creation-history-delete' | Out-Null
 B wait '.creation-delete-modal[open]' | Out-Null
 A '!document.querySelector(".creation-detail-modal[open]") && document.querySelector(".delete-warning").textContent.includes("积分不退还") && document.documentElement.scrollWidth<=innerWidth'
 A '(()=>{const r=document.querySelector(".creation-delete-modal").getBoundingClientRect(); return r.left>=0 && r.right<=innerWidth && r.height<=innerHeight;})()'
 B screenshot (Join-Path $dir 'records-delete-mobile.png') | Out-Null
 B press Escape | Out-Null
 A '!document.querySelector(".creation-delete-modal") && window.deletions.length===0'
 B wait --fn 'document.activeElement.matches(".creation-history-delete")' | Out-Null
 B set viewport 1440 1100 | Out-Null
 # 最后一页只有一条：云端失败保留记录；重试期间阻止 Escape 关闭或重复提交。
 B click '.library-pagination button:last-child' | Out-Null
 WaitRecords
 A 'document.querySelectorAll(".creation-history-item").length===1'
 B focus '.creation-history-delete' | Out-Null
 B press Enter | Out-Null
 B wait '.creation-delete-modal[open]' | Out-Null
 E 'window.deleteMode="error"' | Out-Null
 B click '.creation-delete-modal .button-danger' | Out-Null
 B wait '.creation-delete-modal [role=alert]' | Out-Null
 A 'window.deletions.length===1 && document.querySelectorAll(".creation-history-item").length===1 && document.querySelector(".creation-delete-modal [role=alert]").textContent.includes("存储文件删除失败")'
 B screenshot (Join-Path $dir 'records-delete-error.png') | Out-Null
 E 'window.deleteMode="success"; window.deleteHold=true' | Out-Null
 B click '.creation-delete-modal .button-danger' | Out-Null
 B wait --fn 'window.deletePending.length===1' | Out-Null
 B press Escape | Out-Null
 A '!!document.querySelector(".creation-delete-modal[open]") && [...document.querySelectorAll(".creation-delete-modal .modal-actions button")].every(b=>b.disabled) && window.deletions.length===2'
 E 'window.deleteHold=false; window.deletePending.splice(0).forEach(resolve=>resolve())' | Out-Null
 B wait --fn '!document.querySelector(".creation-delete-modal") && document.querySelector(".creation-history-total").textContent.includes("12 次创作")' | Out-Null
 WaitRecords
 A '!document.querySelector(".library-pagination") && document.querySelectorAll(".creation-history-item").length===12 && window.requests.at(-1).page==="1" && window.masonry()'
 B wait --fn 'document.activeElement.id==="creation-history-title"' | Out-Null
 # 另一页面已经删除，404 按已完成处理；删除筛选中的最后一项展示空状态。
 B fill '.creation-history-search input' '雨后的城市街角' | Out-Null
 WaitRecords
 B wait --fn 'document.querySelectorAll(".creation-history-item").length===1' | Out-Null
 B click '.creation-history-delete' | Out-Null
 E 'window.deleteMode="gone"' | Out-Null
 B click '.creation-delete-modal .button-danger' | Out-Null
 B wait '.creation-history-empty' | Out-Null
 A '!document.querySelector(".creation-delete-modal") && document.querySelector(".creation-history-total").textContent.includes("0 次创作")'
 B click '.creation-history-empty button' | Out-Null
 WaitRecords
 B set media dark reduced-motion | Out-Null
 A 'getComputedStyle(document.querySelector(".creation-history-item")).animationName==="none"'
 Write-Output 'PASS: full square/portrait/landscape artwork without hover cropping or overlays, continuous shortest-column masonry across months, completed by default, server-side filtering/search/sort/pagination, page reset on filters, natural caption heights with consistent card spacing, status variants, long text, keyboard detail, responsive layout, pending pagination, empty/error/retry/loading, delete cancel/error/retry/404, no deletion while running, pending Escape guard, page fallback and empty filtered results, reduced motion.'
} catch {
 E '({width:innerWidth,focus:document.activeElement.outerHTML.slice(0,250),artwork:window.fullArtwork?.(),masonry:window.masonry?.(),records:document.querySelectorAll(".creation-history-item").length})'
 B screenshot (Join-Path $dir 'records-failure.png') | Out-Null
 throw
} finally { B close | Out-Null }
