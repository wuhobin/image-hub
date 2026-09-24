param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'
function B {
    if ($args[0] -eq 'wait' -and $args[1] -eq '--fn') {
        $args[2] = "eval(atob('" + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($args[2])) + "'))"
    }
    if ($args[0] -eq 'click') { & agent-browser --session imagehub-sharing scrollintoview $args[1] | Out-Null }
    $result = & agent-browser --session imagehub-sharing @args
    if ($LASTEXITCODE -ne 0) { throw "Browser command failed: $args" }
    return ($result -join [Environment]::NewLine)
}
function E([string]$script) { B eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script))) }
function A([string]$script) { if ((E $script) -ne 'true') { throw "Assertion failed: $script" } }
$qaDirectory = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force $qaDirectory | Out-Null
$init = Join-Path $qaDirectory 'sharing-init.js'
@'
window.calls = [];
const colors = ['#34545d', '#63647e', '#897258'];
function artwork(index) {
 const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800"><rect width="1200" height="800" fill="'+colors[index]+'"/><circle cx="810" cy="210" r="90" fill="#efdab5"/><path d="M0 560Q250 320 550 520T1200 460V800H0" fill="#213940"/><path d="M0 720Q400 440 780 660T1200 610V800H0" fill="#132a30"/></svg>';
 return 'data:image/svg+xml,' + encodeURIComponent(svg);
}
const initial = [
 {shareId:'11111111-1111-4111-8111-111111111111',shareStatus:'PUBLIC',authorName:'山间来信',imageUrl:artwork(0),width:1200,height:800,modelId:2,modelName:'测试模型二',size:'1536x1024',quality:'high',promptPublic:true,prompt:'晨雾中的山林，暖色日光，安静的画面。',publishedTime:'2026-09-22 12:00:00'},
 {shareId:'22222222-2222-4222-8222-222222222222',shareStatus:'PUBLIC',authorName:'远山',imageUrl:artwork(1),width:1200,height:800,modelId:1,modelName:'测试模型一',size:'1024x1024',quality:'medium',promptPublic:false,prompt:null,publishedTime:'2026-09-22 11:00:00'}
];
window.works = JSON.parse(sessionStorage.getItem('sharing-works') || 'null') || initial;
window.own = JSON.parse(sessionStorage.getItem('sharing-own') || 'null') || {id:'own-task',requestId:'qa-request',status:'SUCCEEDED',modelName:'测试模型二',prompt:'我自己的山林作品',size:'1536x1024',quality:'high',createTime:'2026-09-22 12:00:00',durationSeconds:8.25,shareStatus:'PRIVATE',shareId:null,promptPublic:true,image:{id:'own-task',name:'AI.png',url:artwork(2),preview:artwork(2),width:1200,height:800,size:10000,type:'PNG',sourceType:'AI'}};
window.persist = () => { sessionStorage.setItem('sharing-works',JSON.stringify(window.works)); sessionStorage.setItem('sharing-own',JSON.stringify(window.own)); };
const originalFetch = window.fetch;
window.fetch = async (url, options={}) => {
 const target = new URL(String(url),location.href), path=target.pathname, method=options.method || 'GET';
 if (!path.startsWith('/api/')) return originalFetch(url,options);
 window.calls.push({path,method,body:options.body});
 let data=null, code=200, message='';
 if(path==='/api/app/auth/login') data={token:'sharing-user',user:{username:'分享测试'}};
 else if(path==='/api/app/auth/me') data={username:'分享测试'};
 else if(path==='/api/app/auth/logout') data=null;
 else if(path==='/api/admin/auth/me') data={id:'1',username:'分享管理员'};
 else if(path==='/api/app/images/quota') data={total:100,remaining:98,used:2,reserved:0};
 else if(path==='/api/app/generations/models') data=[
   {id:1,name:'测试模型一',sizes:['1024x1024'],defaultSize:'1024x1024',qualities:['medium'],defaultQuality:'medium',pointsCost:1},
   {id:2,name:'测试模型二',sizes:['1024x1024','1536x1024'],defaultSize:'1024x1024',qualities:['medium','high'],defaultQuality:'medium',pointsCost:3}];
 else if(path==='/api/app/generations/active') data=null;
 else if(path==='/api/app/generations/own-task') data=window.own;
 else if(path==='/api/app/generations/own-task/share') {
   if(method==='DELETE') {
     window.works=window.works.filter(w=>w.shareId!==window.own.shareId);
     window.own={...window.own,shareStatus:'PRIVATE',shareId:null};
   } else {
     const id=window.own.shareId || crypto.randomUUID();
     window.own={...window.own,shareId:id,shareStatus:'PUBLIC',promptPublic:JSON.parse(options.body).promptPublic};
     window.works=window.works.filter(w=>w.shareId!==id);
     window.works.unshift({...initial[0],shareId:id,authorName:'分享测试',imageUrl:window.own.image.url,promptPublic:window.own.promptPublic,prompt:window.own.promptPublic?window.own.prompt:null});
   }
   window.persist(); data=window.own;
 } else if(path==='/api/app/generations') {
   if(method==='POST') throw new Error('QA must never generate an image');
   data={records:[window.own],current:1,total:1,pages:1,size:12};
 } else if(path==='/api/app/public/creations' || path==='/api/admin/creations') {
   const records=window.works.filter(w=>path.includes('/admin/') || w.shareStatus==='PUBLIC');
   data={records,current:1,total:records.length,pages:1,size:12};
 } else if(path.startsWith('/api/app/public/creations/')) {
   data=window.works.find(w=>w.shareId===path.split('/').pop() && w.shareStatus==='PUBLIC');
   if(!data) {code=404;message='作品不存在或已停止分享';}
 } else if(path.endsWith('/block') && path.startsWith('/api/admin/creations/')) {
   const id=path.split('/').at(-2);
   window.works=window.works.map(w=>w.shareId===id?{...w,shareStatus:'BLOCKED'}:w);
   if(window.own.shareId===id) window.own.shareStatus='BLOCKED';
   window.persist();
 } else throw new Error('Unexpected API '+method+' '+path);
 return new Response(JSON.stringify({code,data,message}),{headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
};
'@ | Set-Content -LiteralPath $init -Encoding UTF8
try {
    & agent-browser --session imagehub-sharing --init-script $init open
    B set viewport 1440 1000 | Out-Null
    B open "$BaseUrl/explore" | Out-Null
    B wait '.explore-card' | Out-Null
    A '!localStorage.getItem("imagehub.token") && document.querySelectorAll(".explore-card").length===2'
    # 混合画幅、长短提示词：图片保持原比例，后续卡片填入较短列。
    E 'window.savedWorks=window.works; window.works=[[800,800],[1200,800],[800,1200],[1200,600],[600,900],[800,800],[1200,800],[800,800]].map(([width,height],i)=>({...window.savedWorks[i%2],shareId:"masonry-"+i,width,height,promptPublic:i!==3,prompt:i===1?"两行提示词".repeat(20):"山间的一束光",imageUrl:"data:image/svg+xml,"+encodeURIComponent(decodeURIComponent(window.savedWorks[i%2].imageUrl.split(",")[1]).replace("width=\"1200\" height=\"800\"","width=\""+width+"\" height=\""+height+"\" viewBox=\"0 0 1200 800\" preserveAspectRatio=\"none\""))})); window.dispatchEvent(new Event("focus"))' | Out-Null
    B wait --fn 'document.querySelectorAll("a.explore-card").length===8' | Out-Null
    E 'window.galleryFits=()=>{const grid=document.querySelector(".explore-grid"),box=grid.getBoundingClientRect(),cols=getComputedStyle(grid).gridTemplateColumns.split(" ").length,gap=parseFloat(getComputedStyle(grid).columnGap),width=(box.width-gap*(cols-1))/cols,tops=Array(cols).fill(box.top); return [...grid.children].every(card=>{const r=card.getBoundingClientRect(),col=Math.round((r.left-box.left)/(width+gap)),img=card.querySelector("img"),ir=img.getBoundingClientRect(),frame=img.parentElement.getBoundingClientRect(); if(Math.abs(r.top-Math.min(...tops))>1.5 || Math.abs(r.top-tops[col])>1.5 || Math.abs(ir.width/ir.height-img.naturalWidth/img.naturalHeight)>.01 || Math.abs(ir.height-frame.height)>1) return false; tops[col]=r.top+Math.ceil(card.offsetHeight+32); return true;});}' | Out-Null
    B wait --fn '[...document.querySelectorAll(".explore-image img")].every(img=>img.complete && img.naturalWidth>0) && document.getAnimations().every(a=>a.effect.getTiming().iterations===Infinity || a.playState==="finished")' | Out-Null
    A 'window.galleryFits() && getComputedStyle(document.querySelector(".explore-grid")).gridTemplateColumns.split(" ").length===4'
    B hover '.explore-card:first-child' | Out-Null
    A 'window.galleryFits()'
    B screenshot (Join-Path $qaDirectory 'explore-masonry-desktop.png') --full | Out-Null
    foreach ($width in @(1100,768,390,320)) {
        B set viewport $width 844 | Out-Null
        B wait --fn 'window.galleryFits()' | Out-Null
        A 'document.documentElement.scrollWidth<=innerWidth && window.galleryFits()'
        B screenshot (Join-Path $qaDirectory "explore-masonry-$width.png") --full | Out-Null
    }
    B set media dark reduced-motion | Out-Null
    A 'getComputedStyle(document.querySelector(".explore-card")).animationName==="none"'
    B set media dark no-preference | Out-Null
    E 'window.works=window.savedWorks; window.dispatchEvent(new Event("focus"))' | Out-Null
    B wait --fn 'document.querySelectorAll("a.explore-card").length===2' | Out-Null
    B screenshot (Join-Path $qaDirectory 'sharing-gallery.png') --full | Out-Null
    foreach ($width in @(390,320)) {
        B set viewport $width 844 | Out-Null
        A 'document.documentElement.scrollWidth<=innerWidth'
        B screenshot (Join-Path $qaDirectory "sharing-gallery-$width.png") --full | Out-Null
    }
    B set viewport 1440 1000 | Out-Null
    B click '.explore-card:first-child' | Out-Null
    B wait '.shared-remix' | Out-Null
    A 'document.querySelector(".shared-prompt").textContent.includes(window.works[0].prompt)'
    B screenshot (Join-Path $qaDirectory 'sharing-detail.png') --full | Out-Null
    B click '.shared-remix' | Out-Null
    B wait '#username' | Out-Null
    A 'history.state.usr.creationPreset.modelId===2'
    B fill '#username' 'sharing-test' | Out-Null
    B fill '#password' 'test-only-password' | Out-Null
    B click '.auth-submit' | Out-Null
    B wait --fn 'document.querySelector("#creation-model")?.value==="2"' | Out-Null
    A 'document.querySelector("#creation-prompt").value===window.works[0].prompt && !document.querySelector(".creation-reference") && !window.calls.some(c=>c.path==="/api/app/generations" && c.method==="POST")'
    A 'document.querySelector("#creation-quality").value==="high"'
    A 'document.querySelector(".creation-ratio-trigger").textContent.includes("3:2")'
    B click ".navigation a[href='/explore']" | Out-Null
    B wait '.explore-card' | Out-Null
    E 'window.works[0]={...window.works[0],modelId:999,size:"2048x2048",quality:"unsupported"}' | Out-Null
    B click '.explore-card:first-child' | Out-Null
    B wait '.shared-remix' | Out-Null
    B set viewport 320 844 | Out-Null
    A 'document.documentElement.scrollWidth<=innerWidth'
    B screenshot (Join-Path $qaDirectory 'sharing-detail-320.png') --full | Out-Null
    B set viewport 1440 1000 | Out-Null
    B click '.shared-remix' | Out-Null
    B wait --fn 'document.querySelector("#creation-model")?.value==="1"' | Out-Null
    A 'document.querySelector("#creation-quality").value==="medium" && document.querySelector(".creation-notice").textContent.includes("不可用") && !window.calls.some(c=>c.path==="/api/app/generations" && c.method==="POST")'
    B click '.creation-records-entry a' | Out-Null
    B wait '.creation-history-item' | Out-Null
    B click '.creation-history-item' | Out-Null
    B wait '.creation-detail-tabs' | Out-Null
    B screenshot (Join-Path $qaDirectory 'creation-detail-desktop.png') | Out-Null
    foreach ($width in @(390,320)) {
        B set viewport $width 844 | Out-Null
        A 'document.querySelector(".creation-detail-modal").scrollWidth<=document.querySelector(".creation-detail-modal").clientWidth'
        B screenshot (Join-Path $qaDirectory "creation-detail-$width.png") | Out-Null
    }
    B press Escape | Out-Null
    E 'window.own.prompt="山间的晨雾与日光。".repeat(120); window.persist()' | Out-Null
    B open "$BaseUrl/creations" | Out-Null
    B wait '.creation-history-item' | Out-Null
    B click '.creation-history-item' | Out-Null
    B wait '.creation-detail-tabs' | Out-Null
    B set viewport 1188 653 | Out-Null
    A 'document.querySelector(".creation-detail-prompt p").scrollHeight>document.querySelector(".creation-detail-prompt p").clientHeight'
    A 'document.querySelector(".creation-detail-modal .modal-header").getBoundingClientRect().top>=0 && document.querySelector(".creation-detail-actions").getBoundingClientRect().bottom<=innerHeight'
    B screenshot (Join-Path $qaDirectory 'creation-detail-long-prompt.png') | Out-Null
    B set viewport 1440 1000 | Out-Null
    B click '.creation-detail-tabs button:nth-child(2)' | Out-Null
    B wait '.sharing-toggle input' | Out-Null
    A 'document.querySelector(".sharing-toggle input").checked'
    B click '.creation-sharing .button-primary' | Out-Null
    B wait '#creation-share-url' | Out-Null
    E 'window.firstShare=window.own.shareId' | Out-Null
    B uncheck '.sharing-toggle input' | Out-Null
    B click '.creation-sharing .button-primary' | Out-Null
    B wait --fn '!window.own.promptPublic' | Out-Null
    A 'window.works.find(w=>w.shareId===window.own.shareId).prompt===null'
    B screenshot (Join-Path $qaDirectory 'sharing-settings.png') --full | Out-Null
    B click '.creation-sharing .button-secondary' | Out-Null
    B wait --fn 'window.own.shareStatus==="PRIVATE"' | Out-Null
    A '!document.querySelector("#creation-share-url")'
    B click '.creation-sharing .button-primary' | Out-Null
    B wait '#creation-share-url' | Out-Null
    A 'window.own.shareId!==window.firstShare'
    B press Escape | Out-Null
    B click '.navigation a:nth-child(2)' | Out-Null
    B wait '.explore-card' | Out-Null
    B click '.explore-card:first-child' | Out-Null
    B wait '.shared-prompt' | Out-Null
    A '!document.querySelector(".shared-remix") && !document.querySelector(".shared-prompt").textContent.includes(window.own.prompt)'
    E 'window.works=window.works.filter(w=>w.shareId!==window.own.shareId); window.persist(); window.dispatchEvent(new Event("focus"))' | Out-Null
    B wait '.sharing-state' | Out-Null
    A '!document.querySelector(".shared-artwork")'
    E 'localStorage.setItem("imagehub.admin.token","sharing-admin")' | Out-Null
    B open "$BaseUrl/admin/creations" | Out-Null
    B wait '.admin-shared-thumbnail' | Out-Null
    B click '.admin-table tbody tr:first-child .button' | Out-Null
    B wait 'dialog[open]' | Out-Null
    B click 'dialog[open] .button-primary' | Out-Null
    B wait --fn 'window.works[0].shareStatus==="BLOCKED" && !document.querySelector(".admin-table tbody tr:first-child .button")' | Out-Null
    A 'document.querySelector(".admin-table tbody tr:first-child").textContent.includes("已下架")'
    B screenshot (Join-Path $qaDirectory 'sharing-admin.png') --full | Out-Null
    Write-Output 'PASS: guest gallery/detail, mobile, login preserves remix prompt/model/quality without generation, publish/default prompt/hide/revoke/new link, unavailable share, admin takedown.'
} catch {
    B snapshot | Out-Host
    throw
} finally {
    B close | Out-Null
}
