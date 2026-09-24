param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'
function B {
    if ($args[0] -in @('click','wait','open')) { Write-Host ('QA: ' + ($args -join ' ')) }
    # Windows PowerShell 向原生命令传参会移除双引号；CSS 属性值改用单引号。
    if ($args[0] -in @('click','fill','wait','select') -and $args[1] -ne '--fn') { $args[1] = $args[1].Replace('"', "'") }
    if ($args[0] -eq 'wait' -and $args[1] -eq '--fn') {
        $args[2] = "eval(new TextDecoder().decode(Uint8Array.from(atob('" + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($args[2])) + "'), c=>c.charCodeAt(0))))"
    }
    if ($args[0] -eq 'click') { & agent-browser --session imagehub-templates-0924 scrollintoview $args[1] | Out-Null }
    $result = & agent-browser --session imagehub-templates-0924 @args
    if ($LASTEXITCODE -ne 0) { throw "Browser command failed: $args" }
    return ($result -join [Environment]::NewLine)
}
function E([string]$script) { B eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script))) }
function A([string]$script) { if ((E $script) -ne 'true') { throw "Assertion failed: $script" } }
$qaDirectory = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force $qaDirectory | Out-Null
$init = Join-Path $PSScriptRoot 'template-fixture.js'
try {
    & agent-browser --session imagehub-templates-0924 --init-script $init open "$BaseUrl/templates"
    B set viewport 1440 1000 | Out-Null
    B open "$BaseUrl/templates" | Out-Null
    B wait '.template-card' | Out-Null
    A 'document.querySelectorAll(".template-card").length===9 && !localStorage.getItem("imagehub.token")'
    B screenshot (Join-Path $qaDirectory 'templates-desktop.png') --full | Out-Null
    B click '.template-categories button:nth-child(4)' | Out-Null
    B wait --fn 'document.querySelectorAll(".template-card").length===3' | Out-Null
    B click '.template-categories button:first-child' | Out-Null
    B fill '#template-search' '小红书' | Out-Null
    B click '.template-search button' | Out-Null
    B wait --fn 'document.querySelectorAll(".template-card").length===1' | Out-Null
    B click '.template-subfilters > button' | Out-Null
    B wait --fn 'document.querySelectorAll(".template-card").length===9' | Out-Null
    B click '.template-card:first-child' | Out-Null
    B wait '#template-field-subject' | Out-Null
    B fill '#template-field-subject' '戴眼镜的小熊' | Out-Null
    B click '.template-prompt-editor summary' | Out-Null
    B fill '#template-final-prompt' '手动调整：水彩小熊头像' | Out-Null
    B fill '#template-field-subject' '拿着画笔的小熊' | Out-Null
    A 'document.querySelector("#template-final-prompt").value==="手动调整：水彩小熊头像" && !!document.querySelector(".template-notice")'
    B screenshot (Join-Path $qaDirectory 'template-editor-desktop.png') | Out-Null
    foreach ($width in @(390,320)) {
        B set viewport $width 844 | Out-Null
        A 'document.documentElement.scrollWidth<=innerWidth && document.querySelector(".template-dialog").scrollWidth<=document.querySelector(".template-dialog").clientWidth'
        B screenshot (Join-Path $qaDirectory "template-editor-$width.png") | Out-Null
    }
    B set viewport 1440 1000 | Out-Null
    B click '.template-form > button[type=submit], .template-form > button.button-primary' | Out-Null
    B wait '#creation-prompt' | Out-Null
    B wait '.creation-submit[title="登录创作"]' | Out-Null
    A 'document.querySelector("#creation-prompt").value==="手动调整：水彩小熊头像"'
    B wait --fn 'getComputedStyle(document.querySelector(".creation-toolbar")).opacity==="1" && document.getAnimations().every(a=>a.effect?.getTiming().iterations===Infinity || a.playState!=="running")' | Out-Null
    B click '.creation-submit' | Out-Null
    B wait '#username' | Out-Null
    B click '.auth-switch a' | Out-Null
    B wait '#email' | Out-Null
    B click '.auth-switch a' | Out-Null
    B wait '#username' | Out-Null
    B fill '#username' 'template-test' | Out-Null
    B fill '#password' 'test-only-password' | Out-Null
    B click '.auth-submit' | Out-Null
    B wait '#creation-model' | Out-Null
    B wait --fn 'document.querySelector("#creation-model")?.value==="1"' | Out-Null
    A 'document.querySelector("#creation-prompt").value==="手动调整：水彩小熊头像" && document.querySelector(".creation-template-entry").textContent.includes("个性头像")'
    B select '#creation-model' '2' | Out-Null
    B wait --fn 'document.querySelector("#creation-model").value==="2"' | Out-Null
    B select '#creation-quality' 'high' | Out-Null
    B click '.creation-template-entry a' | Out-Null
    B wait '#template-field-subject' | Out-Null
    A 'document.querySelector("#template-field-subject").value==="拿着画笔的小熊"'
    B click '.template-form > button.button-primary' | Out-Null
    B wait --fn 'document.querySelector("#creation-model")?.value==="2" && document.querySelector("#creation-quality")?.value==="high"' | Out-Null
    A '!window.calls.some(c=>c.path==="/api/app/generations" && c.method==="POST")'
    B wait --fn 'getComputedStyle(document.querySelector(".creation-toolbar")).opacity==="1" && document.getAnimations().every(a=>a.effect?.getTiming().iterations===Infinity || a.playState!=="running")' | Out-Null
    B screenshot (Join-Path $qaDirectory 'template-composer.png') | Out-Null
    B click '.navigation a[href="/templates"]' | Out-Null
    B wait '.template-card' | Out-Null
    foreach ($width in @(390,320)) {
        B set viewport $width 844 | Out-Null
        A 'document.documentElement.scrollWidth<=innerWidth'
        B screenshot (Join-Path $qaDirectory "templates-$width.png") --full | Out-Null
    }
    B set viewport 1440 1000 | Out-Null
    E 'localStorage.setItem("imagehub.admin.token","template-admin")' | Out-Null
    B open "$BaseUrl/admin/templates" | Out-Null
    B wait '.template-admin-row' | Out-Null
    B screenshot (Join-Path $qaDirectory 'template-admin-desktop.png') --full | Out-Null
    B click '.template-admin-row:first-child button' | Out-Null
    B wait '.template-editor' | Out-Null
    A 'document.querySelector(".template-editor").getBoundingClientRect().width>=700'
    A 'getComputedStyle(document.querySelector(".template-editor textarea")).borderTopWidth==="0px"'
    B screenshot (Join-Path $qaDirectory 'template-admin-editor.png') | Out-Null
    B fill '.template-editor input[maxlength="80"]' '个性头像已编辑' | Out-Null
    B click '.template-editor button[type=submit], .template-editor .template-admin-actions button.button-primary' | Out-Null
    B wait --fn '!document.querySelector(".template-editor") && document.querySelector(".template-admin-row h2")?.textContent==="个性头像已编辑"' | Out-Null
    B click '.template-admin .admin-page-title button:first-child' | Out-Null
    B wait '.template-term-form' | Out-Null
    B fill '.template-term-form input[maxlength="30"]' '工作创作' | Out-Null
    B click '.template-term-form button.button-primary' | Out-Null
    B wait --fn '[...document.querySelectorAll(".template-term-list span")].some(x=>x.textContent.includes("工作创作"))' | Out-Null
    B press Escape | Out-Null
    B set viewport 390 844 | Out-Null
    A 'document.documentElement.scrollWidth<=innerWidth'
    A '[...document.querySelectorAll(".template-admin-filters select")].every(x=>x.getBoundingClientRect().height<60)'
    B screenshot (Join-Path $qaDirectory 'template-admin-mobile.png') --full | Out-Null
    Write-Output 'Template browser checks passed.'
} catch {
    B snapshot | Write-Output
    B screenshot (Join-Path $qaDirectory 'templates-failure.png') | Out-Null
    throw
} finally {
    B close | Out-Null
}
