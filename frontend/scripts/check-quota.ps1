param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'
function Browser {
    $result = & agent-browser --session imagehub-quota-check @args
    if ($LASTEXITCODE -ne 0) { throw "Browser command failed: $args" }
    return ($result -join "`n")
}
function EvalJs([string]$Source) {
    Browser eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Source)))
}
function AssertJs([string]$Source) {
    if ((EvalJs $Source) -ne 'true') { throw "Quota assertion failed: $Source" }
}
$outputDir = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$initFile = Join-Path $outputDir 'quota-init.js'
@'
// No real accounts or network mutations. Control responses to reproduce slow and out-of-order requests.
localStorage.setItem('imagehub.token', 'qa-quota-token');
window.quotaReplies = [];
window.taskReplies = [];
window.holdTasks = true;
window.replyQuota = (remaining, code = 200, reserved = 0) => {
  const result = { code, data: { total: 100, used: 100 - remaining - reserved, reserved, remaining } };
  window.quotaReplies.splice(0).forEach(reply => reply(result));
};
const response = data => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
const originalFetch = window.fetch;
window.fetch = (url, options = {}) => {
  const path = new URL(String(url), location.href).pathname;
  if (!path.startsWith('/api/')) return originalFetch(url, options);
  if (path.endsWith('/images/quota')) return new Promise(resolve => window.quotaReplies.push(result => resolve(response(result))));
  let data;
  if (path.endsWith('/auth/me')) data = { username: 'Quota QA' };
  else if (path.endsWith('/auth/logout')) data = null;
  else if (path.endsWith('/generations/models')) data = [{ id: 1, name: 'QA image model', sizes: ['1024x1024'], defaultSize: '1024x1024', qualities: ['medium'], defaultQuality: 'medium', pointsCost: 1 }];
  else if (path.endsWith('/generations/active')) data = null;
  else if (path.endsWith('/generations')) data = { records: [], total: 0, current: 1, size: 12, pages: 0 };
  else throw new Error('Unexpected test API: ' + path);
  if (window.holdTasks && (path.endsWith('/generations') || path.endsWith('/generations/active'))) {
    return new Promise(resolve => window.taskReplies.push(() => resolve(response({ code: 200, data }))));
  }
  return Promise.resolve(response({ code: 200, data }));
};
'@ | Set-Content -LiteralPath $initFile -Encoding UTF8

