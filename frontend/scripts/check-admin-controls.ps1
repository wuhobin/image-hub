param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'
if (-not ([uri]$BaseUrl).IsLoopback) { throw 'This check only supports a local preview.' }
$session = 'admin-controls-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

function B { $result = & agent-browser --session $session @args; if ($LASTEXITCODE -ne 0) { throw "Browser failed: $args" }; return ($result -join "`n") }

function E([string]$source) { B eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($source))) }

function A([string]$source) { if ((E $source) -ne 'true') { throw $source } }

$dir = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force $dir | Out-Null
$init = Join-Path $dir 'admin-controls-init.js'
# 所有接口都在浏览器内拦截，新增与保存不会写入真实后台。
@'
localStorage.setItem('imagehub.admin.token','admin-controls-qa');
window.qaErrors=[];
window.addEventListener('error',e=>window.qaErrors.push(e.message));
window.addEventListener('unhandledrejection',e=>window.qaErrors.push(String(e.reason)));
window.writes=[];
window.models=[{id:1,name:'GPT Image 2',modelCode:'gpt-image-2',baseUrl:'https://api.openai.com',imagesPath:'/v1/images/generations',keyConfigured:true,enabled:true,sizes:['1024x1024','1536x1024','1024x1536','2048x1152'],qualities:['low','medium','high','auto'],defaultSize:'2048x1152',defaultQuality:'medium',pointsCost:2,sortOrder:0}];
window.fetch=async(input,init={})=>{
 const url=new URL(String(input),location.href),path=url.pathname;
 const response=(data,code=200)=>new Response(JSON.stringify({code,data}),{headers:{'Content-Type':'application/json'}});
 if(path==='/api/admin/auth/me')return response({id:'1',username:'administrator'});
 if(path==='/api/admin/users')return response({records:[],total:0,pages:1,current:1,size:Number(url.searchParams.get('pageSize'))});
 if(path.startsWith('/api/admin/ai-models')){
  if(init.method==='POST'||init.method==='PUT'){
   const body=JSON.parse(init.body);window.writes.push({method:init.method,body});
   if(init.method==='POST')window.models.push({...body,id:window.models.length+1,keyConfigured:false});
   else window.models[0]={...window.models[0],...body};
   return response(null);
  }
  return response({records:window.models,total:window.models.length,pages:1,current:1,size:20});
 }
 return response(null,401);
};
'@ | Set-Content -LiteralPath $init -Encoding UTF8

function SelectOption([string]$Id, [string]$Value) {
 B click "#$Id" | Out-Null
 B focus "#$Id-options input[value='$Value']" | Out-Null
 B press Space | Out-Null
 B press Enter | Out-Null
}

