'use strict';
/*
 * WygTV for Samsung Smart TVs (Tizen 5.5 and newer): stands in for WygDesktop's main process
 * (src/preload.js + src/main/*.js) so the same screens run on the TV, the way WygTV-iOS/web/ios-bridge.js
 * does on iPhone and iPad.
 *
 * Web requests use the page's own fetch: config.xml's <access origin="*"> lets a TV app reach any server,
 * so there are no CORS rules to get around. The remote's Back key goes back, and on Home it offers to exit.
 *
 * Guides stream in and un-gzip as they download (fflate), and are kept in IndexedDB between starts.
 * Backups are kept on the TV (the newest 5), and can also be restored from a web link, e.g. a backup the
 * desktop or iOS app saved.
 *
 * Video plays in Samsung's AVPlay (web/tizen-avplay.js): makeVideo() hands player.js an element that behaves
 * like <video>, and antenna channels play as they are (MPEG-2 and Dolby sound are decoded by the TV).
 *
 * Remote keys: the TV only sends the keys an app registers, so the ones WygTV uses are registered at start and
 * turned into the key names the shared screens know (ChannelUp, MediaPlay, Subtitle…). Colour keys open Live TV,
 * the TV Guide, Search and Settings, and typing a channel number anywhere tunes it.
 */
