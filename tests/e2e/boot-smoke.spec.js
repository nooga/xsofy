const { test, expect } = require('@playwright/test');
const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

// Header-served (COOP/COEP) so crossOriginIsolated is true and the wasm runs in
// worker mode with SharedArrayBuffer — same approach main's smoke.mjs uses in CI
// (the no-header service-worker bootstrap reloads the page and is flaky to drive,
// so it's intentionally not exercised here).
function serve() {
  return spawn(process.execPath, [path.join(__dirname, 'static-server.js')], {
    env: { ...process.env, COI: '1', PORT: '8123',
           DIST: process.env.DIST || path.join(__dirname, 'dist') },
    stdio: 'pipe',
  });
}

async function waitForServer(port = 8123, timeout = 5000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    try {
      await new Promise((resolve, reject) => {
        http.get(`http://localhost:${port}/`, (res) => { res.destroy(); resolve(); }).on('error', reject);
      });
      return;
    } catch (e) { await new Promise(r => setTimeout(r, 100)); }
  }
  throw new Error(`Server did not start within ${timeout}ms`);
}

// xterm.js renders into .xterm-rows; its text is in textContent (NOT #terminal
// innerText, which comes back empty).
function termText(page) {
  return page.evaluate(() => {
    const el = document.querySelector('.xterm-rows');
    return el ? el.textContent : '';
  });
}
function waitForTermText(page, needle, timeout = 30000) {
  return page.waitForFunction(
    (s) => { const el = document.querySelector('.xterm-rows'); return !!el && el.textContent.includes(s); },
    needle, { timeout, polling: 200 });
}

// The title text renders from frame 0, so it's present even if the animation
// loop is frozen. (The "Press any key" subtitle is frame-30-gated — see below.)
const TITLE_FRAGMENT = 'Chasms';   // seed-independent: every title is "X of Y"; this seed yields "Chasms of Infinity"

test('worker-mode boot: cross-origin isolated, renders the title, console-clean', async ({ page }) => {
  const srv = serve();
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push(String(e)));
  try {
    await waitForServer();
    await page.goto('/?seed=12345');
    expect(await page.evaluate(() => self.crossOriginIsolated)).toBe(true);
    await expect(page.locator('#terminal')).toBeVisible({ timeout: 20000 });
    await waitForTermText(page, TITLE_FRAGMENT);
    expect(await termText(page)).not.toContain('Interactive input requires a local server');
    expect(errors, errors.join('\n')).toEqual([]);
  } finally { srv.kill(); }
});

// Animation gate. The "Press any key" subtitle only renders once the title
// animation reaches frame > 30. On builds with the WASM title-poll deadlock
// (nooga/xsofy#61: make-key-poller's (go (term/read-key)) hits Atomics.wait,
// which blocks the whole worker and freezes the animation loop at frame 0), the
// subtitle never paints. This asserts the animation runs — un-fixme once #61
// (or its fix) is on the base branch.
test.fixme('title animation runs (subtitle paints + frames change) — pending nooga/xsofy#61', async ({ page }) => {
  const srv = serve();
  try {
    await waitForServer();
    await page.goto('/?seed=12345');
    await expect(page.locator('#terminal')).toBeVisible({ timeout: 20000 });
    await waitForTermText(page, 'Press any key');
    const a = await page.locator('#terminal').screenshot();
    await page.waitForTimeout(700);
    const b = await page.locator('#terminal').screenshot();
    expect(Buffer.compare(a, b)).not.toBe(0);
  } finally { srv.kill(); }
});
