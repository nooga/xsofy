const { test, expect } = require('@playwright/test');
const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

function serve(coi) {
  return spawn(process.execPath, [path.join(__dirname, 'static-server.js')], {
    env: { ...process.env, COI: coi ? '1' : '0', PORT: '8123',
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

async function bootAssertions(page) {
  await expect(page.locator('#terminal')).toBeVisible({ timeout: 20000 });
  const text = await page.locator('#terminal').innerText();
  expect(text.trim().length).toBeGreaterThan(0);
  expect(text).not.toContain('Interactive input requires a local server');
  const a = await page.locator('#terminal').screenshot();
  await page.waitForTimeout(700);
  const b = await page.locator('#terminal').screenshot();
  expect(Buffer.compare(a, b)).not.toBe(0);
}

test('worker-mode boot (header-served, crossOriginIsolated)', async ({ page }) => {
  const srv = serve(true);
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push(String(e)));
  try {
    await waitForServer();
    await page.goto('/');
    expect(await page.evaluate(() => self.crossOriginIsolated)).toBe(true);
    await bootAssertions(page);
    expect(errors, errors.join('\n')).toEqual([]);
  } finally { srv.kill(); }
});

test('Pages-faithful boot (no headers → coi-serviceworker bootstrap)', async ({ page }) => {
  const srv = serve(false);
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', e => errors.push(String(e)));
  try {
    await waitForServer();
    await page.goto('/');
    await expect.poll(() => page.evaluate(() => self.crossOriginIsolated),
                      { timeout: 30000 }).toBe(true);
    await bootAssertions(page);
    const retry = await page.evaluate(() =>
      Number(new URLSearchParams(location.search).get('_lg_coi_retry') || '0'));
    expect(retry).toBeLessThan(6);
    expect(errors, errors.join('\n')).toEqual([]);
  } finally { srv.kill(); }
});
