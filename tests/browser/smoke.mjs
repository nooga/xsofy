// Pre-deploy browser smoke gate.
//
// Boots the built WASM bundle in headless Chromium and asserts:
//   1. #app becomes visible within --timeout
//   2. The deterministic title-screen sentinel "Press any key" appears in
//      the rendered terminal text within --title-timeout
//   3. Zero console.error / no uncaught exceptions during the window
//
// Runs its own static server with COOP/COEP headers so SharedArrayBuffer
// works directly (no service-worker dance needed in CI).
//
// Empirically validated against xsofy@78e2350 (passes ~5.3s) and
// xsofy@ffebbb3 (consistently fails — "Press any key" never renders).

import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';

const { values: args } = parseArgs({
  options: {
    dir: { type: 'string', default: 'dist' },
    port: { type: 'string', default: '8765' },
    timeout: { type: 'string', default: '10000' },
    'title-timeout': { type: 'string', default: '10000' },
    sentinel: { type: 'string', default: 'Press any key' },
    screenshot: { type: 'string', default: 'smoke-failure.png' },
  },
});

const port = parseInt(args.port);
const timeout = parseInt(args.timeout);
const titleTimeout = parseInt(args['title-timeout']);
const serveDir = path.resolve(args.dir);

const mimeTypes = {
  '.html': 'text/html', '.js': 'application/javascript', '.json': 'application/json',
  '.css': 'text/css', '.wasm': 'application/wasm', '.png': 'image/png',
};
const server = http.createServer((req, res) => {
  res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
  res.setHeader('Cross-Origin-Embedder-Policy', 'require-corp');
  let urlPath = req.url.split('?')[0];
  if (urlPath.endsWith('/')) urlPath += 'index.html';
  fs.readFile(path.join(serveDir, urlPath), (err, data) => {
    if (err) { res.writeHead(404); res.end(); return; }
    res.setHeader('Content-Type', mimeTypes[path.extname(urlPath)] || 'application/octet-stream');
    res.end(data);
  });
});
await new Promise(r => server.listen(port, '127.0.0.1', r));

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();

const consoleErrs = [];
const exceptions = [];
page.on('console', msg => {
  if (msg.type() === 'error') {
    consoleErrs.push(`[${msg.type()}] ${msg.text()}`);
  }
});
page.on('pageerror', err => exceptions.push(err.message));

const start = Date.now();
let bootMs = -1, sentinelMs = -1, waitErr = null;

try {
  await page.goto(`http://127.0.0.1:${port}/`, { timeout });
  await page.waitForSelector('#app', { state: 'visible', timeout });
  bootMs = Date.now() - start;

  await page.waitForFunction(
    (sentinel) => {
      const el = document.querySelector('.xterm-rows');
      return el && el.textContent.includes(sentinel);
    },
    args.sentinel,
    { timeout: titleTimeout, polling: 200 },
  );
  sentinelMs = Date.now() - start;
} catch (err) {
  waitErr = err;
}

const failures = [];
if (waitErr) failures.push(`wait failed: ${waitErr.message.split('\n')[0]}`);
if (exceptions.length) failures.push(`${exceptions.length} uncaught exception(s)`);
if (consoleErrs.length) failures.push(`${consoleErrs.length} console error(s)`);

if (failures.length === 0) {
  console.log(`OK: #app at ${bootMs}ms, "${args.sentinel}" at ${sentinelMs}ms`);
  await browser.close();
  server.close();
  process.exit(0);
}

if (args.screenshot) {
  try { await page.screenshot({ path: args.screenshot }); console.error(`wrote ${args.screenshot}`); }
  catch { /* ignore */ }
}
for (const f of failures) console.error(`FAIL: ${f}`);
for (const e of exceptions) console.error(`EXCEPTION: ${e}`);
for (const e of consoleErrs) console.error(`CONSOLE: ${e}`);

await browser.close();
server.close();
process.exit(1);
