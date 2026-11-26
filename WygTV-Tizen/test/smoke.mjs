// Opens the built TV app (www/) in Chromium at 1920×1080 with a stand-in for Samsung's `tizen` object, and checks
// that Home, Live TV and the remote's Back key work, both normally and with the Chromium 69 fallbacks switched on
// (?legacy=1). Screenshots go to smoke/. Run: npm run smoke (needs Playwright with Chromium).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const www = path.join(root, 'www');
const shots = path.join(root, 'smoke');
fs.mkdirSync(shots, { recursive: true });

const M3U = ['#EXTM3U', ...Array.from({ length: 12 }, (_, i) => `#EXTINF:-1 tvg-id="ch${i}" group-title="${i < 6 ? 'News' : 'Sports'}",Channel ${i + 1}\nhttp://127.0.0.1:1/ch${i}.m3u8`)].join('\n');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg', '.xml': 'text/xml' };
const server = http.createServer((q, r) => {
  const u = new URL(q.url, 'http://x');
  if (u.pathname === '/demo.m3u') { r.writeHead(200, { 'Content-Type': 'audio/x-mpegurl', 'Access-Control-Allow-Origin': '*' }); r.end(M3U); return; }
  const f = path.join(www, decodeURIComponent(u.pathname === '/' ? '/index.html' : u.pathname));
  if (!f.startsWith(www) || !fs.existsSync(f)) { r.writeHead(404); r.end(); return; }
  r.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream' });
  fs.createReadStream(f).pipe(r);
});
await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
const base = `http://127.0.0.1:${server.address().port}/`;

const browser = await chromium.launch();
const problems = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) problems.push(what); };

async function open(query, playlist) {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push(m.text()); });
  await page.addInitScript(([pl, b]) => {
    window.tizen = { application: { getAppInfo: () => ({ version: '1.0.42' }), getCurrentApplication: () => ({ exit() { window.__exited = true; } }) } };
    localStorage.clear();
    if (pl) localStorage.setItem('wyg.settings', JSON.stringify({ playlists: [{ id: 'demo', type: 'm3u', name: 'Demo', url: b + 'demo.m3u' }] }));
  }, [playlist, base]);
  await page.goto(base + query);
  await page.waitForSelector('#screen[data-screen="home"]');
  await page.waitForTimeout(1500);
  return { page, errors };
}
const remoteBack = (page) => page.evaluate(() => document.dispatchEvent(new KeyboardEvent('keydown', { keyCode: 10009, bubbles: true })));
const layout = (page) => page.evaluate(() => {
  const box = (sel) => { const e = document.querySelector(sel); if (!e) return null; const r = e.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]; };
  const all = (sel) => [...document.querySelectorAll(sel)].slice(0, 8).map((e) => { const r = e.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]; });
  return { tiles: all('.tile'), chips: all('.chip'), header: all('.header > *'), home: box('.home') };
});

try {
  for (const legacy of [false, true]) {
    const tag = legacy ? 'legacy' : 'modern';
    const { page, errors } = await open(legacy ? '?legacy=1' : '', true);
    const cls = await page.evaluate(() => document.documentElement.className);
    check(/\btizen\b/.test(cls), `${tag}: the bridge marked the page as the TV app`);
    if (legacy) check(/no-flex-gap/.test(cls) && /no-aspect-ratio/.test(cls), `${tag}: the Chromium 69 fallbacks are on (${cls})`);
    else check(!/no-flex-gap|no-aspect-ratio/.test(cls), `${tag}: no fallbacks on a current engine`);
    const hint = await page.textContent('.keys-hint').catch(() => '');
    check(/OK plays/.test(hint), `${tag}: Home shows the remote's key hint`);
    await page.screenshot({ path: path.join(shots, `home-${tag}.png`) });
    const l = await layout(page);
    fs.writeFileSync(path.join(shots, `layout-${tag}.json`), JSON.stringify(l, null, 1));
    check(l.tiles.length > 0 && l.tiles.every((t) => t[3] > 20), `${tag}: Home tiles have a height (${JSON.stringify(l.tiles.slice(0, 2))})`);

    // Remote: Back on Home asks to exit; Back again cancels; OK exits.
    await remoteBack(page);
    await page.waitForTimeout(300);
    check(/Exit WygTV/.test(await page.textContent('#layers').catch(() => '')), `${tag}: Back on Home asks to exit`);
    await page.screenshot({ path: path.join(shots, `exit-${tag}.png`) });
    await remoteBack(page);
    await page.waitForTimeout(300);
    check((await page.$$('#layers > .layer')).length === 0, `${tag}: Back closes the question`);
    await remoteBack(page);
    await page.waitForTimeout(300);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(300);
    check(await page.evaluate(() => window.__exited === true), `${tag}: OK exits the app`);

    // Live TV from the demo playlist, then Back returns Home.
    await page.evaluate(() => W.go('live'));
    await page.waitForTimeout(1200);
    const chans = await page.$$eval('.chan', (els) => els.length);
    check(chans >= 6, `${tag}: Live TV lists the playlist's channels (${chans})`);
    await page.screenshot({ path: path.join(shots, `live-${tag}.png`) });
    await remoteBack(page);
    await page.waitForTimeout(500);
    check(await page.evaluate(() => W.current().name === 'home'), `${tag}: Back from Live TV goes Home`);
    check(errors.length === 0, `${tag}: no script errors ${errors.length ? JSON.stringify(errors.slice(0, 3)) : ''}`);
    await page.close();
  }
  // The fallbacks should look like the real thing: same tile sizes, header items in about the same places.
  const m = JSON.parse(fs.readFileSync(path.join(shots, 'layout-modern.json'))), g = JSON.parse(fs.readFileSync(path.join(shots, 'layout-legacy.json')));
  const near = (a, b, tol) => a.length === b.length && a.every((x, i) => x.every((v, j) => Math.abs(v - b[i][j]) <= tol));
  check(near(m.tiles, g.tiles, 3), 'legacy Home tiles match the modern layout within 3 px');
  check(near(m.header, g.header, 3), 'legacy header items match the modern layout within 3 px');
} finally {
  await browser.close();
  server.close();
}
console.log(problems.length ? `\n${problems.length} problem(s)` : '\nall smoke checks passed');
process.exit(problems.length ? 1 : 0);
