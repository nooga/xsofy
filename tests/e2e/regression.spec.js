const { test, expect } = require('@playwright/test');
const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

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

// Wait for the child to actually release the port before the test resolves —
// a bare srv.kill() can leave 8123 in TIME_WAIT and flake the next test/retry.
function stopServer(srv) {
  return new Promise((resolve) => { srv.on('exit', () => resolve()); srv.kill(); });
}

// Collect console.error / pageerror so each test can assert console-clean, not
// just the first boot test.
function watchConsole(page) {
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(String(e)));
  return errors;
}

// Golden title for seed 12345 (computed natively from the word lists). Seeing
// it render in-browser proves the whole determinism chain: ?seed= reached the
// game via go.env, AND wasm-xxh3 == native-xxh3 (a divergence in either would
// produce a different title).
const TITLE = 'Chasms of Infinity';

function waitForTermText(page, needle, timeout = 30000) {
  return page.waitForFunction(
    (t) => { const el = document.querySelector('.xterm-rows'); return !!el && el.textContent.includes(t); },
    needle, { timeout, polling: 200 });
}

test('seed 12345 reproduces the title (proves ?seed bridge + wasm xxh3 parity)', async ({ page }) => {
  const srv = serve();
  const errors = watchConsole(page);
  try {
    await waitForServer();
    await page.goto('/?seed=12345');
    expect(await page.evaluate(() => self.crossOriginIsolated)).toBe(true);
    await expect(page.locator('#terminal')).toBeVisible({ timeout: 20000 });
    await waitForTermText(page, TITLE);
    expect(errors, errors.join('\n')).toEqual([]);
  } finally { await stopServer(srv); }
});

// Replay code for seed 12345 + [:wait :wait :down :wait] (xsofy.replay/encode).
// Regenerate with: lg -e '(require (quote [xsofy.replay :as r])) (println (r/encode 12345 [...]))'
const REPLAY_CODE = 'AQAAAAAAADA5AgR3YWl0BGRvd24AAAADAAIBAQAB';

test('?replay= plays back the run (decode + animated playback + xxh3 parity)', async ({ page }) => {
  const srv = serve();
  const errors = watchConsole(page);
  try {
    await waitForServer();
    await page.goto('/?replay=' + encodeURIComponent(REPLAY_CODE));
    expect(await page.evaluate(() => self.crossOriginIsolated)).toBe(true);
    await expect(page.locator('#terminal')).toBeVisible({ timeout: 20000 });
    // Playback skips the title screen and renders the game; the HUD seed line
    // proves the code decoded to seed 12345 and the run was rebuilt + rendered.
    await waitForTermText(page, 'Seed 12345');
    expect(errors, errors.join('\n')).toEqual([]);
  } finally { await stopServer(srv); }
});