(function () {
  // ------------------------------------------------------------------ recent problems, for Settings → About → Diagnostics
  const problems = [];
  const note = (kind, text) => {
    // People photograph this to report problems: playlist logins in stream addresses are masked.
    const clean = String(text).replace(/\s+/g, ' ')
      .replace(/\b(username|password|user|pass|token)=[^&\s]+/gi, '$1=…')
      .replace(/\/(live|movie|series|timeshift)\/[^/\s]+\/[^/\s]+\//gi, '/$1/…/…/');
    problems.push(new Date().toTimeString().slice(0, 8) + ' ' + kind + ': ' + clean.slice(0, 160));
    if (problems.length > 12) problems.shift();
  };
  window.addEventListener('error', (e) => note('error', (e.message || e.error) + (e.filename ? ' (' + e.filename.split('/').pop() + ':' + e.lineno + ')' : '')));
  window.addEventListener('unhandledrejection', (e) => note('promise', e.reason && (e.reason.message || e.reason)));
  for (const level of ['error', 'warn']) {
    const orig = console[level];
    console[level] = function () {
      note(level, Array.prototype.map.call(arguments, (a) => (a && a.message) || (typeof a === 'object' ? JSON.stringify(a) : a)).join(' '));
      return orig.apply(console, arguments);
    };
  }

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
  const decode = (bytes) => new TextDecoder('utf-8').decode(isGzip(bytes) ? fflate.gunzipSync(bytes) : bytes);

  /**
   * Downloads text a piece at a time, un-gzipping on the fly when it is gzip, and hands each piece to onText.
   * Big guides (100 MB and more once un-gzipped) never sit in memory whole.
   */
  async function streamText(url, timeout, onText) {
    const r = await request(url, { headers: { Accept: '*/*' } }, timeout);
    if (!r.ok) throw new Error(`HTTP ${r.status} from ${new URL(url).host}`);
    const td = new TextDecoder('utf-8');
    const out = (u8) => { const t = td.decode(u8, { stream: true }); if (t) onText(t); };
    let gun = null, head = null;
    const feed = (u8, last) => {
      if (head !== true) {
        // Decide gzip or not from the first two bytes, however the download was split.
        const b = head ? concat(head, u8) : u8;
        if (b.length < 2 && !last) { head = b; return; }
        head = true;
        if (isGzip(b)) gun = new fflate.Gunzip((d) => out(d));
        u8 = b;
      }
      if (gun) gun.push(u8, last); else out(u8);
    };
    const reader = r.body && r.body.getReader ? r.body.getReader() : null;
    if (!reader) feed(new Uint8Array(await r.arrayBuffer()), true);
    else {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (value && value.length) feed(value, false);
      }
      feed(new Uint8Array(0), true);
    }
    const tail = td.decode();
    if (tail) onText(tail);
  }
  function concat(a, b) { const c = new Uint8Array(a.length + b.length); c.set(a); c.set(b, a.length); return c; }
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

  // ------------------------------------------------------------------ IndexedDB: guides and backups kept on the TV
  let dbOpen = null;
  function db() {
    if (!dbOpen) {
      dbOpen = new Promise((resolve, reject) => {
        const r = indexedDB.open('wygtv', 1);
        r.onupgradeneeded = () => {
          r.result.createObjectStore('guides', { keyPath: 'url' });
          r.result.createObjectStore('backups', { keyPath: 'id', autoIncrement: true });
        };
        r.onsuccess = () => resolve(r.result);
        r.onerror = () => reject(r.error);
      });
      dbOpen.catch(() => { dbOpen = null; });
    }
    return dbOpen;
  }
  /** Runs fn(store) in a transaction and resolves with the request it returns (if any) once it commits. */
  async function store(name, mode, fn) {
    const d = await db();
    return new Promise((resolve, reject) => {
      const t = d.transaction(name, mode);
      const q = fn(t.objectStore(name));
      t.oncomplete = () => resolve(q ? q.result : undefined);
      t.onerror = () => reject(t.error);
      t.onabort = () => reject(t.error || new Error('The TV\u2019s storage refused the change'));
    });
  }

  // ------------------------------------------------------------------ TV guides (same rules as src/main/epg.js)
  const MAX_AGE = 12 * 3600e3;
  const guides = new Map();
  // Guides nobody has asked for in 3 days (an old playlist's) are dropped at start-up.
  store('guides', 'readwrite', (s) => {
    const c = s.openCursor();
    c.onsuccess = () => { const cur = c.result; if (!cur) return; if (Date.now() - cur.value.loadedAt > 3 * 24 * 3600e3) cur.delete(); cur.continue(); };
  }).catch(() => { });
  async function loadGuide(url, force) {
    let g = guides.get(url);
    if (!g) { g = {}; guides.set(url, g); }
    if (g.loading) return g.loading;
    if (!force && g.data && Date.now() - g.loadedAt < MAX_AGE) return;
    g.loading = (async () => {
      try {
        if (!force && !g.data) {
          try {
            const saved = await store('guides', 'readonly', (s) => s.get(url));
            if (saved && Date.now() - saved.loadedAt < MAX_AGE) { g.data = saved.data; g.loadedAt = saved.loadedAt; g.byName = null; g.error = ''; return; }
          } catch (e) { /* storage unavailable: download it */ }
        }
        let data;
        if (url.startsWith('hdhr:')) data = await hdhr.loadGuide(url.slice(5), 24);
        else if (url.startsWith('tablo:')) data = await tablo.loadGuide(url.slice(6), 24);
        else {
          const p = new xmltv.Parser();
          await streamText(url, 180000, (t) => p.push(t));
          data = p.finish();
        }
        g.data = data; g.loadedAt = Date.now(); g.byName = null; g.error = '';
        store('guides', 'readwrite', (s) => s.put({ url, loadedAt: g.loadedAt, data })).catch(() => { });
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

  // ------------------------------------------------------------------ backups: kept on the TV, or restored from a link
  const KEEP_BACKUPS = 5;
  const when = (t) => new Date(t).toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
  async function backupList() {
    const all = (await store('backups', 'readonly', (s) => s.getAll())) || [];
    return all.sort((a, b) => b.saved - a.saved);
  }
  async function fileSave(name, data) {
    await store('backups', 'readwrite', (s) => s.add({ saved: Date.now(), name, text: data }));
    const all = await backupList();
    if (all.length > KEEP_BACKUPS) await store('backups', 'readwrite', (s) => { all.slice(KEEP_BACKUPS).forEach((b) => s.delete(b.id)); });
    return 'this TV. Restore a backup lists the newest ' + KEEP_BACKUPS;
  }
  async function fileOpen() {
    let list = [];
    try { list = await backupList(); } catch (e) { }
    const items = list.map((b) => ({ label: 'Saved on this TV · ' + when(b.saved) })).concat([{ label: '🔗 From a web link…' }]);
    const i = await W.menu('Restore a backup', items, { text: 'Backups saved on this TV, or one from a link (a backup the desktop or iPhone app saved, put online).' });
    if (i < 0) return null;
    if (i < list.length) return { path: 'tv:' + list[i].id, text: list[i].text };
    const r = await W.form('Restore from a link', [{ key: 'url', label: 'Backup link', placeholder: 'https://…/wygdesktop-backup.json', type: 'url' }], {
      okLabel: 'Restore',
      validate: (v) => (/^https?:\/\//i.test(v.url.trim()) ? '' : 'Enter a link starting with http:// or https://'),
    });
    if (!r) return null;
    const url = r.url.trim();
    return { path: url, text: await text(url, 60000) };
  }

  // ------------------------------------------------------------------ the app on the TV
  function appVersion() {
    try { return tz.application.getAppInfo().version; } catch (e) { return '1.0.0'; }
  }
  function tvModel() {
    try { return window.webapis && webapis.productinfo ? webapis.productinfo.getRealModel() : ''; } catch (e) { return ''; }
  }
  /** The TV, its software and WygTV's state as plain text (Settings → About → Diagnostics; photograph it to report a problem). */
  function diagnostics() {
    const pi = window.webapis && webapis.productinfo;
    const get = (fn) => { try { const v = fn(); return v == null || v === '' ? '?' : String(v); } catch (e) { return '?'; } };
    const cap = (k) => get(() => tz.systeminfo.getCapability(k));
    const chrome = (navigator.userAgent.match(/Chrome\/([\d.]+)/) || [])[1] || '?';
    const cls = document.documentElement.className;
    const lists = (W.settings && W.settings.playlists) || [];
    const guide = (W.guide && W.guide.status) || {};
    const lines = [
      'WygTV ' + appVersion() + ' · Samsung TV ' + get(() => pi.getRealModel()) + ' (' + get(() => pi.getModel()) + ')',
      'Firmware ' + get(() => pi.getFirmware()) + ' · Tizen ' + cap('http://tizen.org/feature/platform.version') + ' · web engine Chrome ' + chrome,
      'Screen ' + window.innerWidth + '×' + window.innerHeight + ' at ' + (window.devicePixelRatio || 1) + '× (size: ' + (window.__wygTvSize || '?') + ') · 4K panel: ' + get(() => pi.isUdPanelSupported()),
      '2020-TV fallbacks: ' + ((/no-flex-gap|no-aspect-ratio/.test(cls) ? cls.split(' ').filter((c) => /^no-/.test(c)).join(', ') : 'none needed')),
      'Player: ' + (window.wygAvVideo ? 'AVPlay' : 'none (not on a TV)') + ' · remote keys registered: ' + Object.keys(byCode).length,
      'Playlists: ' + (lists.map((p) => p.type).join(', ') || 'none') + ' · guides: ' + Object.keys(guide).map((u) => (guide[u].channels || 0) + (guide[u].error ? ' (' + guide[u].error.slice(0, 40) + ')' : '')).join(', '),
      '',
      problems.length ? 'Recent problems:\n' + problems.join('\n') : 'No problems recorded since WygTV started.',
    ];
    return lines.join('\n');
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

  // ------------------------------------------------------------------ the remote's other keys
  // Samsung key names → what the shared screens and player.js expect in KeyboardEvent.key.
  const KEYS = {
    0: '0', 1: '1', 2: '2', 3: '3', 4: '4', 5: '5', 6: '6', 7: '7', 8: '8', 9: '9', Minus: '-',
    ChannelUp: 'ChannelUp', ChannelDown: 'ChannelDown',
    MediaPlayPause: 'MediaPlayPause', MediaPlay: 'MediaPlay', MediaPause: 'MediaPause', MediaStop: 'MediaStop',
    MediaRewind: 'MediaRewind', MediaFastForward: 'MediaFastForward', MediaTrackPrevious: 'MediaTrackPrevious', MediaTrackNext: 'MediaTrackNext',
    Info: 'i', Caption: 'Subtitle',
    ColorF0Red: 'TvRed', ColorF1Green: 'TvGreen', ColorF2Yellow: 'TvYellow', ColorF3Blue: 'TvBlue', Guide: 'TvGuide', ChannelList: 'TvChannelList',
  };
  const byCode = {};
  try {
    const input = tz && tz.tvinputdevice;
    if (input) {
      for (const k of input.getSupportedKeys()) {
        if (!(k.name in KEYS)) continue;
        try { input.registerKey(k.name); byCode[k.code] = KEYS[k.name]; } catch (e) { }
      }
    }
  } catch (e) { }

  const typing = () => { const t = document.activeElement; return !!t && /INPUT|TEXTAREA|SELECT/.test(t.tagName); };
  const layerOpen = () => !!document.querySelector('#layers > .layer');
  const playerFull = () => !!(W.player && W.player.isFull && W.player.isFull());
  function goTo(screen) {
    if (layerOpen()) return;
    if (playerFull()) W.player.exitFull();
    W.go(screen);
  }

  // A channel number typed outside the full-screen player (the player has its own): tune it full screen.
  let numBuf = '', numTimer = 0, numEl = null;
  function typeNumber(d) {
    if (!numEl) { numEl = document.createElement('div'); numEl.className = 'tv-num'; document.body.appendChild(numEl); }
    if (d === '-') { if (!numBuf || /\.$/.test(numBuf)) return; d = '.'; }
    numBuf = (numBuf + d).slice(-5);
    numEl.textContent = numBuf;
    numEl.hidden = false;
    clearTimeout(numTimer);
    numTimer = setTimeout(() => {
      numEl.hidden = true;
      const ch = W.player.channelByNumber(numBuf);
      numBuf = '';
      if (ch) W.player.playChannel(ch, { full: true, list: W.data.allChannels() });
      else W.toast('No channel with that number');
    }, 1500);
  }

  document.addEventListener('keydown', (e) => {
    if (e.__wyg) return;
    const name = byCode[e.keyCode] || (/^[0-9]$/.test(e.key) ? e.key : null);
    if (!name) return;
    // Colour keys can't type anything, so they work in Search's box too; the other keys leave text boxes alone.
    const tv = {
      TvRed: () => goTo('live'), TvGreen: () => goTo('guide'), TvYellow: () => goTo('search'), TvBlue: () => goTo('settings'),
      TvGuide: () => goTo('guide'), TvChannelList: () => goTo('live'),
    }[name];
    if (tv) { e.preventDefault(); e.stopImmediatePropagation(); if (typing()) document.activeElement.blur(); tv(); return; }
    if (typing()) return;
    if (!playerFull() && !layerOpen()) {
      if (/^[0-9-]$/.test(name)) { e.preventDefault(); e.stopImmediatePropagation(); typeNumber(name); return; }
      // Channel up/down while the Live TV preview or the mini player plays.
      if ((name === 'ChannelUp' || name === 'ChannelDown') && W.player.item()) { e.preventDefault(); e.stopImmediatePropagation(); W.player.zap(name === 'ChannelUp' ? -1 : 1); return; }
    }
    if (name === e.key) return; // already what the screens expect
    // Hand the key on under the name the shared screens know.
    e.preventDefault();
    e.stopImmediatePropagation();
    const k = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true });
    k.__wyg = true;
    (document.activeElement || document.body).dispatchEvent(k);
  }, true);

  const ok = (v) => Promise.resolve(v);
  const no = (why) => () => Promise.reject(new Error(why));
  const api = {
    brand: BRAND, platform: 'tizen', noFFmpeg: true,
    // On a TV, AVPlay plays everything itself (null in a browser preview, where player.js uses <video>).
    makeVideo: window.wygAvVideo ? () => window.wygAvVideo() : null,
    playsAntenna: !!window.wygAvVideo,
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
    file: { save: fileSave, open: fileOpen },
    // The TV app is installed from a computer (or, later, the Samsung store), so there is nothing to check here.
    diag: { report: diagnostics },
    // Size of text and menus (applied by tizen-legacy.js as the page loads, so changing it reloads WygTV).
    tv: {
      sizes: [
        { key: 'large', label: 'Large', sub: 'For watching from the sofa (recommended)' },
        { key: 'medium', label: 'Medium', sub: 'A little more on screen' },
        { key: 'small', label: 'Small', sub: 'As on a computer: the most on screen' },
      ],
      size: () => window.__wygTvSize || 'large',
      setSize: (k) => { try { localStorage.setItem('wyg.tvSize', JSON.stringify(k)); } catch (e) { } location.reload(); },
    },
    update: { check: () => ok({ current: appVersion(), latest: appVersion(), available: false, kind: 'tizen' }), install: no('Updates are installed from a computer.') },
  };
  window.wyg = api;
  document.documentElement.classList.add('tizen');
})();
