// Serves a dist/ dir. With COI=1, sends COOP/COEP so crossOriginIsolated is
// true (worker mode). Without it, behaves like GitHub Pages (no headers) so
// coi-serviceworker.js must bootstrap isolation.
const http = require('http');
const fs = require('fs');
const path = require('path');

const DIST = process.env.DIST || path.join(__dirname, 'dist');
const PORT = Number(process.env.PORT || 8123);
const COI = process.env.COI === '1';

const TYPES = { '.html': 'text/html', '.js': 'text/javascript' };

http.createServer((req, res) => {
  const urlPath = req.url.split('?')[0];
  const rel = urlPath === '/' ? 'index.html' : urlPath.replace(/^\//, '');
  const file = path.join(DIST, rel);
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404); res.end('not found'); return; }
    const headers = { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' };
    if (COI) {
      headers['Cross-Origin-Opener-Policy'] = 'same-origin';
      headers['Cross-Origin-Embedder-Policy'] = 'require-corp';
    }
    res.writeHead(200, headers);
    res.end(buf);
  });
}).listen(PORT, () => console.log(`serving ${DIST} on :${PORT} COI=${COI}`));
