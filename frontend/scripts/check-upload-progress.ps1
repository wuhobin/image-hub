param([string]$BaseUrl = 'http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'

# Isolated browser with mocked callbacks; no real accounts or uploads.
try {
    & agent-browser --session imagehub-progress-check open $BaseUrl
    if ($LASTEXITCODE -ne 0) { throw 'Could not open the test browser' }
    $source = @'
(async () => {
  const check = (condition, message) => { if (!condition) throw new Error(message); };
  const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
  const until = async predicate => {
    for (let i = 0; i < 150; i++) { if (predicate()) return; await wait(20); }
    throw new Error('Timed out waiting for UI');
  };
  const records = [], revoked = new Set();
  const revokeObjectURL = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = url => { revoked.add(url); revokeObjectURL(url); };
  window.fetch = (url, options) => {
    check(!options?.method || options.method === 'GET', 'Navigation must not mutate server data');
    const path = String(url);
    const data = path.endsWith('/auth/me') ? {username: 'Progress QA'}
      : path.endsWith('/images/stats') ? {totalCount: records.length, totalBytes: 1024}
      : {records: [...records], total: records.length, current: 1, size: 24, pages: 1};
    return Promise.resolve(new Response(JSON.stringify({code: 200, data})));
  };
  let request;
  const NativeXHR = window.XMLHttpRequest;
  window.XMLHttpRequest = class extends NativeXHR {
    send(body) { this.file = body.get('file'); request = this; }
  };
  localStorage.setItem('imagehub.token', 'qa-local-only');
  window.dispatchEvent(new StorageEvent('storage', {key: 'imagehub.token'}));
  await until(() => document.querySelector('.user-name'));
  document.querySelector('.motion-button').click();
  await until(() => document.querySelector('.starfield canvas'));
  const starCanvas = document.querySelector('.starfield canvas');
  const gl = starCanvas.getContext('webgl2');
  let starPixels;
  const drawElements = gl.drawElements.bind(gl);
  gl.drawElements = (...args) => {
    drawElements(...args);
    if (gl.getParameter(gl.FRAMEBUFFER_BINDING) === null) {
      starPixels = new Uint8Array(160 * 120 * 4);
      gl.readPixels(10, gl.drawingBufferHeight - 140, 160, 120, gl.RGBA, gl.UNSIGNED_BYTE, starPixels);
    }
  };
  const captureStars = async () => {
    starPixels = null;
    window.dispatchEvent(new Event('resize'));
    await until(() => starPixels);
    return starPixels;
  };
  const canvas = document.createElement('canvas'); canvas.width = 200; canvas.height = 130;
  const ctx = canvas.getContext('2d'); ctx.fillStyle = '#47658b'; ctx.fillRect(0, 0, 200, 130);
  const blob = await new Promise(resolve => canvas.toBlob(resolve));
  const goHistory = async () => {
    document.querySelector('.navigation a[href="/history"]').click();
    await until(() => document.querySelectorAll('.library-card').length === records.length);
  };
  const goHome = async () => {
    document.querySelector('.brand').click();
    await until(() => document.querySelector('.home'));
  };
  const select = count => {
    const files = new DataTransfer();
    for (let i = 0; i < count; i++) files.items.add(new File([blob], `test-${i}.png`, {type: 'image/png'}));
    const input = document.querySelector('input[type=file]'); input.files = files.files;
    input.dispatchEvent(new Event('change', {bubbles: true}));
  };
  const bar = () => document.querySelector('progress');
  const report = value => request.upload.onprogress(new ProgressEvent('progress', {lengthComputable: true, loaded: value, total: 100}));
  const complete = () => {
    const preview = canvas.toDataURL();
    const record = {id: String(records.length + 1), name: request.file.name, url: preview, preview, size: blob.size, width: 200, height: 130, type: 'PNG', createdAt: new Date().toISOString()};
    records.push(record);
    Object.defineProperties(request, {
      status: {value: 200},
      responseText: {value: JSON.stringify({code: 200, data: record})},
    });
    request.onload(); request.onloadend();
  };
  select(2);
  await until(() => document.querySelectorAll('.selected-image').length === 2);
  document.querySelector('.button-upload').click();
  await until(() => bar());
  report(80); await wait(120);
  const first = bar().value;
  check(first > 0 && first < 40, 'Progress should interpolate, not jump to 40');
  report(90); await wait(80);
  check(bar().value > first && bar().value < 45, 'Retarget from the visible value without resetting');
  check(document.querySelector('.upload-progress-heading > span:last-child').textContent === `${Math.floor(bar().value)}%`, 'Number and bar must match');
  const starsBefore = await captureStars();
  check(starsBefore.some((value, index) => index % 4 !== 3 && value > 30), 'Probe must contain visible stars');
  const canvasHeightBefore = starCanvas.height;
  const timeOrigin = performance.timeOrigin;
  const previous = request;
  complete(); await until(() => request !== previous);
  const starsAfter = await captureStars();
  check(starCanvas.height > canvasHeightBefore, 'Results should extend the background');
  check(starCanvas === document.querySelector('.starfield canvas') && performance.timeOrigin === timeOrigin, 'Upload must not remount the canvas or reload the page');
  const starDifference = starsAfter.reduce((sum, value, index) => sum + Math.abs(value - starsBefore[index]), 0) / starsAfter.length;
  check(starDifference < 1, `Stars must remain anchored when results appear; difference: ${starDifference}`);
  report(100); await until(() => bar().value === 99);
  check(document.querySelectorAll('progress').length === 1, 'Only one total bar');
  complete(); await until(() => bar().value === 100);
  check(document.querySelectorAll('.image-done').length === 2 && document.querySelector('.upload-progress-heading').textContent.includes('2/2'), 'Keep the completed bar visible');
  const completedPreviews = [...document.querySelectorAll('.selected-preview img')].map(img => img.src);
  await goHistory();
  check(completedPreviews.every(url => revoked.has(url)), 'Release completed local previews');
  await goHome();
  check(!document.querySelector('.selection-panel, .upload-results, progress'), 'Return home with completed results cleared');

  select(2); await until(() => document.querySelectorAll('.selected-image').length === 2);
  const readyPreviews = [...document.querySelectorAll('.selected-preview img')].map(img => img.src);
  await goHistory(); await goHome();
  check(document.querySelectorAll('.selected-image').length === 2 && readyPreviews.every(url => !revoked.has(url)), 'Preserve unuploaded files across routes');
  document.querySelector('.button-upload').click(); await until(() => bar());
  const firstRequest = request;
  complete(); await until(() => request !== firstRequest);
  await goHistory(); await goHome();
  check(document.querySelectorAll('.selected-image').length === 2 && document.querySelector('.image-done'), 'Keep complete batch while uploading');
  await goHistory();
  request.onerror(); request.onloadend(); await wait(100);
  await goHome();
  check(document.querySelectorAll('.selected-image').length === 1 && document.querySelector('.field-error') && !document.querySelector('.upload-results'), 'Preserve failure and clean background completion');
  check(revoked.has(readyPreviews[0]) && !revoked.has(readyPreviews[1]), 'Release only completed previews');
  document.querySelector('.button-upload').click(); await until(() => bar());
  const matchMedia = window.matchMedia.bind(window);
  window.matchMedia = query => query === '(prefers-reduced-motion: reduce)' ? {matches: true} : matchMedia(query);
  report(60); await until(() => bar().value === 60);
  check(!document.querySelector('.upload-progress-heading').textContent.includes('100%'), 'Reduced motion must not invent completion');
  window.matchMedia = matchMedia;
  report(90); await wait(80);
  const detached = bar();
  localStorage.removeItem('imagehub.token');
  window.dispatchEvent(new StorageEvent('storage', {key: 'imagehub.token'}));
  await until(() => !bar());
  const stopped = detached.value;
  await wait(750);
  check(detached.value === stopped, 'Unmount must stop the tween');
  select(1); await until(() => document.querySelector('.selected-image'));
  document.querySelector('.login-link').click();
  await until(() => document.querySelector('.pending-notice'));
  await goHome();
  check(document.querySelectorAll('.selected-image').length === 1, 'Preserve guest selection across login page');
  return 'PASS: stable starfield pixels, smooth progress, completed results cleanup, intact history, ready/failed/active queues, guest selection and unmount cleanup';
})()
'@
    & agent-browser --session imagehub-progress-check eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($source)))
    if ($LASTEXITCODE -ne 0) { throw 'Upload progress check failed' }
} finally {
    & agent-browser --session imagehub-progress-check close
}
