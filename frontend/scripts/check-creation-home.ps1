param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'

function Browser {
    $result = & agent-browser --session imagehub-creation-home-check @args
    if ($LASTEXITCODE -ne 0) { throw "Browser command failed: $args" }
    return ($result -join "`n")
}
function AssertJs([string]$Source) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Source))
    if ((Browser eval -b $encoded) -ne 'true') { throw "Assertion failed: $Source" }
}
$outputDir = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$initFile = Join-Path $outputDir 'creation-home-init.js'
@'
// Isolated browser with fake authentication; intercept every API request.
if (!sessionStorage.getItem('creationHomeCheck')) {
  localStorage.removeItem('imagehub.token');
  sessionStorage.setItem('creationHomeCheck', '1');
}
window.creationHomeRequests = [];
window.creationTask = JSON.parse(sessionStorage.getItem('qa-creation-task') || 'null');
const originalFetch = window.fetch;
window.fetch = (url, options = {}) => {
  const path = new URL(String(url), location.href).pathname;
  if (!path.startsWith('/api/')) return originalFetch(url, options);
  window.creationHomeRequests.push({ path, method: options.method || 'GET' });
  let data;
  if (path.endsWith('/auth/login')) data = { token: 'qa-placeholder', user: { username: 'guest' } };
  else if (path.endsWith('/auth/me')) data = { username: 'guest' };
  else if (path.endsWith('/auth/logout')) data = null;
  else if (path.endsWith('/generations/models')) data = [{ id: 1, name: 'QA image model', sizes: ['1024x1024', '2048x2048', '2880x2880', '1536x1024', '2160x1440', '3456x2304', '1024x1536', '1440x2160', '2304x3456', '1280x720', '2560x1440', '3840x2160', '720x1280', '1440x2560', '2160x3840', '1024x768', '2048x1536', '3200x2400', '768x1024', '1536x2048', '2400x3200', '1344x576', '2016x864', '3808x1632'], defaultSize: '1024x1024', qualities: ['medium', 'high'], defaultQuality: 'medium' }, { id: 2, name: 'Custom size model', sizes: ['1536x864'], defaultSize: '1536x864', qualities: ['medium'], defaultQuality: 'medium' }];
  else if (path.endsWith('/generations/active')) data = ['QUEUED', 'GENERATING', 'SAVING'].includes(window.creationTask?.status) ? window.creationTask : null;
  else if (path.endsWith('/generations') && options.method === 'POST') {
    window.creationRequest = JSON.parse(options.body);
    const pixel = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aL1kAAAAASUVORK5CYII=';
    data = window.creationTask = { ...window.creationRequest, id: 'qa-task', status: 'SUCCEEDED', modelName: 'QA image model', errorMessage: null, createTime: '2026-09-15 12:00:00', image: { id: 'qa-image', name: 'QA image', url: pixel, preview: pixel, width: 1, height: 1, size: 68 } };
    sessionStorage.setItem('qa-creation-task', JSON.stringify(data));
  }
  else if (path.endsWith('/generations')) data = { records: window.creationTask ? [window.creationTask] : [], total: window.creationTask ? 1 : 0, current: 1, size: 12, pages: 1 };
  else if (path.endsWith('/images/quota')) data = { total: 100, used: 0, reserved: 0, remaining: 100 };
  else return Promise.resolve(new Response(JSON.stringify({ code: 500, message: 'Unexpected test API: ' + path })));
  return Promise.resolve(new Response(JSON.stringify({ code: 200, data }), { headers: { 'Content-Type': 'application/json' } }));
};
'@ | Set-Content -LiteralPath $initFile -Encoding UTF8

