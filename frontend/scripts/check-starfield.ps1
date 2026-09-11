param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'

# Requires the running dev server and agent-browser CLI. Exercise the actual GPU shader.
function Browser {
    $result = & agent-browser --session imagehub-starfield-check @args
    if ($LASTEXITCODE -ne 0) { throw "Browser command failed: $args" }
    return ($result -join "`n")
}
$outputDir = Join-Path $PSScriptRoot '../.qa'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
try {
    Browser open $BaseUrl | Out-Null
    Browser set viewport 1440 1000 | Out-Null
    Browser wait 5000 | Out-Null
    $ready = Browser eval "!!document.querySelector('.starfield canvas') && !document.querySelector('.webgl-unavailable')"
    if ($ready -ne 'true') { throw 'Starfield canvas unavailable' }
    # Inspect the matrix sent to the real star draw call, excluding idle rotation.
    $probe = @'
const gl = document.querySelector('.starfield canvas').getContext('webgl2');
const draw = gl.drawArrays;
const drawPost = gl.drawElements;
window.uploadBounds = JSON.stringify(document.querySelector('.upload-shell').getBoundingClientRect());
gl.drawArrays = function(...args) {
    if (args[0] === gl.POINTS) {
        const program = gl.getParameter(gl.CURRENT_PROGRAM);
        window.starView = Array.from(gl.getUniform(program, gl.getUniformLocation(program, 'modelViewMatrix')));
    }
    return draw.apply(this, args);
};
gl.drawElements = function(...args) {
    const program = gl.getParameter(gl.CURRENT_PROGRAM);
    const expansion = gl.getUniformLocation(program, 'uExpansion');
    if (expansion !== null) window.lensExpansion = gl.getUniform(program, expansion);
    return drawPost.apply(this, args);
};
'@
    Browser eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($probe))) | Out-Null
    Browser wait 200 | Out-Null
    if ((Browser eval 'window.lensExpansion === 0 && Math.abs(window.starView[12]) < 0.01') -ne 'true') {
        throw 'Idle GSAP controls moved before pointer interaction'
    }
    Browser mouse move 120 360 | Out-Null
    Browser wait 900 | Out-Null
    Browser eval 'window.leftView = window.starView[12]' | Out-Null
    Browser mouse move 1320 360 | Out-Null
    Browser wait 900 | Out-Null
    $follows = Browser eval "window.starView[12] - window.leftView > 1.5 && JSON.stringify(document.querySelector('.upload-shell').getBoundingClientRect()) === window.uploadBounds"
    if ($follows -ne 'true') { throw 'Star parallax did not follow the pointer or moved the upload box' }
    Browser screenshot (Join-Path $outputDir 'lens-pointer-right.png') | Out-Null
    $label = Browser eval "document.querySelector('.drop-zone strong').getBoundingClientRect().toJSON()" | ConvertFrom-Json
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new((Join-Path $outputDir 'lens-pointer-right.png'))
    $brightPixels = 0
    try {
        for ($y = [int]$label.top; $y -lt [int]$label.bottom; $y++) {
            for ($x = [int]$label.left; $x -lt [int]$label.right; $x++) {
                if ($bitmap.GetPixel($x, $y).GetBrightness() -gt 0.3) { $brightPixels++ }
            }
        }
    } finally { $bitmap.Dispose() }
    if ($brightPixels -lt 20) { throw 'Upload label disappeared during WebGL compositing' }
    Browser mouse move 720 35 | Out-Null
    Browser wait 1000 | Out-Null
    if ((Browser eval 'Math.abs(window.starView[12]) < 0.08') -ne 'true') { throw 'Parallax did not return to center' }
    Browser mouse move 720 450 | Out-Null
    Browser wait 800 | Out-Null
    $hovered = Browser eval "window.lensExpansion > 0.95 && new DOMMatrixReadOnly(getComputedStyle(document.querySelector('.upload-symbol')).transform).m42 < -4"
    if ($hovered -ne 'true') { throw 'GSAP did not coordinate the lens expansion and upload icon' }
    Browser screenshot (Join-Path $outputDir 'lens-hover-expanded.png') | Out-Null
    Browser focus '.drop-zone' | Out-Null
    Browser mouse move 720 300 | Out-Null
    Browser wait 1100 | Out-Null
    $contracted = Browser eval "window.lensExpansion < 0.04 && JSON.stringify(document.querySelector('.upload-shell').getBoundingClientRect()) === window.uploadBounds"
    if ($contracted -ne 'true') { throw 'Pointer leave did not contract the star field or changed the upload box' }
    Browser screenshot (Join-Path $outputDir 'lens-hover-contracted.png') | Out-Null
    # Reverse before either tween finishes: the shader must respond from its
    # current position without jumping or waiting for the previous transition.
    $reverse = @'
