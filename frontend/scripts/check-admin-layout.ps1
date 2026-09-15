param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'

# 独立浏览器和受控管理接口，不使用真实账号或修改服务端数据。
function Browser {
    $result = & agent-browser --session imagehub-admin-layout-check @args
    if ($LASTEXITCODE -ne 0) { throw "Browser command failed: $args" }
    return ($result -join "`n")
}
function AssertJs([string]$Source) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Source))
    if ((Browser eval -b $encoded) -ne 'true') { throw "Layout assertion failed: $Source" }
}
$outputDir = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$initFile = Join-Path $outputDir 'admin-layout-init.js'
@'
localStorage.setItem('imagehub.admin.token', 'layout-test-admin');
const originalFetch = window.fetch;
window.fetch = (url, options) => {
  const path = new URL(String(url), location.href);
  if (!path.pathname.startsWith('/api/admin/')) return originalFetch(url, options);
  const data = path.pathname.endsWith('/me') ? {id:'1', username:'operator'}
    : path.pathname.endsWith('/users') ? {
        records:[{id:'1',username:'alice',email:'alice@example.test',createTime:'2026-09-15 10:00:00',remaining:87}],
        total:21,current:Number(path.searchParams.get('page') || 1),size:20,pages:2
      } : null;
  return Promise.resolve(new Response(JSON.stringify({code:200,data}), {headers:{'Content-Type':'application/json'}}));
};
'@ | Set-Content -LiteralPath $initFile -Encoding UTF8

try {
    & agent-browser --session imagehub-admin-layout-check --init-script $initFile open
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the test browser' }
    Browser set viewport 1440 1000 | Out-Null
    Browser open "$BaseUrl/admin?search=alice" | Out-Null
    Browser wait --text alice@example.test | Out-Null
    AssertJs 'location.pathname === "/admin/users" && new URL(location.href).searchParams.get("search") === "alice"'
    AssertJs 'document.querySelector(".admin-sidebar .admin-nav-item").getAttribute("aria-current") === "page" && document.querySelectorAll("main").length === 1'
    AssertJs 'document.title === "ImgHub · 用户管理" && document.documentElement.scrollWidth <= innerWidth'
    Browser find role button click --name 下一页 | Out-Null
    Browser wait --url '**page=2*' | Out-Null
    Browser reload | Out-Null
    Browser wait --text alice@example.test | Out-Null
    AssertJs 'new URL(location.href).searchParams.get("page") === "2"'
    Browser screenshot (Join-Path $outputDir 'admin-layout-desktop.png') | Out-Null

    Browser set viewport 390 844 | Out-Null
    AssertJs 'document.documentElement.scrollWidth <= innerWidth'
    Browser find role button click --name 打开管理导航 | Out-Null
    AssertJs 'document.querySelector("dialog").open && document.querySelector("dialog").contains(document.activeElement)'
    Browser screenshot (Join-Path $outputDir 'admin-layout-mobile-menu.png') | Out-Null
    Browser press Escape | Out-Null
    AssertJs '!document.querySelector("dialog").open && document.activeElement.getAttribute("aria-label") === "打开管理导航"'
    Browser find role button click --name 打开管理导航 | Out-Null
    Browser find role link click --name 用户管理 | Out-Null
    AssertJs '!document.querySelector("dialog").open && location.pathname === "/admin/users"'
    Browser find role button click --name 打开管理导航 | Out-Null
    Browser set viewport 1440 1000 | Out-Null
    AssertJs '!document.querySelector("dialog").open'

    Browser open "$BaseUrl/admin/unknown-page" | Out-Null
    Browser wait --text 页面未找到 | Out-Null
    AssertJs '!!document.querySelector(".admin-sidebar") && !document.querySelector(".admin-users-page")'
    Browser find role link click --name 返回用户管理 | Out-Null
    Browser wait --text alice@example.test | Out-Null
    Browser find role button click --name 退出管理后台 | Out-Null
    Browser wait --url '**/admin/login' | Out-Null
    AssertJs '!localStorage.getItem("imagehub.admin.token") && !document.querySelector(".admin-sidebar")'
    Write-Output 'PASS: nested routes, active navigation, search/page restoration, desktop/mobile layout, dialog focus/Escape/resize, unknown page and logout.'
} finally {
    Browser close | Out-Null
}
