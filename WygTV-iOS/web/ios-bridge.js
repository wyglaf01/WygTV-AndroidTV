'use strict';
/*
 * WygTV-iOS: stands in for WygDesktop's main process (src/preload.js + src/main/*.js) so the same screens
 * run on iPhone and iPad. Web requests go through iOS itself (CapacitorHttp: no CORS rules), guides are
 * cached in the app's Caches folder, backups go to Files › WygTV-iOS, and the "external player" is VLC.
 *
 * There is no FFmpeg on iOS: HLS plays in Apple's own player, other formats either play directly or are
 * handed to VLC (see W.api.noFFmpeg in player.js).
 */
(function () {
  const cap = window.capacitorExports;
  const plug = (name) => cap.registerPlugin(name);
  const Http = cap.CapacitorHttp;
  const App = plug('App');
  const Launcher = plug('AppLauncher');
  const Files = plug('Filesystem');
  const Prefs = plug('Preferences');
  const req = window.__wygRequire;

  // ------------------------------------------------------------------ the screen: TV-style layout needs ~1000 px
  (function viewport() {
    const long = Math.max(screen.width, screen.height);
    const m = document.querySelector('meta[name=viewport]');
    if (m && long < 1000) m.content = 'width=1000, user-scalable=no';
    document.documentElement.classList.add('ios', long < 1000 ? 'phone' : 'tablet');
  })();

  // ------------------------------------------------------------------ web requests
  let userAgent = 'WygTV-iOS/1.0';
  const b64ToBytes = async (b64) => new Uint8Array(await (await fetch('data:application/octet-stream;base64,' + b64)).arrayBuffer());
  const isGzip = (b) => b.length > 2 && b[0] === 0x1f && b[1] === 0x8b;

  async function getBytes(url, timeout = 60000) {
    if (!/^https?:\/\//i.test(url)) throw new Error('Only HTTP or HTTPS links are supported.');
    let r;
    try {
      r = await Http.get({ url, headers: { 'User-Agent': userAgent, Accept: '*/*' }, responseType: 'arraybuffer', connectTimeout: Math.min(timeout, 20000), readTimeout: timeout });
    } catch (error) {
      const message = String(error && error.message || error);
      const code = String(error && error.code || '');
      const server = new URL(url).origin;
      if (/App Transport Security|-1022/i.test(message + ' ' + code)) {
        throw new Error('iOS blocked the HTTP connection to ' + server + '. Install the latest WygTV-iOS update, then reload this playlist.');
      }
      if (/TLS|SSL|certificate|secure connection|-120[0-6]/i.test(message + ' ' + code)) {
        throw new Error('The secure connection to ' + server + ' failed. Check the exact http:// or https:// address and port supplied by your provider. If HTTPS is correct, ask the provider to repair its TLS/certificate configuration.');
      }
      throw new Error('Could not connect to ' + server + '. Check the server address, network and provider, then reload the playlist.');
    }
    if (r.status < 200 || r.status >= 300) throw new Error(`HTTP ${r.status} from ${new URL(url).host}`);
    if (typeof r.data !== 'string') return new TextEncoder().encode(typeof r.data === 'object' ? JSON.stringify(r.data) : String(r.data ?? ''));
    return b64ToBytes(r.data);
  }

  /** Text as a stream of strings, un-gzipping when needed (guides can be big). */
  async function* textChunks(bytes) {
    let s = new Blob([bytes]).stream();
    if (isGzip(bytes)) {
      if (typeof DecompressionStream === 'undefined') throw new Error('This guide is gzip-compressed, which needs iOS 16.4 or newer');
      s = s.pipeThrough(new DecompressionStream('gzip'));
    }
    const rd = s.pipeThrough(new TextDecoderStream()).getReader();
    for (;;) { const { done, value } = await rd.read(); if (done) return; yield value; }
  }

  async function text(url, timeout) {
    let out = '';
    for await (const c of textChunks(await getBytes(url, timeout))) out += c;
    return out;
  }
  async function json(url, timeout) {
    const t = await text(url, timeout);
    try { return JSON.parse(t); } catch { throw new Error('The server did not answer with JSON (' + t.slice(0, 80).replace(/\s+/g, ' ') + '…)'); }
  }

  // The desktop modules (m3u.js, xmltv.js, hdhr.js) are reused as they are; this is their "./net".
  window.__wygNet = {
    text: (url, o = {}) => text(url, o.timeout), json: (url, o = {}) => json(url, o.timeout),
    setUserAgent: (ua) => { if (ua && ua.trim()) userAgent = ua.trim(); }, getUserAgent: () => userAgent,
  };
  const m3u = req('./m3u'), xmltv = req('./xmltv'), hdhr = req('./hdhr');

  // ------------------------------------------------------------------ TV guides (same rules as src/main/epg.js)
  const MAX_AGE = 12 * 3600e3;
  const guides = new Map();
  const cacheName = async (url) => {
    const d = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(url));
    return 'guides/' + [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('') + '.json';
  };
  async function loadGuide(url, force) {
    let g = guides.get(url);
    if (!g) { g = {}; guides.set(url, g); }
    if (g.loading) return g.loading;
    if (!force && g.data && Date.now() - g.loadedAt < MAX_AGE) return;
    g.loading = (async () => {
      try {
        const path = await cacheName(url);
        if (!force && !g.data) {
          try {
            const saved = JSON.parse((await Files.readFile({ path, directory: 'CACHE', encoding: 'utf8' })).data);
            if (Date.now() - saved.loadedAt < MAX_AGE) { g.data = saved.data; g.loadedAt = saved.loadedAt; g.byName = null; return; }
          } catch { /* not cached yet */ }
        }
        let data;
        if (url.startsWith('hdhr:')) data = await hdhr.loadGuide(url.slice(5), 24);
        else {
          const p = new xmltv.Parser();
          for await (const c of textChunks(await getBytes(url, 180000))) p.push(c);
          data = p.finish();
        }
        g.data = data; g.loadedAt = Date.now(); g.byName = null; g.error = '';
        Files.writeFile({ path, directory: 'CACHE', encoding: 'utf8', recursive: true, data: JSON.stringify({ loadedAt: g.loadedAt, data }) }).catch(() => { });
      } catch (e) { g.error = String(e.message || e); throw e; } finally { g.loading = null; }
    })();
    return g.loading;
  }
  function guideStatus() {
    const out = {};
    for (const [url, g] of guides) out[url] = { loadedAt: g.loadedAt || 0, error: g.error || '', loading: !!g.loading, channels: g.data ? Object.keys(g.data.programmes).length : 0 };
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

  // ------------------------------------------------------------------ HDHomeRun: no broadcast on iOS, so the cloud lookup + hdhomerun.local
  async function hdhrDiscover() {
    const bases = new Set(['http://hdhomerun.local']);
    try {
      const list = await json('https://api.hdhomerun.com/discover', 8000);
      for (const o of Array.isArray(list) ? list : []) if (o && (o.LocalIP || o.BaseURL) && (o.TunerCount || o.DeviceID)) bases.add(o.BaseURL || 'http://' + o.LocalIP);
    } catch { }
    const out = [];
    await Promise.all([...bases].map(async (b) => { try { const d = await hdhr.describe(b); if (!out.some((o) => o.id && o.id === d.id)) out.push(d); } catch { } }));
    return out;
  }

  // ------------------------------------------------------------------ external players (by their URL schemes)
  const PLAYERS = {
    VLC: (u) => 'vlc-x-callback://x-callback-url/stream?url=' + encodeURIComponent(u),
    Infuse: (u) => 'infuse://x-callback-url/play?url=' + encodeURIComponent(u),
    nPlayer: (u) => 'nplayer-' + u,
    Outplayer: (u) => 'outplayer://' + u,
  };
  async function openUrl(url) {
    const r = await Launcher.openUrl({ url });
    return r && r.completed !== false;
  }
  async function extPlay(name, url) {
    const p = PLAYERS[name] ? name : 'VLC';
    if (!(await openUrl(PLAYERS[p](url)))) throw new Error(p + ' isn’t installed. Get it free from the App Store, or pick another player in Settings → Player.');
    return p;
  }

  // ------------------------------------------------------------------ files
  async function fileSave(name, data) {
    await Files.writeFile({ path: name, data, directory: 'DOCUMENTS', encoding: 'utf8' });
    return 'Files › On My ' + deviceName() + ' › WygTV-iOS › ' + name;
  }
  function fileOpen() {
    return new Promise((resolve) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.json,.m3u,.m3u8,.txt,application/json,text/plain,audio/x-mpegurl';
      input.onchange = async () => {
        const f = input.files && input.files[0];
        if (!f) return resolve(null);
        const t = await f.text();
        // Playlists picked from Files are kept inside the app, under a pretend path m3u:load understands.
        const path = 'file:' + f.name;
        try { localStorage.setItem('wyg.file.' + path, t); } catch { }
        resolve({ path, text: t });
      };
      input.oncancel = () => resolve(null);
      input.click();
    });
  }
  const deviceName = () => (/iPad/.test(navigator.userAgent) || (navigator.maxTouchPoints > 1 && /Macintosh/.test(navigator.userAgent)) ? 'iPad' : 'iPhone');

  // ------------------------------------------------------------------ updates: AltStore installs and updates the app
  const REL = 'https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygtv-ios-latest/';
  const newer = (a, b) => {
    const pa = String(a).split('.').map(Number), pb = String(b).split('.').map(Number);
    for (let i = 0; i < 3; i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) > (pb[i] || 0);
    return false;
  };

  const ok = (v) => Promise.resolve(v);
  const api = {
    brand: 'WygTV-iOS', platform: 'ios', noFFmpeg: true,
    onUpdateProgress: () => { },
    app: { info: async () => { let i = {}; try { i = await App.getInfo(); } catch { } return { version: i.version || '1.0.0', platform: 'ios', arch: 'arm64', brand: 'WygTV-iOS', device: deviceName() }; } },
    net: { setUserAgent: (ua) => ok(window.__wygNet.setUserAgent(ua)), text: (u, t) => text(u, t), json: (u, t) => json(u, t) },
    m3u: {
      load: async (url) => {
        if (/^https?:/i.test(url)) return m3u.parse(await text(url, 120000));
        const t = localStorage.getItem('wyg.file.' + url);
        if (t == null) throw new Error('Pick the playlist file again (Settings → Playlists → Edit → Pick a file)');
        return m3u.parse(t);
      },
    },
    hdhr: { discover: hdhrDiscover, describe: (b) => hdhr.describe(b), lineup: (b) => hdhr.lineup(b) },
    epg: {
      ensure: async (urls, force) => { await Promise.all((urls || []).filter(Boolean).map((u) => loadGuide(u, force).catch(() => { }))); return guideStatus(); },
      status: () => ok(guideStatus()),
      query: (items, from, to) => ok(guideQuery(items, from, to)),
    },
    stream: {
      probe: () => Promise.reject(new Error('no FFmpeg on iOS')),
      start: () => Promise.reject(new Error('no FFmpeg on iOS')),
      stop: () => ok(), log: () => ok(''),
    },
    shell: { open: (url) => (/^https?:/.test(url) ? openUrl(url) : ok()) },
    win: { fullscreen: () => ok(true), quit: () => ok() },
    ext: {
      play: (name, url) => extPlay(name, url),
      pick: async () => {
        const names = Object.keys(PLAYERS);
        const i = await W.menu('External player', names.map((n) => ({ label: n, checked: (W.settings.extPlayer || 'VLC') === n })), { text: 'Used by “Open in VLC” for channels and videos WygTV-iOS can’t play itself.' });
        return i >= 0 ? names[i] : '';
      },
    },
    file: { save: fileSave, open: fileOpen },
    update: {
      check: async () => {
        const cur = (await api.app.info()).version;
        const info = await json(REL + 'ios-version.json?t=' + Date.now(), 15000);
        return { current: cur, latest: info.version, available: newer(info.version, cur), kind: 'ios', url: REL + 'WygTV-iOS.ipa', notes: info.notes || '' };
      },
      install: async (info) => {
        if (!(await openUrl('altstore://install?url=' + encodeURIComponent(info.url)))) await openUrl(info.url);
        return 'opened';
      },
    },
  };
  window.wyg = api;

  // Touch (tap, touch and hold, swipe from the left edge, double-tap) is handled by the shared screens: WygDesktop/src/renderer/js/nav.js.

  // ------------------------------------------------------------------ self-test used by CI in the iPad Simulator (see .github/workflows/ios.yml)
  window.addEventListener('load', async () => {
    let base = '';
    try { base = (await Prefs.get({ key: 'smoke' })).value || ''; } catch { }
    if (!base) return;
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const step = async (name, ms = 2500) => { await sleep(ms); console.log('WYG-STEP ' + name); await sleep(1500); };
    const out = { ok: false, ua: navigator.userAgent };
    try {
      await sleep(1500);
      W.settings.playlists = [{ id: 'smoke', type: 'xtream', name: 'Smoke test', host: base, user: 'demo', pass: 'demo' }];
      W.saveSettings();
      await W.data.loadAll();
      out.channels = W.data.allChannels().length;
      out.playlistError = (W.data.lists.get('smoke') || {}).error || '';
      W.home(); await step('home');
      const play = async (key, wait, expectVlc) => {
        W.player.playChannel(W.data.channel(key), { full: true });
        const t0 = Date.now();
        while (Date.now() - t0 < wait) {
          await sleep(500);
          const v = document.querySelector('#player video');
          const msg = document.querySelector('.player-msg:not([hidden])');
          if (expectVlc && msg && /VLC/.test(msg.textContent)) return { msg: msg.textContent, w: v ? v.videoWidth : 0 };
          if (!expectVlc && v && v.currentTime > 3) return { played: v.currentTime, w: v.videoWidth, src: (v.currentSrc || '').slice(0, 80) };
        }
        const v = document.querySelector('#player video');
        return { played: v ? v.currentTime : 0, err: v && v.error ? v.error.code + ' ' + v.error.message : '', msg: (document.querySelector('.player-msg') || {}).textContent || '' };
      };
      out.hls = await play('smoke|100', 40000);
      await step('player', 500);
      W.player.stop(); await sleep(500);
      out.mpeg2 = await play('smoke|104', 30000, true); // expected not to play: it should say to use VLC
      await step('mpeg2-message', 500);
      W.player.stop();
      await W.guide.ensureAll();
      const g = await W.guide.get(W.data.allChannels());
      out.guideChannels = Object.values(g).filter((l) => l.length).length;
      W.go('live'); await step('live', 4000);
      W.go('guide'); await step('guide', 4000);
      await W.data.loadAllVod('vod'); await W.data.loadAllVod('series');
      out.movies = W.data.vodItems('vod').length; out.series = W.data.vodItems('series').length;
      W.go('vod', { kind: 'vod' }); await step('movies', 4000);
      W.go('settings'); await step('settings');
      out.ok = out.channels > 0 && out.hls.played > 3 && /VLC/.test(out.mpeg2.msg || '') && out.guideChannels > 0 && out.movies > 0;
    } catch (e) { out.error = String(e && e.stack || e); }
    console.log('WYG-SMOKE ' + JSON.stringify(out));
    window.__done = true; // for scripted browser checks
  });
})();

