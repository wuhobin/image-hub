param([string]$BaseUrl = 'http://127.0.0.1:9000')
$ErrorActionPreference = 'Stop'

# Delay /auth/me in an isolated browser; no real tokens, accounts or external services.
function Browser {
    $result = & agent-browser --session imagehub-session-check @args
    if ($LASTEXITCODE -ne 0) { throw "Browser command failed: $args" }
    return ($result -join "`n")
}
function EvalJs([string]$Source) {
    Browser eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Source)))
}
function AssertJs([string]$Source) {
    if ((EvalJs $Source) -ne 'true') { throw "Session assertion failed: $Source" }
}
$outputDir = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$initFile = Join-Path $outputDir 'session-init.js'
@'
const mode = new URL(location.href).searchParams.get('sessionCheck');
if (mode) {
  mode === 'guest' ? localStorage.removeItem('imagehub.token') : localStorage.setItem('imagehub.token', 'qa-placeholder');
  window.sessionCheck = { waiting: mode !== 'guest', flashed: false, placeholderSeen: false };
  const originalFetch = window.fetch;
  window.fetch = (url, options) => String(url).endsWith('/api/auth/me')
    ? new Promise(resolve => setTimeout(() => {
        window.sessionCheck.waiting = false;
        resolve(new Response(JSON.stringify(mode === 'valid'
          ? { code: 200, data: { username: 'Refresh QA' } }
          : { code: 401, message: 'Session expired' }), { headers: { 'Content-Type': 'application/json' } }));
      }, 1000))
    : originalFetch(url, options);
  new MutationObserver(() => {
    if (window.sessionCheck.waiting && document.querySelector('.session-placeholder')) window.sessionCheck.placeholderSeen = true;
    if (window.sessionCheck.waiting && document.querySelector('.header-actions a[href="/login"], .header-actions a[href="/register"]')) {
      window.sessionCheck.flashed = true;
    }
  }).observe(document, { childList: true, subtree: true });
}
'@ | Set-Content -LiteralPath $initFile -Encoding UTF8

try {
    # Start outside a captured pipeline: the Windows browser daemon inherits stdout.
    & agent-browser --session imagehub-session-check --init-script $initFile open
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the test browser' }
    foreach ($mode in @('valid', 'expired', 'guest')) {
        Browser open "$BaseUrl/?sessionCheck=$mode" | Out-Null
        Browser wait '.header-actions' | Out-Null
        if ($mode -eq 'guest') {
            AssertJs '!!document.querySelector(".header-actions .login-link") && !document.querySelector(".session-placeholder")'
        } else {
            Browser wait 1300 | Out-Null
            # Inspect again after a full refresh; the init script runs before React.
            Browser reload | Out-Null
            Browser wait 1300 | Out-Null
            AssertJs '!window.sessionCheck.flashed && window.sessionCheck.placeholderSeen'
            if ($mode -eq 'valid') {
                Browser wait '.user-name' | Out-Null
                AssertJs '!window.sessionCheck.flashed && document.querySelector(".user-name").textContent.includes("Refresh QA") && !document.querySelector(".header-actions .login-link")'
            } else {
                Browser wait '.header-actions .login-link' | Out-Null
                AssertJs '!window.sessionCheck.flashed && !localStorage.getItem("imagehub.token") && !document.querySelector(".session-placeholder")'
            }
        }
    }
    Write-Output 'PASS: refresh restores valid sessions without guest controls; expired and anonymous sessions show login correctly.'
} finally {
    Browser close | Out-Null
}