try {
    & agent-browser --session imagehub-quota-check --init-script $initFile open
    if ($LASTEXITCODE -ne 0) { throw 'Could not start quota test browser' }
    foreach ($path in @('/', '/upload')) {
        Browser open "$BaseUrl$path" | Out-Null
        Browser wait '.upload-quota .quota-placeholder' | Out-Null
        EvalJs 'window.replyQuota(0, 500)' | Out-Null
        Browser wait '.upload-quota .text-button' | Out-Null
        AssertJs '!document.querySelector(".quota-number") && !document.querySelector(".upload-quota .quota-placeholder")'
        Browser focus '.upload-quota .text-button' | Out-Null
        Browser press Enter | Out-Null
        Browser wait --fn 'window.quotaReplies.length > 0' | Out-Null
        EvalJs 'window.quotaTop = document.querySelector(".upload-quota").getBoundingClientRect().top; window.toolbarHeight = document.querySelector(".upload-toolbar").getBoundingClientRect().height; window.replyQuota(10)' | Out-Null
        Browser wait '.quota-number' | Out-Null
        AssertJs 'document.querySelector(".quota-number").textContent === "10" && Math.abs(document.querySelector(".upload-quota").getBoundingClientRect().top - window.quotaTop) < 1 && Math.abs(document.querySelector(".upload-toolbar").getBoundingClientRect().height - window.toolbarHeight) < 1'
        if ($path -eq '/') {
            AssertJs 'window.taskReplies.length > 0 && !!document.querySelector(".quota-number")'
            EvalJs 'window.holdTasks = false; window.taskReplies.splice(0).forEach(reply => reply())' | Out-Null
            Browser fill '#creation-prompt' 'Quota regression prompt' | Out-Null
        }
        EvalJs 'window.quotaNode = document.querySelector(".quota-content"); window.dispatchEvent(new Event("focus"))' | Out-Null
        Browser wait --fn 'window.quotaReplies.length > 0' | Out-Null
        AssertJs 'document.querySelector(".quota-content") === window.quotaNode && !document.querySelector(".upload-quota .quota-placeholder") && document.querySelector(".quota-number").textContent === "10"'
        EvalJs 'window.replyQuota(0, 500)' | Out-Null
        Browser wait '.upload-quota .text-button' | Out-Null
        AssertJs 'document.querySelector(".quota-content") === window.quotaNode && document.querySelector(".quota-number").textContent === "10" && !document.querySelector(".upload-quota .quota-placeholder")'
        if ($path -eq '/') { AssertJs 'document.querySelector(".creation-submit").disabled' }
        Browser focus '.upload-quota .text-button' | Out-Null
        Browser press Enter | Out-Null
        Browser wait --fn 'window.quotaReplies.length > 0' | Out-Null
        EvalJs 'window.replyQuota(9, 200, 1)' | Out-Null
        Browser wait --fn 'document.querySelector(".quota-number").textContent === "9"' | Out-Null
        AssertJs 'document.querySelector(".quota-content") === window.quotaNode && !document.querySelector(".upload-quota .text-button") && !!document.querySelector(".quota-reserved")'
        EvalJs 'window.dispatchEvent(new Event("focus"))' | Out-Null
        Browser wait --fn 'window.quotaReplies.length > 0' | Out-Null
        EvalJs 'window.oldQuotaReplies = window.quotaReplies.splice(0); window.dispatchEvent(new Event("focus"))' | Out-Null
        Browser wait --fn 'window.quotaReplies.length > 0' | Out-Null
        EvalJs 'window.replyQuota(5)' | Out-Null
        Browser wait --fn 'document.querySelector(".quota-number").textContent === "5"' | Out-Null
        EvalJs 'window.oldQuotaReplies.forEach(reply => reply({ code: 200, data: { total: 100, used: 80, reserved: 0, remaining: 20 } }))' | Out-Null
        AssertJs 'document.querySelector(".quota-number").textContent === "5"'
        EvalJs 'window.dispatchEvent(new Event("focus"))' | Out-Null
        Browser wait --fn 'window.quotaReplies.length > 0' | Out-Null
        EvalJs 'window.replyQuota(0)' | Out-Null
        Browser wait '.quota-exhausted' | Out-Null
        AssertJs 'document.querySelectorAll(".quota-number").length === 2 && document.querySelector(".quota-number").textContent === "0"'
        Browser set viewport 390 844 | Out-Null
        AssertJs 'document.documentElement.scrollWidth <= innerWidth'
        $shot = if ($path -eq '/') { 'creation-quota-mobile.png' } else { 'upload-quota-mobile.png' }
        Browser screenshot (Join-Path $outputDir $shot) --full | Out-Null
        Browser set viewport 320 740 | Out-Null
        AssertJs 'document.documentElement.scrollWidth <= innerWidth'
        Browser set viewport 1440 1000 | Out-Null
    }
    EvalJs 'window.dispatchEvent(new Event("focus"))' | Out-Null
    Browser wait --fn 'window.quotaReplies.length > 0' | Out-Null
    Browser focus '.user-name' | Out-Null
    Browser press Enter | Out-Null
    Browser focus '.user-logout' | Out-Null
    Browser press Enter | Out-Null
    Browser wait '.header-actions .login-link' | Out-Null
    EvalJs 'window.replyQuota(100)' | Out-Null
    AssertJs '!document.querySelector(".quota-number") && !localStorage.getItem("imagehub.token")'
    Write-Output 'PASS: both pages share stable quota layout; independent loading, background failure/retry, reservation, stale responses, zero quota and mobile layout.'
} catch {
    Browser snapshot | Write-Output
    throw
} finally { Browser close | Out-Null }
