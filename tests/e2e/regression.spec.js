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
        const req = http.get(`http://localhost:${port}/`, (res) => {
          res.destroy();
          resolve();
        }).on('error', reject);
      });
      return;
    } catch (e) {
      await new Promise(r => setTimeout(r, 100));
    }
  }
  throw new Error(`Server did not start within ${timeout}ms`);
}

// Golden values for seed 12345 (computed natively from the word lists).
const TITLE = 'Chasms of Infinity';

test('seed 12345 reproduces the same run (proves ?seed bridge + wasm xxh3 parity)', async ({ page }) => {
  const srv = serve();
  try {
    await waitForServer();
    await page.goto('/?seed=12345');
    await expect(page.locator('#terminal')).toBeVisible({ timeout: 20000 });
    await expect.poll(async () =>
      (await page.locator('#terminal').innerText()).includes(TITLE),
      { timeout: 20000 }).toBe(true);
    // Start the game (any key), then the quest line is reachable in-game.
    await page.locator('#terminal').click();
    await page.keyboard.press('Enter');
    await expect.poll(async () =>
      (await page.locator('#terminal').innerText()).includes('Spatula'),
      { timeout: 20000 }).toBe(true);
  } finally { srv.kill(); }
});
