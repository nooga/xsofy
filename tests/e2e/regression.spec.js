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

// Golden title for seed 12345 (computed natively from the word lists). Seeing
// it render in-browser proves the whole determinism chain: ?seed= reached the
// game via go.env, AND wasm-xxh3 == native-xxh3 (a divergence in either would
// produce a different title).
const TITLE = 'Chasms of Infinity';

test('seed 12345 reproduces the title (proves ?seed bridge + wasm xxh3 parity)', async ({ page }) => {
  const srv = serve();
  try {
    await waitForServer();
    await page.goto('/?seed=12345');
    expect(await page.evaluate(() => self.crossOriginIsolated)).toBe(true);
    await expect(page.locator('#terminal')).toBeVisible({ timeout: 20000 });
    await page.waitForFunction(
      (t) => {
        const el = document.querySelector('.xterm-rows');
        return !!el && el.textContent.includes(t);
      },
      TITLE, { timeout: 30000, polling: 200 });
  } finally { srv.kill(); }
});
