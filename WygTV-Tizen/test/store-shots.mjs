// Screenshots for Samsung's TV app store: the built app (www/) at exactly 1920×1080, with the demo playlist
// (docs/demo.m3u) and a sample guide, so they show only free content. Saved in store/. Run: npm run store-shots
// (needs Playwright with Chromium). Video can't play in a browser, so nothing is playing in them.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const www = path.join(root, 'www');
const out = path.join(root, 'store');
fs.mkdirSync(out, { recursive: true });

// The demo playlist, as published; the guide gives each demo channel a plausible evening.
const M3U = fs.readFileSync(path.join(root, '..', 'docs', 'demo.m3u'), 'utf8');
const SHOWS = {
  'demo.live': ['Live Test Pattern', 'Signal Check', 'Overnight Test Card', 'Morning Signal'],
  'demo.bipbop': ['Stream Lab', 'Encoding Hour', 'Bit Rate Ladder', 'Codec Corner'],
  'demo.bbb': ['Big Buck Bunny', 'Making of: Bunny', 'Open Movie Night', 'Animation Workshop'],
  'demo.sintel': ['Sintel', 'Dragons of Blender', 'Open Movie Night', 'Scoring Sintel'],
  'demo.tos': ['Tears of Steel', 'Visual Effects Today', 'Behind the Scenes', 'Sci-Fi Shorts'],
};
const xt = (t) => new Date(t).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';
const t0 = Math.floor(Date.now() / 3600e3) * 3600e3 - 3600e3;
const GUIDE = '<?xml version="1.0"?>\n<tv>\n' + Object.entries(SHOWS).map(([id, list]) =>
  `<channel id="${id}"><display-name>${id}</display-name></channel>\n` + list.map((title, k) =>
    `<programme start="${xt(t0 + k * 90 * 60e3)}" stop="${xt(t0 + (k + 1) * 90 * 60e3)}" channel="${id}"><title>${title}</title><desc>Free to watch: a public test stream or a Creative Commons film from the Blender Foundation.</desc></programme>`).join('\n')).join('\n') + '\n</tv>\n';

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg', '.xml': 'text/xml' };
const server = http.createServer((q, r) => {
  const u = new URL(q.url, 'http://x');
  if (u.pathname === '/demo.m3u') { r.writeHead(200, { 'Content-Type': 'audio/x-mpegurl', 'Access-Control-Allow-Origin': '*' }); r.end(M3U); return; }
  if (u.pathname === '/guide.xml') { r.writeHead(200, { 'Content-Type': 'text/xml', 'Access-Control-Allow-Origin': '*' }); r.end(GUIDE); return; }
  const f = path.join(www, decodeURIComponent(u.pathname === '/' ? '/index.html' : u.pathname));
  if (!f.startsWith(www) || !fs.existsSync(f)) { r.writeHead(404); r.end(); return; }
  r.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream' });
  fs.createReadStream(f).pipe(r);
});
await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
const base = `http://127.0.0.1:${server.address().port}/`;

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await page.addInitScript((b) => {
    window.tizen = { application: { getAppInfo: () => ({ version: '1.0.0' }), getCurrentApplication: () => ({ exit() { } }) } };
    if (!localStorage.getItem('wyg.settings')) localStorage.setItem('wyg.settings', JSON.stringify({ playlists: [{ id: 'demo', type: 'm3u', name: 'WygTV demo', url: b + 'demo.m3u', epgUrl: b + 'guide.xml' }] }));
  }, base);
  await page.goto(base);
  await page.waitForSelector('#screen[data-screen="home"]');
  await page.evaluate(() => new Promise((r) => { const off = W.data.on('loaded', () => { off(); r(); }); setTimeout(r, 8000); }));
  await page.evaluate(async () => { await W.guide.ensureAll(); });
  const shot = async (name, go, wait = 1500) => {
    await page.evaluate(go);
    await page.waitForTimeout(wait);
    await page.screenshot({ path: path.join(out, name + '.png') });
    console.log('store/' + name + '.png');
  };
  await shot('1-home', () => W.home());
  await shot('2-live-tv', () => { W.go('live'); setTimeout(() => { const c = document.querySelectorAll('.chan')[2]; if (c) c.focus(); }, 400); }, 2000);
  await shot('3-tv-guide', () => W.go('guide'), 2500);
  await shot('4-movies', () => W.go('vod', { kind: 'vod' }), 2000);
  await shot('5-settings', () => W.go('settings', { section: 'playlists' }));
  // Every screenshot must be exactly 1920×1080 (PNG header: width and height at bytes 16–23).
  for (const f of fs.readdirSync(out).filter((n) => n.endsWith('.png'))) {
    const b = fs.readFileSync(path.join(out, f));
    if (b.readUInt32BE(16) !== 1920 || b.readUInt32BE(20) !== 1080) throw new Error(f + ' is not 1920×1080');
  }
} finally {
  await browser.close();
  server.close();
}
