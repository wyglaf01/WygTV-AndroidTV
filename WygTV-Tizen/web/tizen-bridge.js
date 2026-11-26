'use strict';
/*
 * WygTV for Samsung Smart TVs (Tizen 5.5 and newer): stands in for WygDesktop's main process
 * (src/preload.js + src/main/*.js) so the same screens run on the TV, the way WygTV-iOS/web/ios-bridge.js
 * does on iPhone and iPad.
 *
 * Web requests use the page's own fetch: config.xml's <access origin="*"> lets a TV app reach any server,
 * so there are no CORS rules to get around. The remote's Back key goes back, and on Home it offers to exit.
 *
 * Still to come (see WygTV-Tizen/README.md): gzip guides and keeping guides between starts (phase 2),
 * Samsung's AVPlay player for MPEG-2 and Dolby antenna channels (phase 3), media and number keys (phase 4).
 */
(function () {
  const req = window.__wygRequire;
  const tz = window.tizen || null;
  const BRAND = 'WygTV';

  // ------------------------------------------------------------------ web requests
  let userAgent = 'WygTV-Tizen/1.0';

  async function request(url, init, timeout) {
    if (!/^https?:\/\//i.test(url)) throw new Error('Only HTTP or HTTPS links are supported.');
    const ac = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = ac ? setTimeout(() => ac.abort(), timeout) : 0;
    let r;
    try {
      // Browsers don't let pages set User-Agent; the TV's web engine sends its own.
      r = await fetch(url, Object.assign({ signal: ac ? ac.signal : undefined, credentials: 'omit', cache: 'no-store' }, init));
    } catch (error) {
      const server = new URL(url).origin;
      if (error && error.name === 'AbortError') throw new Error(server + ' took too long to answer. Check the server address, network and provider, then try again.');
      throw new Error('Could not connect to ' + server + '. Check the server address, network and provider, then reload the playlist.');
    } finally { clearTimeout(timer); }
    return r;
  }

  async function getBytes(url, timeout = 60000) {
    const r = await request(url, { headers: { Accept: '*/*' } }, timeout);
    if (!r.ok) throw new Error(`HTTP ${r.status} from ${new URL(url).host}`);
    return new Uint8Array(await r.arrayBuffer());
  }
  const isGzip = (b) => b.length > 2 && b[0] === 0x1f && b[1] === 0x8b;
  function decode(bytes) {
    if (isGzip(bytes)) throw new Error('This guide is gzip-compressed (.gz). Samsung TVs get gzip guides in the next WygTV update; use the plain .xml link for now.');
    return new TextDecoder('utf-8').decode(bytes);
  }
  const text = async (url, timeout) => decode(await getBytes(url, timeout));
  async function json(url, timeout) {
    const t = await text(url, timeout);
    try { return JSON.parse(t); } catch (e) { throw new Error('The server did not answer with JSON (' + t.slice(0, 80).replace(/\s+/g, ' ') + '…)'); }
  }
  /** POSTs JSON and reads JSON back (Tablo tuners). */
  async function postJson(url, body, timeout = 30000) {
    const r = await request(url, { method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, body: JSON.stringify(body == null ? {} : body) }, timeout);
    let j = null;
    try { j = JSON.parse(await r.text()); } catch (e) { }
    if (!r.ok) { if (j && j.error) return j; throw new Error(`HTTP ${r.status} from ${new URL(url).host}`); }
    if (j == null) throw new Error('The server did not answer with JSON');
    return j;
  }

  // The desktop modules (m3u.js, xmltv.js, hdhr.js, tablo.js) are reused as they are; this is their "./net".
  window.__wygNet = {
    text: (url, o = {}) => text(url, o.timeout), json: (url, o = {}) => json(url, o.timeout), postJson: (url, body, o = {}) => postJson(url, body, o.timeout),
    setUserAgent: (ua) => { if (ua && ua.trim()) userAgent = ua.trim(); }, getUserAgent: () => userAgent,
  };
  const m3u = req('./m3u'), xmltv = req('./xmltv'), hdhr = req('./hdhr'), tablo = req('./tablo');

  // ------------------------------------------------------------------ TV guides (same rules as src/main/epg.js; kept in memory for now)
  const MAX_AGE = 12 * 3600e3;
  const guides = new Map();
  async function loadGuide(url, force) {
    let g = guides.get(url);
    if (!g) { g = {}; guides.set(url, g); }
    if (g.loading) return g.loading;
    if (!force && g.data && Date.now() - g.loadedAt < MAX_AGE) return;
    g.loading = (async () => {
      try {
        let data;
        if (url.startsWith('hdhr:')) data = await hdhr.loadGuide(url.slice(5), 24);
        else if (url.startsWith('tablo:')) data = await tablo.loadGuide(url.slice(6), 24);
        else {
          const p = new xmltv.Parser();
          p.push(decode(await getBytes(url, 180000)));
          data = p.finish();
        }
        g.data = data; g.loadedAt = Date.now(); g.byName = null; g.error = '';
      } catch (e) { g.error = String(e.message || e); throw e; } finally { g.loading = null; }
    })();
    return g.loading;
  }
  function guideStatus() {
    const out = {};
    guides.forEach((g, url) => { out[url] = { loadedAt: g.loadedAt || 0, error: g.error || '', loading: !!g.loading, channels: g.data ? Object.keys(g.data.programmes).length : 0 }; });
    return out;
  }
  function byName(g) {
    if (!g.byName) {
      g.byName = new Map();
      for (const [id, ch] of Object.entries(g.data.channels || {})) {
        if (!g.data.programmes[id]) continue;
        for (const n of ch.names || []) { const k = xmltv.nameKey(n); if (k && !g.byName.has(k)) g.byName.set(k, id); }
        const k = xmltv.nameKey(id.replace(/\.[a-z]{2}$/i, ''));
        if (k && !g.byName.has(k)) g.byName.set(k, id);
      }
    }
    return g.byName;
  }
  function guideQuery(items, from, to) {
    const out = {};
    for (const it of items) {
      for (const url of it.guides || []) {
        const g = guides.get(url);
        if (!g || !g.data) continue;
        let list = it.epgId ? g.data.programmes[it.epgId] : null;
        if (!list && it.name) { const id = byName(g).get(xmltv.nameKey(it.name)); if (id) list = g.data.programmes[id]; }
        if (!list || !list.length) continue;
        const hit = list.filter((p) => p[1] > from && p[0] < to);
        if (hit.length) { out[it.key] = hit; break; }
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ HDHomeRun: TV apps can't broadcast, so the cloud lookup + hdhomerun.local
  async function hdhrDiscover() {
    const bases = new Set(['http://hdhomerun.local']);
    try {
      const list = await json('https://api.hdhomerun.com/discover', 8000);
      for (const o of Array.isArray(list) ? list : []) if (o && (o.LocalIP || o.BaseURL) && (o.TunerCount || o.DeviceID)) bases.add(o.BaseURL || 'http://' + o.LocalIP);
    } catch (e) { }
    const out = [];
    await Promise.all([...bases].map(async (b) => { try { const d = await hdhr.describe(b); if (!out.some((o) => o.id && o.id === d.id)) out.push(d); } catch (e) { } }));
    return out;
  }

  // ------------------------------------------------------------------ the app on the TV
  function appVersion() {
    try { return tz.application.getAppInfo().version; } catch (e) { return '1.0.0'; }
  }
  function tvModel() {
    try { return window.webapis && webapis.productinfo ? webapis.productinfo.getRealModel() : ''; } catch (e) { return ''; }
  }
  function exitApp() {
    try { tz.application.getCurrentApplication().exit(); } catch (e) { window.close(); }
  }

  // The remote's Back key (10009) does what Escape does in the desktop app; on Home it offers to exit,
  // as Samsung asks of TV apps.
  let asking = false;
  document.addEventListener('keydown', async (e) => {
    if (e.keyCode !== 10009 && e.key !== 'XF86Back') return;
    e.preventDefault();
    e.stopImmediatePropagation();
    const t = document.activeElement;
    if (t && /INPUT|TEXTAREA/.test(t.tagName)) { t.blur(); return; }
    const atHome = !document.querySelector('#layers > .layer') && !(W.player && W.player.isFull && W.player.isFull()) && W.current && W.current() && W.current().name === 'home';
    if (!atHome) { W.back(); return; }
    if (asking) return;
    asking = true;
    try { if (await W.confirm('Exit ' + BRAND + '?', 'Press OK to close ' + BRAND + '.')) exitApp(); } finally { asking = false; }
  }, true);

  const ok = (v) => Promise.resolve(v);
  const no = (why) => () => Promise.reject(new Error(why));
  const api = {
    brand: BRAND, platform: 'tizen', noFFmpeg: true,
    onUpdateProgress: () => { },
    app: { info: () => ok({ version: appVersion(), platform: 'tizen', arch: 'arm', brand: BRAND, device: tvModel() ? 'Samsung TV ' + tvModel() : 'Samsung TV' }) },
    net: { setUserAgent: (ua) => ok(window.__wygNet.setUserAgent(ua)), text: (u, t) => text(u, t), json: (u, t) => json(u, t) },
    m3u: {
      load: async (url) => {
        if (/^https?:/i.test(url)) return m3u.parse(await text(url, 120000));
        throw new Error('On a Samsung TV, playlists come from a web link (http:// or https://).');
      },
    },
    hdhr: { discover: hdhrDiscover, describe: (b) => hdhr.describe(b), lineup: (b) => hdhr.lineup(b) },
    tablo: { discover: () => tablo.discoverAll(), describe: (b) => tablo.describe(b), lineup: (b) => tablo.lineup(b), watch: (b, id) => tablo.watch(b, id) },
    epg: {
      ensure: async (urls, force) => { await Promise.all((urls || []).filter(Boolean).map((u) => loadGuide(u, force).catch(() => { }))); return guideStatus(); },
      status: () => ok(guideStatus()),
      query: (items, from, to) => ok(guideQuery(items, from, to)),
    },
    stream: { probe: no('no FFmpeg on Samsung TVs'), start: no('no FFmpeg on Samsung TVs'), stop: () => ok(), log: () => ok('') },
    shell: { open: () => ok() },
    win: { fullscreen: () => ok(true), quit: () => ok(exitApp()) },
    ext: { play: no('Samsung TVs have no external players.'), pick: () => ok('') },
    file: { save: no('Backups on Samsung TVs come in a later WygTV update.'), open: () => ok(null) },
    // The TV app is installed from a computer (or, later, the Samsung store), so there is nothing to check here.
    update: { check: () => ok({ current: appVersion(), latest: appVersion(), available: false, kind: 'tizen' }), install: no('Updates are installed from a computer.') },
  };
  window.wyg = api;
  document.documentElement.classList.add('tizen');
})();
