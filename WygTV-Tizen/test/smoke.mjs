// Opens the built TV app (www/) in Chromium at 1920×1080 with a stand-in for Samsung's `tizen` object, and checks
// that Home, Live TV and the remote's Back key work, both normally and with the Chromium 69 fallbacks switched on
// (?legacy=1). Screenshots go to smoke/. Run: npm run smoke (needs Playwright with Chromium).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import zlib from 'node:zlib';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const www = path.join(root, 'www');
const shots = path.join(root, 'smoke');
fs.mkdirSync(shots, { recursive: true });

const M3U = ['#EXTM3U', ...Array.from({ length: 12 }, (_, i) => `#EXTINF:-1 tvg-id="ch${i}" group-title="${i < 6 ? 'News' : 'Sports'}",Channel ${i + 1}\nhttp://127.0.0.1:1/ch${i}.m3u8`)].join('\n');
// A guide for the demo channels, starting an hour ago, served gzip-compressed (as most guides are).
const xmltvTime = (t) => new Date(t).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';
const t0 = Math.floor(Date.now() / 3600e3) * 3600e3 - 3600e3;
const GUIDE = zlib.gzipSync('<?xml version="1.0"?>\n<tv>\n' + Array.from({ length: 12 }, (_, i) => `<channel id="ch${i}"><display-name>Channel ${i + 1}</display-name></channel>\n` +
  Array.from({ length: 6 }, (_, k) => `<programme start="${xmltvTime(t0 + k * 3600e3)}" stop="${xmltvTime(t0 + (k + 1) * 3600e3)}" channel="ch${i}"><title>Show ${k} on ${i + 1}</title></programme>`).join('\n')).join('\n') + '\n</tv>\n');
const BACKUP = JSON.stringify({ app: 'WygDesktop', version: 1, settings: { userAgent: 'from-a-link', playlists: [] } });
const hits = { guide: 0 };
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg', '.xml': 'text/xml' };
const server = http.createServer((q, r) => {
  const u = new URL(q.url, 'http://x');
  if (u.pathname === '/guide.xml.gz') { hits.guide++; r.writeHead(200, { 'Content-Type': 'application/gzip', 'Access-Control-Allow-Origin': '*' }); r.end(GUIDE); return; }
  if (u.pathname === '/backup.json') { r.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }); r.end(BACKUP); return; }
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

async function open(query, playlist, page) {
  page = page || await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push(m.text()); });
  await page.addInitScript(([pl, b]) => {
    window.tizen = { application: { getAppInfo: () => ({ version: '1.0.42' }), getCurrentApplication: () => ({ exit() { window.__exited = true; } }) } };
    // Only the first time: reloads keep what the app saved, as on a TV.
    if (pl && !localStorage.getItem('wyg.settings')) localStorage.setItem('wyg.settings', JSON.stringify({ playlists: [{ id: 'demo', type: 'm3u', name: 'Demo', url: b + 'demo.m3u', epgUrl: b + 'guide.xml.gz' }] }));
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
  // ---------------------------------------------------------------- phase 2: gzip guides kept between starts, backups
  {
    const { page, errors } = await open('', true);
    const guideNow = () => page.evaluate(async () => {
      for (let i = 0; i < 40 && !Object.values(W.guide.status || {}).some((s) => s.channels); i++) await new Promise((r) => setTimeout(r, 250));
      const g = await W.guide.get(W.data.allChannels());
      const now = W.guide.nowNext(g['demo|0'] || []).now;
      return { status: W.guide.status, now: now ? now[2] : '' };
    });
    const before = hits.guide;
    const g1 = await guideNow();
    check(/^Show 1 on 1$/.test(g1.now), `gzip guide streams in and gives what's on now ("${g1.now}")`);
    check(hits.guide === before + 1, 'the guide was downloaded once');
    await page.waitForTimeout(500); // let IndexedDB finish writing
    await page.reload();
    await page.waitForSelector('#screen[data-screen="home"]');
    const g2 = await guideNow();
    check(/^Show 1 on 1$/.test(g2.now) && hits.guide === before + 1, `after a restart the guide comes from the TV's storage, not the network (downloads: ${hits.guide - before})`);

    // Save a backup on the TV, change a setting, restore it.
    const saved = await page.evaluate(async () => W.api.file.save('wygtv-tv-backup.json', W.backupText()));
    check(/this TV/.test(saved), `backup saved on the TV ("${saved}")`);
    await page.evaluate(() => { W.settings.userAgent = 'changed'; W.saveSettings(); W.restoreBackup(); });
    await page.waitForSelector('.menu-item');
    const items = await page.$$eval('.menu-item', (els) => els.map((e) => e.textContent));
    check(items.length === 2 && /Saved on this TV/.test(items[0]) && /web link/.test(items[1]), `Restore lists the TV's backup and a link (${JSON.stringify(items)})`);
    await page.screenshot({ path: path.join(shots, 'restore-menu.png') });
    await Promise.all([page.waitForNavigation(), page.keyboard.press('Enter')]);
    await page.waitForSelector('#screen[data-screen="home"]');
    check(await page.evaluate(() => !W.settings.userAgent), 'restoring the TV backup brought the old settings back');

    // Restore from a link (e.g. a desktop backup put online).
    await page.evaluate(() => W.restoreBackup());
    await page.waitForSelector('.menu-item');
    await page.click('.menu-item >> text=web link');
    await page.waitForSelector('.dialog.form input');
    await page.fill('.dialog.form input', base + 'backup.json');
    await page.screenshot({ path: path.join(shots, 'restore-link.png') });
    await Promise.all([page.waitForNavigation(), page.click('.dialog.form .button.primary')]);
    await page.waitForSelector('#screen[data-screen="home"]');
    check(await page.evaluate(() => W.settings.userAgent === 'from-a-link'), 'restoring from a link brought its settings in');

    // Up to 5 backups are kept.
    await page.evaluate(async () => { for (let i = 0; i < 7; i++) await W.api.file.save('b.json', '{"settings":{}}'); });
    const count = await page.evaluate(() => new Promise((res) => { const r = indexedDB.open('wygtv'); r.onsuccess = () => { const q = r.result.transaction('backups').objectStore('backups').count(); q.onsuccess = () => res(q.result); }; }));
    check(count === 5, `only the newest 5 backups are kept (${count})`);
    check(errors.length === 0, `phase 2: no script errors ${errors.length ? JSON.stringify(errors.slice(0, 3)) : ''}`);
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
