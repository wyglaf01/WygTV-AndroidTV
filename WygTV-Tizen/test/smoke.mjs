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
const nacl = require('tweetnacl');
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const www = path.join(root, 'www');
const shots = path.join(root, 'smoke');
fs.mkdirSync(shots, { recursive: true });

// Channel numbers 1–12, except two antenna-style sub-channels: 4.1 and 4.2.
const NUMS = ['1', '2', '3', '4.1', '4.2', '6', '7', '8', '9', '10', '11', '12'];
const M3U = ['#EXTM3U', ...Array.from({ length: 12 }, (_, i) => `#EXTINF:-1 tvg-id="ch${i}" tvg-chno="${NUMS[i]}" group-title="${i < 6 ? 'News' : 'Sports'}",Channel ${i + 1}\nhttp://127.0.0.1:1/ch${i}.m3u8`)].join('\n');
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
  // The phone page and its encryption library, as GitHub Pages serves them (docs/).
  if (u.pathname === '/send.html' || u.pathname === '/vendor/nacl-fast.min.js') {
    r.writeHead(200, { 'Content-Type': u.pathname.endsWith('.html') ? 'text/html' : 'text/javascript' });
    r.end(fs.readFileSync(path.join(root, '..', 'docs', u.pathname)));
    return;
  }
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
    // ?avplay=1: a stand-in for Samsung's AVPlay that logs every call and plays pretend streams.
    if (/avplay=1/.test(location.search)) {
      const log = [];
      let st = 'NONE', L = {}, t = 0, timer = 0, url = '';
      const A = {
        log,
        open(u) { url = u; t = 0; st = 'IDLE'; log.push(['open', u]); },
        close() { clearInterval(timer); st = 'NONE'; log.push(['close']); },
        stop() { clearInterval(timer); if (st !== 'NONE') st = 'IDLE'; log.push(['stop']); },
        setListener(l) { L = l; A.L = l; }, setDisplayMethod() { }, setTimeoutForBuffering() { },
        getTotalTrackInfo() {
          return [{ index: 0, type: 'VIDEO', extra_info: '{}' }, { index: 1, type: 'AUDIO', extra_info: '{"language":"eng","fourCC":"AC3"}' },
            { index: 2, type: 'AUDIO', extra_info: '{"language":"spa","fourCC":"AAC"}' }, { index: 3, type: 'TEXT', extra_info: '{"track_lang":"eng"}' }];
        },
        getCurrentStreamInfo() { return [{ index: 0, type: 'VIDEO' }, { index: 1, type: 'AUDIO' }]; },
        setSelectTrack(type, i) { log.push(['track', type, i]); },
        setSilentSubtitle(on) { log.push(['silent', on]); },
        setDisplayRect(x, y, w, h) { log.push(['rect', x, y, w, h]); },
        prepareAsync(ok, bad) { const u = url; setTimeout(() => { if (u !== url) return; if (/fail/.test(u)) bad({ name: 'PLAYER_ERROR_CONNECTION_FAILED' }); else { st = 'READY'; ok(); } }, 50); },
        play() {
          st = 'PLAYING'; log.push(['play']);
          L.onbufferingstart(); setTimeout(() => L.onbufferingcomplete(), 30);
          clearInterval(timer); timer = setInterval(() => { t += 500; L.oncurrentplaytime(t); }, 500);
        },
        pause() { st = 'PAUSED'; clearInterval(timer); log.push(['pause']); },
        getState() { return st; },
        getDuration() { return /movie/.test(url) ? 600000 : 0; },
        seekTo(ms, ok) { t = ms; log.push(['seek', ms]); if (ok) ok(); },
        suspend() { log.push(['suspend']); }, restore() { log.push(['restore']); },
      };
      const productinfo = { getRealModel: () => 'QN55Q80T', getModel: () => '20_KANTSU2E_QTV', getFirmware: () => 'T-KSU2EAKUC-1500.2', isUdPanelSupported: () => true };
      window.webapis = { avplay: A, productinfo };
    }
    // The TV's key service: which keys exist, and which the app registered.
    const SUPPORTED = { 0: 48, 1: 49, 2: 50, 3: 51, 4: 52, 5: 53, 6: 54, 7: 55, 8: 56, 9: 57, Minus: 189, ChannelUp: 427, ChannelDown: 428,
      MediaPlayPause: 10252, MediaPlay: 415, MediaPause: 19, MediaStop: 413, MediaRewind: 412, MediaFastForward: 417, Info: 457, Caption: 10221,
      ColorF0Red: 403, ColorF1Green: 404, ColorF2Yellow: 405, ColorF3Blue: 406, Guide: 458, ChannelList: 10073, Exit: 10182, VolumeUp: 447 };
    window.__registered = [];
    const tvinputdevice = {
      getSupportedKeys: () => Object.entries(SUPPORTED).map(([name, code]) => ({ name, code })),
      registerKey: (n) => { window.__registered.push(n); },
    };
    window.tizen = { tvinputdevice, systeminfo: { getCapability: (k) => (/platform\.version/.test(k) ? '5.5' : '') }, application: { getAppInfo: () => ({ version: '1.0.42' }), getCurrentApplication: () => ({ exit() { window.__exited = true; } }) } };
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
    const before = hits.guide;
    const { page, errors } = await open('', true);
    const guideNow = () => page.evaluate(async () => {
      for (let i = 0; i < 40 && !Object.values(W.guide.status || {}).some((s) => s.channels); i++) await new Promise((r) => setTimeout(r, 250));
      const g = await W.guide.get(W.data.allChannels());
      const now = W.guide.nowNext(g['demo|0'] || []).now;
      return { status: W.guide.status, now: now ? now[2] : '' };
    });
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
    await page.evaluate(() => { W.restoreBackup(); }); // don't wait: it resolves only once a choice is made
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

  // ---------------------------------------------------------------- phase 3: video in AVPlay
  {
    const { page, errors } = await open('?avplay=1', true);
    const log = () => page.evaluate(() => webapis.avplay.log.slice());
    const clearLog = () => page.evaluate(() => { webapis.avplay.log.length = 0; });
    const last = (l, what) => l.filter((e) => e[0] === what).pop();
    const shown = (sel) => page.evaluate((s) => { const e = document.querySelector(s); return !!e && !e.hidden && e.offsetParent !== null; }, sel);

    // Live TV: OK on a channel plays it in the preview box, where AVPlay is told to draw.
    // (Wait for movies and series to finish loading first: that redraws Live TV, which stops a preview.)
    await page.evaluate(() => new Promise((r) => { const off = W.data.on('loaded', () => { off(); r(); }); setTimeout(r, 8000); }));
    await page.evaluate(() => W.go('live'));
    await page.waitForSelector('.chan');
    await page.focus('.chan');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(1500);
    let l = await log();
    const tag = await page.evaluate(() => { const v = document.querySelector('#player .video'); return v ? v.tagName + ' ' + v.getAttribute('type') : 'none'; });
    check(tag === 'OBJECT application/avplayer', `the player uses an AVPlay <object> (${tag})`);
    check(last(l, 'open') && /ch0\.m3u8$/.test(last(l, 'open')[1]) && last(l, 'play'), `OK plays the channel in AVPlay (${JSON.stringify(last(l, 'open'))})`);
    const box = await page.evaluate(() => { const r = document.querySelector('.preview-box').getBoundingClientRect(); return [r.left, r.top, r.width, r.height].map(Math.round); });
    const rect = last(l, 'rect') ? last(l, 'rect').slice(1) : [];
    check(rect.length === 4 && rect.every((v, i) => Math.abs(v - box[i]) <= 3), `AVPlay draws in the preview box (${JSON.stringify(rect)} vs ${JSON.stringify(box)})`);
    check(!(await shown('.player-spinner')), 'the spinner goes once it plays');
    await page.screenshot({ path: path.join(shots, 'avplay-preview.png') });

    // Full screen (what OK on the playing channel does): AVPlay covers the whole TV, and the page behind is hidden.
    await page.evaluate(() => W.player.enterFull());
    await page.waitForTimeout(800);
    l = await log();
    check(JSON.stringify(last(l, 'rect')) === JSON.stringify(['rect', 0, 0, 1920, 1080]), `full screen: AVPlay covers the TV (${JSON.stringify(last(l, 'rect'))})`);
    check(await page.evaluate(() => document.documentElement.classList.contains('av-full') && getComputedStyle(document.getElementById('backdrop')).visibility === 'hidden'), 'full screen: the page behind the picture is hidden');
    await page.screenshot({ path: path.join(shots, 'avplay-full.png') });

    // Down (on-screen bar hidden) changes channel: the old stream is closed before the next opens.
    await page.evaluate(() => { const o = document.querySelector('.osd'); if (o) o.hidden = true; });
    await clearLog();
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(1200);
    l = await log();
    const order = l.map((e) => e[0]).filter((k) => ['stop', 'close', 'open', 'play'].includes(k)).join(' ');
    check(/stop close open play/.test(order) && /ch1\.m3u8$/.test(last(l, 'open')[1]), `channel down: ${order} → ${last(l, 'open') && last(l, 'open')[1].split('/').pop()}`);

    // An antenna channel (needs FFmpeg on a computer) plays straight in AVPlay on the TV.
    await page.evaluate(() => W.player.play({ kind: 'live', key: 'hdhr|4.1', name: 'WNBC', num: '4.1', url: 'http://192.168.1.60:5004/auto/v4.1', convert: 'x264' }, { full: true }));
    await page.waitForTimeout(1000);
    l = await log();
    check(/auto\/v4\.1$/.test(last(l, 'open')[1]) && !(await shown('.player-msg')), 'an antenna channel plays as it is (no "needs converting" message)');

    // A stream AVPlay can't open: a TV message, and no "Open in VLC".
    await page.evaluate(() => W.player.play({ kind: 'live', key: 'x|fail', name: 'Broken', url: 'http://127.0.0.1:1/fail.ts' }, { full: true }));
    await page.waitForTimeout(1200);
    const msg = await page.evaluate(() => (document.querySelector('.player-msg') || {}).textContent || '');
    check(/will not play on this TV/.test(msg) && !/VLC/.test(msg) && /Try again/.test(msg), `a broken stream says so, with Try again and no VLC ("${msg.slice(0, 80)}")`);

    // A movie resumes where it was left: AVPlay seeks once it is ready.
    await clearLog();
    await page.evaluate(() => W.player.play({ kind: 'movie', key: 'm|1', name: 'A movie', url: 'http://127.0.0.1:1/movie.mp4' }, { full: true, resume: 120 }));
    await page.waitForTimeout(1500);
    l = await log();
    check(l.some((e) => e[0] === 'seek' && e[1] === 120000), `a movie resumes at 2:00 (${JSON.stringify(l.filter((e) => e[0] === 'seek'))})`);
    const dur = await page.evaluate(() => document.querySelector('#player .video').duration);
    check(dur === 600, `the movie's length comes from AVPlay (${dur} s)`);
    const osd = await page.$$eval('.osd-btn', (els) => els.map((e) => e.textContent));
    check(!osd.some((t) => /VLC|Window|Volume/.test(t)), `no VLC, Window or Volume buttons on the TV (${JSON.stringify(osd)})`);

    // The TV's Home button: AVPlay lets go of the decoder, then picks up again.
    await page.evaluate(() => {
      let hidden = true;
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
      document.dispatchEvent(new Event('visibilitychange'));
      hidden = false;
      document.dispatchEvent(new Event('visibilitychange'));
    });
    l = await log();
    check(l.some((e) => e[0] === 'suspend') && l.some((e) => e[0] === 'restore'), 'leaving WygTV and coming back suspends and restores AVPlay');

    // Back from full screen: the picture goes back to the Live TV preview box, and the app shows again.
    await clearLog();
    await page.evaluate(() => W.back());
    await page.waitForTimeout(700);
    l = await log();
    const back = last(l, 'rect') ? last(l, 'rect').slice(1) : [];
    check(back.every((v, i) => Math.abs(v - box[i]) <= 3) && !(await page.evaluate(() => document.documentElement.classList.contains('av-full'))),
      `Back from full screen returns the picture to the preview box (${JSON.stringify(back)})`);
    // Back again leaves Live TV, which stops AVPlay.
    await page.evaluate(() => W.back());
    await page.waitForTimeout(500);
    l = await log();
    check(!!last(l, 'close') && (await page.evaluate(() => !W.player.item())), 'leaving Live TV stops AVPlay');
    check(errors.length === 0, `phase 3: no script errors ${errors.length ? JSON.stringify(errors.slice(0, 3)) : ''}`);
    await page.close();
  }

  // ---------------------------------------------------------------- phase 4: the remote, sound tracks and subtitles
  {
    const { page, errors } = await open('?avplay=1', true);
    await page.evaluate(() => new Promise((r) => { const off = W.data.on('loaded', () => { off(); r(); }); setTimeout(r, 8000); }));
    const CODE = { red: 403, green: 404, yellow: 405, blue: 406, chUp: 427, chDown: 428, play: 415, pause: 19, rew: 412, ff: 417, info: 457, cc: 10221, minus: 189, guide: 458 };
    // A remote key as the TV sends it: a keyCode, and a key name the page can't read.
    const press = async (code) => { await page.evaluate((c) => (document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', { keyCode: c, key: 'Unidentified', bubbles: true, cancelable: true })), code); await page.waitForTimeout(150); };
    const digits = async (s) => { for (const c of s) await press(c === '-' ? CODE.minus : 48 + Number(c)); };
    const screen = () => page.evaluate(() => W.current().name);
    const log = () => page.evaluate(() => webapis.avplay.log.slice());
    const last = (l, what) => l.filter((e) => e[0] === what).pop();

    const reg = await page.evaluate(() => window.__registered.slice());
    check(['ChannelUp', 'MediaPlay', 'ColorF0Red', 'Caption', '4', 'Minus'].every((k) => reg.includes(k)) && !reg.includes('Exit') && !reg.includes('VolumeUp'),
      `the remote keys WygTV uses are registered (${reg.length}), and Exit and volume stay with the TV`);

    await press(CODE.green); check(await screen() === 'guide', 'green key: TV Guide');
    await press(CODE.yellow); check(await screen() === 'search', 'yellow key: Search');
    await press(CODE.blue); check(await screen() === 'settings', 'blue key: Settings');
    await press(CODE.red); check(await screen() === 'live', 'red key: Live TV');
    await page.evaluate(() => W.home());

    // Typing a channel number on Home tunes it full screen; 4 - 1 and 4 2 find the antenna sub-channels.
    await digits('12');
    check(await page.evaluate(() => (document.querySelector('.tv-num') || {}).textContent) === '12', 'typed digits show on screen');
    await page.waitForTimeout(1900);
    let l = await log();
    check(/ch11\.m3u8$/.test((last(l, 'open') || [])[1] || '') && await page.evaluate(() => W.player.isFull()), `12 tunes channel 12 full screen (${(last(l, 'open') || [])[1]})`);
    await page.evaluate(() => W.player.stop());
    await digits('4-1');
    await page.waitForTimeout(1900);
    l = await log();
    check(/ch3\.m3u8$/.test((last(l, 'open') || [])[1] || ''), `4 - 1 tunes 4.1 (${(last(l, 'open') || [])[1]})`);
    // In the full-screen player its own number entry takes the keys: 4 2 → 4.2 (there's no 42).
    await page.evaluate(() => { const o = document.querySelector('.osd'); if (o) o.hidden = true; });
    await digits('42');
    await page.waitForTimeout(1900);
    l = await log();
    check(/ch4\.m3u8$/.test((last(l, 'open') || [])[1] || ''), `in the player, 4 2 tunes 4.2 (${(last(l, 'open') || [])[1]})`);

    // Channel up/down, pause and play keys in the player.
    await page.evaluate(() => { const o = document.querySelector('.osd'); if (o) o.hidden = true; webapis.avplay.log.length = 0; });
    await press(CODE.chUp);
    await page.waitForTimeout(800);
    l = await log();
    check(/ch3\.m3u8$/.test((last(l, 'open') || [])[1] || ''), `CH ▲ goes to the previous channel (${(last(l, 'open') || [])[1]})`);
    await page.evaluate(() => { webapis.avplay.log.length = 0; });
    await press(CODE.pause); await press(CODE.pause);
    await press(CODE.play);
    l = await log();
    check(l.filter((e) => e[0] === 'pause').length === 1 && last(l, 'play'), `pause pauses once, play plays (${l.map((e) => e[0]).filter((k) => /pause|play/.test(k)).join(' ')})`);

    // Sound tracks and subtitles from AVPlay: buttons on the bar, the sound menu, and the CC key.
    await page.evaluate(() => { document.querySelector('.osd').hidden = true; });
    await press(CODE.info);
    check(await page.evaluate(() => !document.querySelector('.osd').hidden), 'INFO shows the on-screen bar');
    const btns = await page.$$eval('.osd-btn', (els) => els.map((e) => e.textContent));
    check(btns.some((t) => /Sound/.test(t)) && btns.some((t) => /Subtitles/.test(t)), `the bar offers Sound and Subtitles (${JSON.stringify(btns)})`);
    await page.evaluate(() => [...document.querySelectorAll('.osd-btn')].find((b) => /Sound/.test(b.textContent)).click());
    await page.waitForSelector('.menu-item');
    const tracks = await page.$$eval('.menu-item', (els) => els.map((e) => e.textContent + (e.classList.contains('checked') ? ' ✓' : '')));
    check(tracks.length === 2 && /eng · AC3 ✓/.test(tracks[0]) && /spa · AAC/.test(tracks[1]), `Sound lists the stream's tracks, the playing one ticked (${JSON.stringify(tracks)})`);
    await page.screenshot({ path: path.join(shots, 'sound-tracks.png') });
    await page.click('.menu-item >> nth=1');
    l = await log();
    check(JSON.stringify(last(l, 'track')) === JSON.stringify(['track', 'AUDIO', 2]), 'choosing Spanish switches AVPlay to that track');
    await press(CODE.cc);
    l = await log();
    check(JSON.stringify(last(l, 'track')) === JSON.stringify(['track', 'TEXT', 3]) && JSON.stringify(last(l, 'silent')) === JSON.stringify(['silent', false]), 'CC turns subtitles on');
    await page.evaluate(() => webapis.avplay.L.onsubtitlechange(3000, 'Hello from the subtitles'));
    check(await page.evaluate(() => { const e = document.querySelector('.player-subs'); return !e.hidden && e.textContent; }) === 'Hello from the subtitles', 'subtitle text shows over the picture');
    await page.screenshot({ path: path.join(shots, 'subtitles.png') });
    await press(CODE.cc);
    l = await log();
    check(JSON.stringify(last(l, 'silent')) === JSON.stringify(['silent', true]) && await page.evaluate(() => document.querySelector('.player-subs').hidden), 'CC again turns them off');

    check(errors.length === 0, `phase 4: no script errors ${errors.length ? JSON.stringify(errors.slice(0, 3)) : ''}`);
    await page.close();
  }

  // ---------------------------------------------------------------- phase 6: Diagnostics, for reporting problems from a TV
  {
    const { page, errors } = await open('?avplay=1', true);
    // A broken stream and a warning that carries a playlist login: both should be listed, the login masked.
    await page.evaluate(() => {
      console.warn('player', 'hls', 'http://example.com:8080/live/alice/secretpass/123.m3u8 failed');
      console.warn('m3u', 'http://example.com/get.php?username=alice&password=secretpass&type=m3u');
      W.player.play({ kind: 'live', key: 'x|fail', name: 'Broken', url: 'http://127.0.0.1:1/fail.ts' }, { full: false });
    });
    await page.waitForTimeout(1200);
    await page.evaluate(() => { W.player.stop(); W.go('settings', { section: 'about' }); });
    await page.waitForTimeout(300);
    const has = await page.evaluate(() => [...document.querySelectorAll('.button')].some((b) => /Diagnostics/.test(b.textContent)));
    check(has, 'Settings → About has a Diagnostics button on the TV');
    await page.evaluate(() => [...document.querySelectorAll('.button')].find((b) => /Diagnostics/.test(b.textContent)).click());
    await page.waitForSelector('.dialog.menu.wide');
    const text = await page.evaluate(() => document.querySelector('.dialog.menu.wide .dialog-text').innerText);
    fs.writeFileSync(path.join(shots, 'diagnostics.txt'), text);
    await page.screenshot({ path: path.join(shots, 'diagnostics.png') });
    check(/WygTV 1\.0\.42 · Samsung TV QN55Q80T/.test(text) && /Firmware T-KSU2EAKUC-1500\.2 · Tizen 5\.5 · web engine Chrome \d+/.test(text), 'Diagnostics names the app, the TV, its firmware and Tizen version');
    check(/Player: AVPlay · remote keys registered: \d+/.test(text) && /Playlists: m3u/.test(text), 'Diagnostics shows the player, the remote keys and the playlists');
    check(/PLAYER_ERROR_CONNECTION_FAILED/.test(text) && /Recent problems/.test(text), 'Diagnostics lists the broken stream');
    check(!/secretpass|alice/.test(text) && /\/live\/…\/…\//.test(text) && /password=…/.test(text), 'playlist logins are masked in Diagnostics');
    check(errors.length === 0, `phase 6: no script errors ${errors.length ? JSON.stringify(errors.slice(0, 3)) : ''}`);
    await page.close();
  }

  // ---------------------------------------------------------------- the TV's text size: #app laid out smaller and magnified
  {
    const { page, errors } = await open('?avplay=1', true);
    const dims = () => page.evaluate(() => {
      const a = document.getElementById('app'), r = a.getBoundingClientRect();
      return { size: W.api.tv.size(), zoom: window.__wygTvZoom, layout: a.offsetWidth, screen: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)] };
    });
    let d = await dims();
    check(d.size === 'large' && d.zoom === 1.5 && d.layout === 1280 && JSON.stringify(d.screen) === '[0,0,1920,1080]',
      `Large (the default): laid out 1280 wide, magnified 1.5× to fill the screen exactly (${JSON.stringify(d)})`);
    await page.screenshot({ path: path.join(shots, 'size-large-home.png') });
    await page.evaluate(() => new Promise((r) => { const off = W.data.on('loaded', () => { off(); r(); }); setTimeout(r, 8000); }));
    await page.evaluate(() => W.go('live'));
    await page.waitForSelector('.chan');
    await page.focus('.chan');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(1500);
    const box = await page.evaluate(() => { const r = document.querySelector('.preview-box').getBoundingClientRect(); return [r.left, r.top, r.width, r.height].map(Math.round); });
    let rect = await page.evaluate(() => webapis.avplay.log.filter((e) => e[0] === 'rect').pop().slice(1));
    check(rect.every((v, i) => Math.abs(v - box[i]) <= 4), `the preview plays exactly over the enlarged preview box (${JSON.stringify(rect)} vs ${JSON.stringify(box)})`);
    await page.screenshot({ path: path.join(shots, 'size-large-live.png') });
    await page.evaluate(() => W.player.enterFull());
    await page.waitForTimeout(800);
    rect = await page.evaluate(() => webapis.avplay.log.filter((e) => e[0] === 'rect').pop().slice(1));
    check(JSON.stringify(rect) === '[0,0,1920,1080]', `full screen still fills the TV (${JSON.stringify(rect)})`);
    await page.evaluate(() => { W.player.stop(); W.go('settings', { section: 'look' }); });
    await page.waitForTimeout(300);
    const rowText = () => page.evaluate(() => [...document.querySelectorAll('.set-row')].map((r) => r.textContent).find((t) => /Size of text/.test(t)) || '');
    const row = await rowText();
    check(/Size of text and menus: Large/.test(row) && /OK: Extra large/.test(row), `Settings → Appearance has the size toggle ("${row.slice(0, 50)}…")`);
    await page.screenshot({ path: path.join(shots, 'size-large-settings.png') });
    // OK on the toggle: Extra large at once, without reloading WygTV.
    await page.evaluate(() => { window.__noReload = true; });
    await page.evaluate(() => [...document.querySelectorAll('.set-row')].find((r) => /Size of text/.test(r.textContent)).click());
    await page.waitForTimeout(400);
    d = await dims();
    check(d.size === 'xlarge' && d.layout === 1097 && JSON.stringify(d.screen) === '[0,0,1920,1080]' && await page.evaluate(() => window.__noReload === true),
      `OK on the toggle: Extra large at once, laid out 1097 wide, still filling the screen, no reload (${JSON.stringify(d)})`);
    check(/: Extra large/.test(await rowText()) && /OK: Small/.test(await rowText()), 'the toggle then offers Small');
    await page.evaluate(() => W.home());
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(shots, 'size-xlarge-home.png') });
    await page.evaluate(() => W.go('live'));
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(shots, 'size-xlarge-live.png') });
    const over = await page.evaluate(() => [...document.querySelectorAll('#screen *')].filter((e) => { const r = e.getBoundingClientRect(); return r.width && r.right > 1922; }).length);
    check(over === 0, `Extra large: nothing runs off the right edge of the screen (${over})`);
    // A dialog stays on screen at the biggest size.
    await page.evaluate(() => { W.alert('A long message', 'x '.repeat(900)); });
    await page.waitForSelector('.dialog');
    const dlg = await page.evaluate(() => { const r = document.querySelector('.dialog').getBoundingClientRect(); return [Math.round(r.top), Math.round(r.bottom)]; });
    check(dlg[0] >= 0 && dlg[1] <= 1080, `Extra large: a long dialog stays on the screen (${JSON.stringify(dlg)})`);
    await page.evaluate(() => W.back());
    // Small: back to no magnification.
    await page.evaluate(() => W.api.tv.setSize('small'));
    await page.waitForTimeout(300);
    d = await dims();
    check(d.size === 'small' && d.zoom === 1 && d.layout === 1920 && !(await page.evaluate(() => document.documentElement.classList.contains('tv-zoom'))), `Small: laid out 1920 wide, not magnified (${JSON.stringify(d)})`);
    // The size is remembered when WygTV starts again.
    await page.reload();
    await page.waitForSelector('#screen[data-screen="home"]');
    d = await dims();
    check(d.size === 'small' && d.layout === 1920, `the size is remembered next time (${d.size})`);
    check(errors.length === 0, `text size: no script errors ${errors.length ? JSON.stringify(errors.slice(0, 3)) : ''}`);
    await page.close();
  }

  // ---------------------------------------------------------------- typing on a phone: QR code → phone page → encrypted relay → TV
  {
    const { page: tv, errors } = await open('', true);
    // (Matched by host: older Playwright globs miss URLs with a query string, like the TV's ?since=.)
    // A stand-in for ntfy.sh: the phone's POST is kept, and the TV's listening request gets it (or ends after a
    // moment, as a quiet relay connection would, and the TV listens again).
    const relay = { posted: [], trace: [] };
    const warns = [];
    tv.on('console', (m) => { if (m.type() === 'warning' || m.type() === 'error') warns.push(m.text().slice(0, 120)); });
    const reqs = [];
    tv.on('request', (q) => { if (/ntfy/.test(q.url())) reqs.push('req ' + q.method() + ' ' + q.url().replace(/wygtv-[^/]+/, 'T')); });
    tv.on('requestfailed', (q) => { if (/ntfy/.test(q.url())) reqs.push('failed ' + (q.failure() && q.failure().errorText)); });
    await tv.route((u) => u.hostname === 'ntfy.sh', async (route) => {
      const topic = new URL(route.request().url()).pathname.split('/')[1];
      let msg = relay.posted.find((p) => p.topic === topic);
      if (!msg) { await new Promise((r) => setTimeout(r, 700)); msg = relay.posted.find((p) => p.topic === topic); }
      relay.trace.push((msg ? 'served' : 'empty') + '@' + (Date.now() % 100000));
      const line = msg ? { id: 'x', event: 'message', topic, message: msg.body } : { event: 'open', topic };
      await route.fulfill({ status: 200, headers: { 'Content-Type': 'application/x-ndjson', 'Access-Control-Allow-Origin': '*' }, body: JSON.stringify(line) + '\n' });
    });
    await tv.evaluate(() => {
      W.form('Add an M3U playlist', [
        { key: 'name', label: 'Name (optional)', value: 'Kitchen' },
        { key: 'url', label: 'Playlist link', type: 'url' },
        { key: 'pass', label: 'Password', type: 'password', value: 'old-secret' },
      ]);
    });
    await tv.waitForSelector('.dialog.form');
    const hasBtn = await tv.evaluate(() => [...document.querySelectorAll('.dialog.form .button')].some((b) => /Type on your phone/.test(b.textContent)));
    check(hasBtn, 'forms on the TV have "📱 Type on your phone"');
    await tv.evaluate(() => [...document.querySelectorAll('.dialog.form .button')].find((b) => /Type on your phone/.test(b.textContent)).click());
    await tv.waitForSelector('.phone-dialog canvas.phone-qr');
    await tv.screenshot({ path: path.join(shots, 'phone-qr.png') });
    const qr = await tv.evaluate(() => window.__wygPhoneLast);
    const frag = qr.url.split('#')[1];
    const fieldsSent = Buffer.from(decodeURIComponent(frag.match(/f=([^&]+)/)[1]), 'base64url').toString();
    check(qr.url.startsWith('https://wyglaf01.github.io/WygTV-AndroidTV/send.html#v=1&t=wygtv-'), 'the QR code opens the phone page on GitHub Pages');
    check(!qr.url.includes('old-secret') && !fieldsSent.includes('old-secret'), 'passwords are never put in the QR code');

    // The phone: open the page the QR code points to, type, and send.
    const phone = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true });
    await phone.route((u) => u.hostname === 'ntfy.sh', async (route) => {
      const topic = new URL(route.request().url()).pathname.split('/')[1];
      relay.posted.push({ topic, body: route.request().postData() || '' });
      await route.fulfill({ status: 200, headers: { 'Access-Control-Allow-Origin': '*' }, body: '{}' });
    });
    await phone.goto(base + 'send.html#' + frag);
    await phone.waitForSelector('form:not([hidden]) input');
    const labels = await phone.$$eval('form label span', (els) => els.map((e) => e.textContent));
    const prefilled = await phone.$$eval('form input', (els) => els.map((e) => e.value));
    check(JSON.stringify(labels) === JSON.stringify(['Name (optional)', 'Playlist link', 'Password']) && prefilled[0] === 'Kitchen' && prefilled[2] === '',
      `the phone shows the TV's fields, the name filled in and no password (${JSON.stringify(labels)})`);
    check(await phone.evaluate(() => !location.hash), 'the phone page drops the key from its address once read');
    const inputs = await phone.$$('form input');
    await inputs[1].fill('http://example.com/get.php?username=alice&password=hunter2');
    await inputs[2].fill('hunter2');
    await phone.screenshot({ path: path.join(shots, 'phone-page.png') });
    await phone.click('form button');
    await phone.waitForSelector('.status.ok');
    const sent = relay.posted[0] || { body: '' };
    check(relay.posted.length === 1 && sent.topic === qr.topic && /^[A-Za-z0-9_-]{60,}$/.test(sent.body), "the phone sends one message, to that QR code's own channel");
    check(!/hunter2|alice|example\.com|Kitchen/.test(sent.body + Buffer.from(sent.body, 'base64url').toString('latin1')), 'the relay only sees scrambled data (no link, login or name)');

    // Back on the TV: the form fills in and the QR code closes.
    await tv.waitForFunction(() => !document.querySelector('.phone-dialog'), null, { timeout: 8000 }).catch(() => { });
    const vals = await tv.evaluate(() => [...document.querySelectorAll('.dialog.form input')].map((i) => i.value));
    const filledOk = JSON.stringify(vals) === JSON.stringify(['Kitchen', 'http://example.com/get.php?username=alice&password=hunter2', 'hunter2']);
    check(filledOk, `the TV's form fills in from the phone (${JSON.stringify(vals.map((v) => v.slice(0, 24)))})`
      + (filledOk ? '' : ` [relay: ${relay.trace.slice(-8).join(' ')}; TV requests: ${JSON.stringify(reqs.slice(-6))}; TV warnings: ${JSON.stringify(warns.slice(-4))}; fetch: ${await tv.evaluate(() => typeof fetch + ' ' + typeof ReadableStream + ' ' + navigator.userAgent.match(/Chrome\/[\d.]+/))}]`));
    check(await tv.evaluate(() => !document.querySelector('.phone-dialog')), 'the QR code closes once it has');
    await tv.screenshot({ path: path.join(shots, 'phone-filled.png') });

    // A message sealed with another key (someone else's QR code) is ignored.
    const t2 = await tv.evaluate(() => { W.api.phone.fill('Test', [{ key: 'a', label: 'A' }], { a: document.createElement('input') }); return window.__wygPhoneLast.topic; });
    const k2 = nacl.randomBytes(32), n2 = nacl.randomBytes(24);
    const fake = nacl.secretbox(Buffer.from(JSON.stringify({ v: 1, values: { a: 'evil' } })), n2, k2);
    relay.posted.push({ topic: t2, body: Buffer.concat([Buffer.from(n2), Buffer.from(fake)]).toString('base64url') });
    await tv.waitForTimeout(2500);
    check(await tv.evaluate(() => !!document.querySelector('.phone-dialog')), 'a message sealed with another key is ignored (the QR code stays up)');
    await tv.evaluate(() => W.back());
    check(errors.length === 0, `phone typing: no script errors ${errors.length ? JSON.stringify(errors.slice(0, 3)) : ''}`);
    await phone.close();
    await tv.close();
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