(async () => {
    const shell = document.querySelector('.upload-shell');
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const hover = type => shell.dispatchEvent(new PointerEvent(type, { pointerType: 'mouse' }));
    hover('pointerenter');
    await wait(250);
    const outward = window.lensExpansion;
    hover('pointerleave');
    const continuous = window.lensExpansion === outward;
    await wait(160);
    const inward = window.lensExpansion;
    hover('pointerenter');
    await wait(160);
    const resumed = window.lensExpansion;
    hover('pointerleave');
    return continuous && outward > 0.05 && inward < outward - 0.01 && resumed > inward + 0.01;
})()
'@
    if ((Browser eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($reverse)))) -ne 'true') {
        throw 'Rapid hover reversal stalled or jumped'
    }
    Browser wait 1100 | Out-Null
    Browser eval 'document.activeElement.blur()' | Out-Null
    Browser click '.hero-bottom button' | Out-Null
    Browser mouse move 120 360 | Out-Null
    Browser wait 500 | Out-Null
    Browser screenshot (Join-Path $outputDir 'lens-paused-a.png') | Out-Null
    Browser mouse move 1320 360 | Out-Null
    Browser wait 500 | Out-Null
    Browser screenshot (Join-Path $outputDir 'lens-paused-b.png') | Out-Null
    if ((Get-FileHash (Join-Path $outputDir 'lens-paused-a.png')).Hash -ne
        (Get-FileHash (Join-Path $outputDir 'lens-paused-b.png')).Hash) {
        throw 'Paused starfield is still changing'
    }
    Browser set viewport 390 844 | Out-Null
    Browser wait 500 | Out-Null
    $fits = Browser eval "document.documentElement.scrollWidth <= innerWidth && getComputedStyle(document.querySelector('.upload-shell')).borderTopLeftRadius === '24px'"
    if ($fits -ne 'true') { throw 'Mobile layout or lens radius changed' }
    Browser screenshot (Join-Path $outputDir 'lens-mobile-check.png') | Out-Null
    Browser set media dark reduced-motion | Out-Null
    Browser wait 200 | Out-Null
    $reduced = Browser eval "window.lensExpansion === 0 && [...document.querySelectorAll('.hero-heading h1, .upload-shell, .hero-bottom')].every(e => +getComputedStyle(e).opacity === 1 && getComputedStyle(e).visibility === 'visible') && Math.abs(new DOMMatrixReadOnly(getComputedStyle(document.querySelector('.upload-symbol')).transform).m42) < 0.01"
    if ($reduced -ne 'true') { throw 'Reduced motion left GSAP content hidden or hover motion active' }
    $console = Browser console
    $errors = Browser errors
    if ($console -match '(?i)\[error\]|shader error|validate_status|context_lost' -or $errors.Trim()) {
        throw "Browser errors: $console $errors"
    }
    Write-Output 'PASS: hover expansion/contraction and rapid reversal, pointer parallax, recentering, paused frames, reduced motion, shader, and mobile layout; screenshots in .qa/'
} finally {
    Browser close | Out-Null
}