try {
 & agent-browser --session $session --init-script $init open "$BaseUrl/admin/models"
 if ($LASTEXITCODE -ne 0) { throw 'Could not open browser' }
 B set viewport 1440 900 | Out-Null
 B wait '.model-row' | Out-Null
 B find first '.model-row-actions .button' click | Out-Null
 B focus '.model-form-scroll' | Out-Null
 B press End | Out-Null
 B wait --fn 'document.querySelector(".model-form-scroll").scrollTop>200' | Out-Null
 A '(()=>{const r=document.querySelector("#model-enabled").getBoundingClientRect(),f=document.querySelector(".model-editor-footer").getBoundingClientRect();return r.top>0&&r.bottom<f.top&&f.bottom<=innerHeight})()'
 B scroll up 380 --selector '.model-form-scroll' | Out-Null
 A 'document.querySelectorAll(".model-size-tier input").length===3 && document.querySelector("#model-default-size").textContent.includes("2048 × 1152")'
 SelectOption 'model-size-ratio' '1:1'
 B check '.model-size-tier input[value="2048x2048"]' | Out-Null
 SelectOption 'model-size-ratio' '3:2'
 B check '.model-size-tier input[value="3456x2304"]' | Out-Null
 SelectOption 'model-size-ratio' '1:1'
 A 'document.querySelectorAll(".model-size-tier input:checked").length===2 && document.querySelector("#model-default-size").textContent.includes("2048 × 1152")'
 SelectOption 'model-default-ratio' '1:1'
 SelectOption 'model-default-size' '2048x2048'
 B uncheck '.model-size-tier input[value="2048x2048"]' | Out-Null
 A 'document.querySelector("#model-default-size").textContent.includes("1024 × 1024")'
 B click '.model-size-legacy summary' | Out-Null
 B click '#model-custom-sizes' | Out-Null
 B uncheck '#model-custom-sizes-options input[value="2048x1152"]' | Out-Null
 A '!!document.querySelector("#model-custom-sizes-options input[value=\"2048x1152\"]")'
 B check '#model-custom-sizes-options input[value="2048x1152"]' | Out-Null
 B press Escape | Out-Null
 B click '.model-editor-footer .button-primary' | Out-Null
 B wait --fn '!document.querySelector(".model-modal")' | Out-Null
 A 'window.writes.length===1 && window.writes[0].method==="PUT" && window.writes[0].body.sizes.length===5 && window.writes[0].body.sizes.includes("3456x2304") && window.writes[0].body.sizes.includes("2048x1152") && window.writes[0].body.defaultSize==="1024x1024" && window.writes[0].body.apiKey===""'
 B click '.admin-page-title .button' | Out-Null
 B fill '#model-name' 'New model QA' | Out-Null
 B scroll down 550 --selector '.model-form-scroll' | Out-Null
 B click '#model-sizes-clear' | Out-Null
 A 'document.querySelector("#model-default-ratio").disabled && document.querySelector("#model-default-size").disabled'
 B click '.model-editor-footer .button-primary' | Out-Null
 A 'window.writes.length===1 && document.querySelector(".model-editor-footer [role=alert]").textContent.includes("至少选择")'
 SelectOption 'model-size-ratio' '16:9'
 B check '.model-size-tier input[value="1280x720"]' | Out-Null
 B click '#model-qualities' | Out-Null
 B click '#model-qualities-options .admin-select-actions button:last-child' | Out-Null
 B press Escape | Out-Null
 A 'document.querySelector("#model-default-quality").disabled'
 B click '.model-editor-footer .button-primary' | Out-Null
 A 'window.writes.length===1'
 B click '#model-qualities' | Out-Null
 B click '#model-qualities-options input[value="high"]' | Out-Null
 B press Escape | Out-Null
 B click '.model-editor-footer .button-primary' | Out-Null
 B wait --fn '!document.querySelector(".model-modal")' | Out-Null
 A 'window.writes.length===2 && window.writes[1].method==="POST" && window.writes[1].body.sizes.join()==="1280x720" && window.writes[1].body.defaultSize==="1280x720" && window.writes[1].body.defaultQuality==="high"'
 B click '.admin-page-title .button' | Out-Null
 B fill '#model-name' 'Gemini preset QA' | Out-Null
 B fill '#model-code' 'gemini-3.1-flash-image' | Out-Null
 B scroll down 500 --selector '.model-form-scroll' | Out-Null
 A 'document.querySelector("#model-sizes-summary").textContent.includes("56") && document.querySelectorAll(".model-size-tier input").length===4'
 SelectOption 'model-size-ratio' '1:8'
 B uncheck '.model-size-tier input[value="192x1536"]' | Out-Null
 SelectOption 'model-size-ratio' '8:1'
 A 'document.querySelectorAll(".model-size-tier input:checked").length===4'
 SelectOption 'model-size-ratio' '1:8'
 A 'document.querySelectorAll(".model-size-tier input:checked").length===3 && document.querySelector("#model-sizes-summary").textContent.includes("55")'
 SelectOption 'model-default-ratio' '1:8'
 SelectOption 'model-default-size' '1536x12288'
 SelectOption 'model-default-ratio' '8:1'
 A 'document.querySelector("#model-default-size").textContent.includes("12288 × 1536")'
 foreach ($width in @(1440,390,320)) {
  B set viewport $width 844 | Out-Null
  B focus '.model-form-scroll' | Out-Null
  B press Home | Out-Null
  B wait --fn 'document.querySelector(".model-form-scroll").scrollTop===0' | Out-Null
  B scroll down 530 --selector '.model-form-scroll' | Out-Null
  B click '#model-size-ratio' | Out-Null
  B wait '#model-size-ratio-options:popover-open' | Out-Null
  A 'document.querySelectorAll("#model-size-ratio-options input").length===14 && document.querySelectorAll(".model-size-tier input").length===4'
  A '(()=>{const r=document.querySelector("#model-size-ratio-options").getBoundingClientRect(),m=document.querySelector(".model-modal");return r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight&&m.scrollWidth<=m.clientWidth&&document.querySelector(".model-editor-footer").getBoundingClientRect().bottom<=innerHeight})()'
  B press Escape | Out-Null
  A '!document.querySelector(":popover-open") && !!document.querySelector(".model-modal[open]")'
  B screenshot (Join-Path $dir "admin-size-steps-$width.png") | Out-Null
 }
 B set viewport 1440 900 | Out-Null
 B click '.model-editor-footer .button-primary' | Out-Null
 B wait --fn '!document.querySelector(".model-modal")' | Out-Null
 A 'window.writes.length===3 && window.writes[2].body.sizes.length===55 && !window.writes[2].body.sizes.includes("192x1536") && window.writes[2].body.defaultSize==="12288x1536"'
 B find first '.model-row-actions .button' click | Out-Null
 B fill '#model-code' 'gemini-3.1-flash-image' | Out-Null
 B scroll down 500 --selector '.model-form-scroll' | Out-Null
 A 'document.querySelectorAll(".model-size-tier input").length===4 && !!document.querySelector(".model-size-legacy")'
 B click '#model-size-ratio' | Out-Null
 A 'document.querySelectorAll("#model-size-ratio-options input").length===14'
 B press ArrowDown | Out-Null
 B press Enter | Out-Null
 A 'document.activeElement.id==="model-size-ratio" && document.querySelector("#model-sizes-summary").textContent.includes("5 种尺寸") && !document.querySelector(":popover-open")'
 B click '#model-sizes-presets' | Out-Null
 B wait --fn 'document.querySelector("#model-sizes-summary").textContent.includes("56")' | Out-Null
 B click '#model-ratio-clear' | Out-Null
 A 'document.querySelectorAll(".model-size-tier input:checked").length===0 && document.querySelector("#model-sizes-summary").textContent.includes("52")'
 B click '#model-ratio-all' | Out-Null
 A 'document.querySelectorAll(".model-size-tier input:checked").length===4 && document.querySelector("#model-sizes-summary").textContent.includes("56")'
 B click '#model-qualities' | Out-Null
 B click '.model-modal .modal-header h2' | Out-Null
 A '!document.querySelector(":popover-open") && !!document.querySelector(".model-modal[open]")'
 B click '.model-editor-footer .button-primary' | Out-Null
 B wait --fn '!document.querySelector(".model-modal")' | Out-Null
 A 'window.writes.length===4 && window.writes[3].method==="PUT" && window.writes[3].body.sizes.length===56 && !window.writes[3].body.sizes.includes("3456x2304") && !window.writes[3].body.sizes.includes("2048x1152")'
 B click 'a[href="/admin/users"]' | Out-Null
 B wait '#admin-page-size' | Out-Null
 B click '#admin-page-size' | Out-Null
 B press ArrowDown | Out-Null
 B press Enter | Out-Null
 B wait --fn 'location.search.includes("pageSize=50")' | Out-Null
 A 'document.querySelector("#admin-page-size").textContent.includes("50") && !document.querySelector(":popover-open") && window.qaErrors.length===0'
 Write-Output 'PASS: per-ratio resolutions, dependent defaults, custom preservation/re-selection, preset isolation, all/clear, edit/create payloads, keyboard and mobile layout.'
} catch {
 B screenshot (Join-Path $dir 'admin-controls-failure.png') | Out-Null
 throw
} finally { B close | Out-Null }
