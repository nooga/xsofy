const { test, expect } = require('@playwright/test');
const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

// Responsive coverage for the touch shell: phone portrait plus three landscape
// heights down to iPhone-SE class. Each viewport boots the bundle with touch
// emulation, cycles the layer switcher (config, a·z, back to play), and
// asserts the shell never overflows its box — the compact-landscape clipping
// regression rode exactly this gap (rail scrollHeight > viewport at 667x375).

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

function stopServer(srv) {
  return new Promise((resolve) => { srv.on('exit', () => resolve()); srv.kill(); });
}

function watchConsole(page) {
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(String(e)));
  return errors;
}

// No scroll anywhere: the document doesn't scroll on either axis, and the
// shell (the rail, in landscape) fits its own box. 1px of tolerance for
// subpixel rounding on fractional dvh/percentage splits.
async function expectNoOverflow(page, label) {
  const m = await page.evaluate(() => {
    const doc = document.scrollingElement;
    const shell = document.getElementById('xsofy-shell');
    return {
      docX: doc.scrollWidth - doc.clientWidth,
      docY: doc.scrollHeight - doc.clientHeight,
      shellY: shell.scrollHeight - shell.clientHeight,
    };
  });
  expect(m.docX, `${label}: document scrolls horizontally`).toBeLessThanOrEqual(1);
  expect(m.docY, `${label}: document scrolls vertically`).toBeLessThanOrEqual(1);
  expect(m.shellY, `${label}: shell overflows its box`).toBeLessThanOrEqual(1);
}

const VIEWPORTS = [
  { name: 'portrait 390x844', width: 390, height: 844 },
  { name: 'landscape 844x390', width: 844, height: 390 },
  { name: 'landscape 667x375', width: 667, height: 375 },
  { name: 'landscape 568x320', width: 568, height: 320 },
];

for (const vp of VIEWPORTS) {
  test(`touch shell fits and switches panes at ${vp.name}`, async ({ browser }) => {
    const srv = serve();
    // hasTouch makes (pointer: coarse) match, which sets body.force-touch —
    // the same path a real phone takes; without it landscape hides the rail.
    const ctx = await browser.newContext({
      viewport: { width: vp.width, height: vp.height },
      hasTouch: true, isMobile: true,
    });
    const page = await ctx.newPage();
    const errors = watchConsole(page);
    try {
      await waitForServer();
      await page.goto('http://localhost:8123/?seed=12345');
      await expect(page.locator('#app')).toBeVisible({ timeout: 20000 });
      await expect(page.locator('#xs-touch button').first()).toBeVisible({ timeout: 20000 });
      await expectNoOverflow(page, `${vp.name} play`);

      // a·z layer (portrait: pane swap; landscape: bottom sheet over the rail)
      await page.click('#xs-seg-az');
      await expect(page.locator('#xs-seg-az')).toHaveClass(/active/);
      await expectNoOverflow(page, `${vp.name} a·z`);

      // config layer, then back to the play base
      await page.click('#xs-seg-set');
      await expect(page.locator('#xs-seg-set')).toHaveClass(/active/);
      await expectNoOverflow(page, `${vp.name} config`);
      await page.click('#xs-seg-set');
      await expect(page.locator('#xs-seg-set')).not.toHaveClass(/active/);
      await expectNoOverflow(page, `${vp.name} play again`);

      expect(errors, errors.join('\n')).toEqual([]);
    } finally {
      await ctx.close();
      await stopServer(srv);
    }
  });
}
