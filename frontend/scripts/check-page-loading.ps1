param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'

function B { $r = & agent-browser --session imagehub-page-loading @args; if ($LASTEXITCODE -ne 0) { throw "Browser failed: $args" }; return ($r -join "`n") }

function E([string]$source) { B eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($source))) }

function A([string]$source) { if ((E $source) -ne 'true') { E '({initial:window.firstLayout,title:document.querySelector("h1")?.getBoundingClientRect().top,grid:document.querySelector(".creation-history-grid")?.getBoundingClientRect().top,total:document.querySelector(".creation-history-total")?.getBoundingClientRect().toJSON()})' | Write-Output; B screenshot (Join-Path $dir 'loading-failed.png') | Out-Null; throw $source } }

$dir = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force $dir | Out-Null
$init = Join-Path $dir 'page-loading-init.js'
@'
localStorage.setItem('imagehub.token','loading-qa');
localStorage.setItem('imagehub.admin.token','loading-admin-qa');
window.gates={auth:true,data:true};
window.pending=[];
window.calls=[];
window.entries=[];
document.addEventListener('animationstart',event=>{
 if(event.target.matches('.creation-history-item,.library-card,.creation-heading,.hero-heading,.profile-heading')) window.entries.push(event.animationName);
});
window.release=phase=>{window.gates[phase]=false;window.pending.filter(p=>p.phase===phase).forEach(p=>p.resolve());};
const imageUrl='data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="800" height="800"><rect width="800" height="800" fill="#658585"/></svg>');
const image={id:'qa-image',name:'QA image',url:imageUrl,preview:imageUrl,width:800,height:800,size:1000,type:'PNG',sourceType:'AI',createdAt:'2026-09-23 10:00:00'};
const task={id:'qa-task',requestId:'qa',prompt:'加载测试作品',modelName:'Test model',status:'SUCCEEDED',size:'1024x1024',quality:'medium',image,createTime:'2026-09-23 10:00:00',shareStatus:'PRIVATE'};
const work={shareId:'qa',authorName:'QA',imageUrl,width:800,height:800,modelName:'Test model',modelId:1,promptPublic:true,prompt:'公开作品',size:'1024x1024',quality:'medium',publishedTime:'2026-09-23 10:00:00'};
const page=records=>({records,total:records.length,current:1,size:12,pages:1});
window.fetch=async (input,options={})=>{
 if(options.method && options.method!=='GET') throw Error('No mutations allowed');
 await Promise.resolve();
 options.signal?.throwIfAborted();
 const url=new URL(String(input),location.href),path=url.pathname;
 const phase=path.endsWith('/auth/me')?'auth':'data';
 window.calls.push(path);
 if(window.gates[phase]) await new Promise((resolve,reject)=>{
   window.pending.push({phase,resolve});
   options.signal?.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')),{once:true});
 });
 options.signal?.throwIfAborted();
 if(phase==='auth' && sessionStorage.getItem('expired')) return new Response(JSON.stringify({code:401,message:'登录已过期'}));
 let data;
 if(phase==='auth') data={id:'qa',username:'QA'};
 else if(path==='/api/app/generations/active') data=null;
 else if(path==='/api/app/generations/models') data=[{id:1,name:'Test model',sizes:['1024x1024'],defaultSize:'1024x1024',qualities:['medium'],defaultQuality:'medium',pointsCost:1}];
 else if(path==='/api/app/images/quota') data={total:100,remaining:90,used:10,reserved:0};
 else if(path==='/api/app/generations') data=page([task,{...task,id:'qa2'},{...task,id:'qa3'}]);
 else if(path==='/api/app/images') data={page:page([image]),totalBytes:1000};
 else if(path==='/api/app/quota/records') data=page([]);
 else if(path==='/api/app/check-in') data={consecutiveDays:0,signedIn:false,dailyPoints:1,bonusPoints:5,rewardPoints:1,nextResetAt:Date.now()+86400000};
 else if(path==='/api/app/public/creations') data=page([work]);
 else if(path==='/api/app/public/creations/qa') {
   if(window.revoked) return new Response(JSON.stringify({code:404,message:'作品已撤销'}));
   data=work;
 }
 else if(path.startsWith('/api/admin/')) data=page([]);
 else throw Error('Unexpected API '+path);
 return new Response(JSON.stringify({code:200,data}),{headers:{'Content-Type':'application/json'}});
};
'@ | Set-Content -LiteralPath $init -Encoding UTF8
try {
 & agent-browser --session imagehub-page-loading --init-script $init open "$BaseUrl/creations"
 if ($LASTEXITCODE -ne 0) { throw 'Could not open test browser' }
 foreach($width in @(1440,390)) {
  B set viewport $width 1000 | Out-Null
  foreach($path in @('/creations','/','/upload','/history','/profile','/explore','/share/qa','/admin/users')) {
   B open "$BaseUrl$path" | Out-Null
   B wait 'main' | Out-Null
   B wait --fn 'window.calls.some(p=>p.endsWith("/auth/me"))' | Out-Null
   A '!document.body.textContent.includes("正在恢复创作记录") && !document.body.textContent.includes("正在加载页面")'
   A 'document.documentElement.scrollWidth<=innerWidth'
   if($path -notlike '/admin/*') {
    A 'document.querySelector(".footer").getBoundingClientRect().bottom>=innerHeight-1'
    A '!window.calls.some(p=>p.startsWith("/api/app/")&&!p.endsWith("/auth/me")&&!p.includes("/public/"))'
   }
   if($path -eq '/creations') {
    A 'document.querySelector("h1").textContent==="创作记录" && !!document.querySelector(".creation-history-skeleton") && !document.querySelector(".creation-history-empty")'
    E 'window.firstLayout={title:document.querySelector("h1").getBoundingClientRect().top,grid:document.querySelector(".creation-history-grid").getBoundingClientRect().top}' | Out-Null
    B screenshot (Join-Path $dir "loading-creations-auth-$width.png") | Out-Null
   }
   if($path -eq '/') { A '!!document.querySelector("#creation-prompt") && document.querySelector(".creation-submit").disabled && !document.querySelector(".login-link")' }
   if($path -eq '/profile') { A '!!document.querySelector(".profile-check-in") && !!document.querySelector(".profile-usage")' }
   if($path -eq '/explore') { A '!!document.querySelector(".explore-grid .skeleton-block")' }
   if($path -eq '/share/qa') { A '!!document.querySelector(".shared-skeleton")' }
   if($path -eq '/admin/users') { A '!!document.querySelector(".admin-console") && !window.calls.some(p=>p.startsWith("/api/app/"))' }
   E 'window.release("auth")' | Out-Null
   B wait --fn '!document.querySelector(".session-placeholder") && !document.querySelector(".admin-loading-content")' | Out-Null
   if($path -eq '/creations') {
    A 'Math.abs(document.querySelector("h1").getBoundingClientRect().top-window.firstLayout.title)<1 && Math.abs(document.querySelector(".creation-history-grid").getBoundingClientRect().top-window.firstLayout.grid)<1'
   }
   E 'window.release("data")' | Out-Null
   if($path -eq '/creations') {
    B wait '.creation-history-item' | Out-Null
    A 'Math.abs(document.querySelector("h1").getBoundingClientRect().top-window.firstLayout.title)<1 && Math.abs(document.querySelector(".creation-history-grid").getBoundingClientRect().top-window.firstLayout.grid)<1'
    A 'getComputedStyle(document.querySelector(".creation-history-item")).animationName==="creation-history-enter"'
    B wait --fn '[...document.querySelectorAll(".creation-history-item")].every(e=>e.getAnimations().length===0)' | Out-Null
   }
   if($path -eq '/history') { B wait '.library-card' | Out-Null; A 'getComputedStyle(document.querySelector(".library-card")).animationName==="enter"' }
   if($path -eq '/') { A 'getComputedStyle(document.querySelector(".creation-heading")).animationName==="creation-history-enter"' }
   if($path -eq '/upload') { A 'getComputedStyle(document.querySelector(".hero-heading")).animationName==="hero-arrive"' }
   if($path -eq '/profile') { A 'getComputedStyle(document.querySelector(".profile-heading")).animationName==="enter"' }
   if($path -eq '/profile') { B wait '.profile-usage-state strong' | Out-Null }
   if($path -eq '/explore') { B wait 'a.explore-card' | Out-Null }
   if($path -eq '/share/qa') { B wait 'article.shared-work' | Out-Null }
   A 'document.documentElement.scrollWidth<=innerWidth'
   if($path -in @('/creations','/explore','/share/qa')) {
    B wait --fn 'document.getAnimations().filter(a=>a.effect.getTiming().iterations!==Infinity).every(a=>a.playState==="finished")' | Out-Null
    E 'window.entryCount=window.entries.length; window.gates.data=true; window.dispatchEvent(new Event("focus"))' | Out-Null
    B wait --fn 'document.querySelector("main[aria-busy=true],.creation-history[aria-busy=true]")!==null' | Out-Null
    A '!!document.querySelector(".creation-history-item,a.explore-card,article.shared-work") && !document.querySelector(".creation-history-skeleton,.shared-skeleton")'
    E 'window.release("data")' | Out-Null
    B wait --fn '!document.querySelector("main[aria-busy=true],.creation-history[aria-busy=true]")' | Out-Null
    A 'window.entryCount===window.entries.length'
   }
   Write-Output "PASS $width $path"
  }
 }
 # 失效登录仍跳转，不因为提前显示外壳而请求私人数据。
 E 'sessionStorage.setItem("expired","1")' | Out-Null
 B open "$BaseUrl/creations" | Out-Null
 B wait '.creation-history-skeleton' | Out-Null
 E 'window.release("auth")' | Out-Null
 B wait '#username' | Out-Null
 A 'location.pathname==="/login" && !window.calls.some(p=>p==="/api/app/generations")'
 E 'sessionStorage.removeItem("expired")' | Out-Null
 Write-Output 'PASS: delayed auth/data, stable creation heading/grid, no guest flash, preserved refresh content, mobile overflow, admin isolation, expired-session redirect.'
} finally { B close | Out-Null }