try {
    & agent-browser --session imagehub-creation-home-check --init-script $initFile open
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the test browser' }
    Browser open "$BaseUrl/" | Out-Null
    Browser wait '#creation-prompt' | Out-Null
    AssertJs 'location.pathname === "/" && document.querySelector(".navigation a").textContent === "AI 创作" && window.creationHomeRequests.length === 0'
    AssertJs '!document.querySelector(".creation-result") && document.querySelectorAll(".creation-workspace select").length === 4 && document.querySelector(".creation-workspace").contains(document.querySelector(".creation-submit"))'
    Browser fill '#creation-prompt' '海边书店，午后的阳光，胶片质感' | Out-Null
    Browser focus '.creation-submit' | Out-Null
    Browser press Enter | Out-Null
    Browser wait '#username' | Out-Null
    Browser fill '#username' 'guest' | Out-Null
    Browser fill '#password' 'qa-password-only' | Out-Null
    Browser click '.auth-submit' | Out-Null
    Browser wait '#creation-model' | Out-Null
    AssertJs 'location.pathname === "/" && document.querySelector("#creation-prompt").value === "海边书店，午后的阳光，胶片质感" && !history.state.usr'
    AssertJs 'Array.from(document.querySelector("#creation-size").options, option => option.textContent).join(",") === "1:1,3:2,2:3,16:9,9:16,4:3,3:4,21:9"'
    Browser select '#creation-quality' 'high' | Out-Null
    AssertJs 'document.querySelector("#creation-resolution").value === "1024x1024" && Array.from(document.querySelector("#creation-resolution").options).every(option => !option.disabled)'
    foreach ($case in @('1:1|1024x1024', '1:1|2048x2048', '1:1|2880x2880', '3:2|1536x1024', '3:2|2160x1440', '3:2|3456x2304', '2:3|1024x1536', '2:3|1440x2160', '2:3|2304x3456', '16:9|1280x720', '16:9|2560x1440', '16:9|3840x2160', '9:16|720x1280', '9:16|1440x2560', '9:16|2160x3840', '4:3|1024x768', '4:3|2048x1536', '4:3|3200x2400', '3:4|768x1024', '3:4|1536x2048', '3:4|2400x3200', '21:9|1344x576', '21:9|2016x864', '21:9|3808x1632')) {
        $ratio, $size = $case.Split('|')
        Browser select '#creation-size' $ratio | Out-Null
        Browser select '#creation-resolution' $size | Out-Null
        Browser wait --fn "!document.querySelector('.creation-submit').disabled" | Out-Null
        Browser focus '.creation-submit' | Out-Null
        Browser press Enter | Out-Null
        Browser wait --fn "window.creationRequest?.size === '$size'" | Out-Null
        Browser wait --fn "!document.querySelector('.creation-submit').disabled" | Out-Null
        AssertJs "window.creationRequest.size === '$size' && window.creationRequest.quality === 'high'"
    }
    Browser select '#creation-size' '1:1' | Out-Null
    AssertJs 'document.querySelector("#creation-resolution").value === "2880x2880" && document.querySelector("#creation-resolution").selectedOptions[0].textContent.includes("4K")'
    Browser select '#creation-size' '16:9' | Out-Null
    AssertJs 'document.querySelector("#creation-resolution").value === "3840x2160"'
    Browser select '#creation-model' '2' | Out-Null
    AssertJs 'document.querySelector("#creation-resolution").value === "1536x864" && document.querySelector("#creation-resolution").selectedOptions[0].textContent.includes("自定义")'
    Browser select '#creation-model' '1' | Out-Null
    AssertJs 'document.querySelector("#creation-resolution").value === "1024x1024"'
    foreach ($width in @(320, 390, 1440)) {
        Browser set viewport $width 1000 | Out-Null
        AssertJs 'document.documentElement.scrollWidth <= innerWidth && Array.from(document.querySelectorAll(".creation-options select")).every(el => el.getBoundingClientRect().right <= innerWidth)'
        if ($width -eq 390) { Browser screenshot (Join-Path $outputDir 'creation-resolutions-mobile.png') | Out-Null }
    }
    Browser screenshot (Join-Path $outputDir 'creation-resolutions-desktop.png') | Out-Null
    AssertJs '!document.querySelector("main > .creation-result") && !!document.querySelector(".creation-history-item img")'
    Browser reload | Out-Null
    Browser wait '.creation-history-item' | Out-Null
    AssertJs '!document.querySelector(".creation-result") && !document.querySelector("dialog[open]")'
    Browser focus '.creation-history-item' | Out-Null
    Browser press Enter | Out-Null
    Browser wait '.creation-detail-modal[open]' | Out-Null
    AssertJs '!!document.querySelector(".creation-detail-modal .creation-image img") && document.querySelector(".creation-detail-badges").textContent.includes("21:9") && document.querySelector(".creation-detail-parameters").textContent.includes("3808×1632") && document.querySelector(".creation-detail-parameters").textContent.includes("4K") && !document.querySelector("main > .creation-result")'
    Browser set viewport 390 844 | Out-Null
    AssertJs 'document.querySelector(".creation-detail-modal").scrollWidth <= document.querySelector(".creation-detail-modal").clientWidth'
    Browser screenshot (Join-Path $outputDir 'creation-detail-mobile.png') | Out-Null
    Browser set viewport 1440 1000 | Out-Null
    Browser press Escape | Out-Null
    foreach ($status in @('GENERATING', 'FAILED')) {
        AssertJs "(() => { window.creationTask.status = '$status'; window.creationTask.image = null; sessionStorage.setItem('qa-creation-task', JSON.stringify(window.creationTask)); return true; })()"
        Browser reload | Out-Null
        Browser wait '.creation-history-item' | Out-Null
        AssertJs '!document.querySelector(".creation-result") && !document.querySelector("dialog[open]")'
        AssertJs "document.getElementById('creation-submit-status').textContent === ('$status' === 'GENERATING' ? '任务进行中' : '生成图片')"
        if ($status -eq 'GENERATING') {
            AssertJs '!!document.querySelector(".creation-history-item.is-running") && getComputedStyle(document.querySelector(".is-running .creation-history-thumb"), "::after").animationName === "creation-orbit"'
            Browser screenshot (Join-Path $outputDir 'creation-generating-desktop.png') --full | Out-Null
            Browser set viewport 390 844 | Out-Null
            AssertJs 'document.documentElement.scrollWidth <= innerWidth'
            Browser screenshot (Join-Path $outputDir 'creation-generating-mobile.png') --full | Out-Null
            Browser set viewport 1440 1000 | Out-Null
        }
        Browser focus '.creation-history-item' | Out-Null
        Browser press Enter | Out-Null
        Browser wait '.creation-detail-modal[open]' | Out-Null
        AssertJs '!!document.querySelector(".creation-detail-modal .creation-placeholder") && !document.querySelector("main > .creation-result")'
        if ($status -eq 'FAILED') { AssertJs '!document.querySelector(".creation-detail-modal .creation-result-actions")' }
        Browser press Escape | Out-Null
        AssertJs '!document.querySelector(".creation-result") && !document.querySelector("dialog[open]")'
    }
    Browser focus '.user-name' | Out-Null
    Browser press Enter | Out-Null
    Browser focus '.user-logout' | Out-Null
    Browser press Enter | Out-Null
    Browser wait '.header-actions .login-link' | Out-Null
    AssertJs 'document.querySelector("#creation-prompt").value === "" && document.querySelector("#creation-model").disabled && !document.querySelector(".creation-result") && window.creationHomeRequests.filter(r => r.path.endsWith("/generations") && r.method === "POST").length === 0'
    Browser open "$BaseUrl/create" | Out-Null
    Browser wait '#creation-prompt' | Out-Null
    AssertJs 'location.pathname === "/"'
    Browser click '.navigation a:nth-child(2)' | Out-Null
    Browser wait '.upload-shell' | Out-Null
    AssertJs 'location.pathname === "/upload" && document.querySelector(".navigation a[aria-current=page]").textContent === "上传图片"'
    Browser focus 'a.button-upload' | Out-Null
    Browser press Enter | Out-Null
    Browser wait '#username' | Out-Null
    Browser fill '#username' 'guest' | Out-Null
    Browser fill '#password' 'qa-password-only' | Out-Null
    Browser click '.auth-submit' | Out-Null
    Browser wait '.upload-shell' | Out-Null
    AssertJs 'location.pathname === "/upload" && !!document.querySelector(".user-name")'
    Browser focus '.user-name' | Out-Null
    Browser press Enter | Out-Null
    Browser focus '.user-logout' | Out-Null
    Browser press Enter | Out-Null
    Browser wait '.header-actions .login-link' | Out-Null
    Browser click '.brand' | Out-Null
    Browser wait '#creation-prompt' | Out-Null
    Browser set viewport 390 844 | Out-Null
    AssertJs 'document.documentElement.scrollWidth <= innerWidth'
    AssertJs '(() => { const bg = document.querySelector(".creation-page > .ambient-layer"); const box = bg.getBoundingClientRect(); return getComputedStyle(bg).pointerEvents === "none" && box.width === innerWidth && box.height <= Math.max(760, innerHeight); })()'
    Browser screenshot (Join-Path $outputDir 'creation-home-mobile.png') --full | Out-Null
    Browser set viewport 1440 1000 | Out-Null
    Browser wait '.creation-page canvas' | Out-Null
    AssertJs 'document.querySelectorAll(".creation-page canvas").length === 1 && !document.querySelector(".webgl-unavailable")'
    Browser screenshot (Join-Path $outputDir 'creation-home-desktop.png') --full | Out-Null
    Write-Output 'PASS: AI homepage, 24 calculated ratio/resolution submissions, retained tiers and custom models, refresh without auto-opening tasks, history detail dialogs, active-task recovery, login and mobile layout.'
} catch {
    Browser snapshot | Write-Output
    throw
} finally {
    Browser close | Out-Null
}
