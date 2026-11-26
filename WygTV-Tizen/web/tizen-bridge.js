'use strict';
/*
 * WygTV for Samsung Smart TVs (Tizen): stands in for WygDesktop's main process (src/preload.js + src/main/*.js) so
 * the same screens run on the TV, like WygTV-iOS does. Web requests use XMLHttpRequest, which a Tizen app may send
 * to any server (config.xml's <access origin="*">) without browser CORS rules. The remote's keys are mapped to the
 * keys the screens already understand (Back → Escape, CH+/CH− → Page Up/Down, ▶❚❚ → MediaPlayPause…).
 *
 * There is no FFmpeg on a TV: HLS and H.264 MPEG-TS play in the TV's own browser engine; MPEG-2 antenna channels
 * (most HDHomeRun channels) can't (see W.api.noFFmpeg in player.js). Tablo channels are H.264 HLS and play.
 */
(function () {
  const req = window.__wygRequire;

  // ------------------------------------------------------------------ older TVs: what the screens use that 2020 TVs lack
  const addChildren = (proto) => {
    if (proto && !proto.replaceChildren) proto.replaceChildren = function (...nodes) { while (this.lastChild) this.removeChild(this.lastChild); this.append(...nodes); };
  };
  addChildren(Element.prototype); addChildren(DocumentFragment.prototype);
  if (!AbortSignal.timeout) AbortSignal.timeout = (ms) => { const c = new AbortController(); setTimeout(() => c.abort(), ms); return c.signal; };

  // ------------------------------------------------------------------ web requests
  function xhr(method, url, { timeout = 60000, body = null, type = 'arraybuffer', headers = {} } = {}) {
    return new Promise((resolve, reject) => {
      if (!/^https?:\/\//i.test(url)) { reject(new Error('Only HTTP or HTTPS links are supported.')); return; }
      const x = new XMLHttpRequest();
      x.open(method, url, true);
      x.responseType = type;
      x.timeout = timeout;
      for (const [k, v] of Object.entries(headers)) x.setRequestHeader(k, v);
      x.onload = () => resolve(x);
      x.onerror = () => reject(new Error('Could not connect to ' + new URL(url).origin + '. Check the address and the TV’s network.'));
      x.ontimeout = () => reject(new Error(new URL(url).host + ' took too long to answer.'));
      x.send(body);
    });
  }

  const isGzip = (b) => b.length > 2 && b[0] === 0x1f && b[1] === 0x8b;

  async function getBytes(url, timeout = 60000) {
    const r = await xhr('GET', url, { timeout });
    if (r.status < 200 || r.status >= 300) throw new Error(`HTTP ${r.status} from ${new URL(url).host}`);
    const b = new Uint8Array(r.response || new ArrayBuffer(0));
    return isGzip(b) ? window.fflate.gunzipSync(b) : b;
  }

  async function text(url, timeout) { return new TextDecoder('utf-8').decode(await getBytes(url, timeout)); }
  async function json(url, timeout) {
    const t = await text(url, timeout);
    try { return JSON.parse(t); } catch { throw new Error('The server did not answer with JSON (' + t.slice(0, 80).replace(/\s+/g, ' ') + '…)'); }
  }
  async function postJson(url, body, timeout = 30000) {
    const r = await xhr('POST', url, { timeout, type: 'text', body: JSON.stringify(body == null ? {} : body), headers: { 'Content-Type': 'application/json' } });
    let j = null;
    try { j = JSON.parse(r.responseText); } catch { }
    if (r.status < 200 || r.status >= 300) { if (j && j.error) return j; throw new Error(`HTTP ${r.status} from ${new URL(url).host}`); }
    if (j == null) throw new Error('The server did not answer with JSON');
    return j;
  }

  /** Big guides: handed to the XMLTV parser in pieces, so the TV isn't asked for one huge string. */
  function* textChunks(bytes) {
    const dec = new TextDecoder('utf-8');
    const step = 1 << 20;
    for (let i = 0; i < bytes.length; i += step) yield dec.decode(bytes.subarray(i, i + step), { stream: i + step < bytes.length });
  }

  // The desktop modules (m3u.js, xmltv.js, hdhr.js, tablo.js) are reused as they are; this is their "./net".
  window.__wygNet = {
    text: (url, o = {}) => text(url, o.timeout), json: (url, o = {}) => json(url, o.timeout), postJson: (url, body, o = {}) => postJson(url, body, o.timeout),
    setUserAgent: () => { }, getUserAgent: () => navigator.userAgent, // a TV app can't change its User-Agent
  };
  const m3u = req('./m3u'), xmltv = req('./xmltv'), hdhr = req('./hdhr'), tablo = req('./tablo');

  // ------------------------------------------------------------------ TV guides (same rules as src/main/epg.js; kept in memory)
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
          for (const c of textChunks(await getBytes(url, 180000))) p.push(c);
          data = p.finish();
        }
        g.data = data; g.loadedAt = Date.now(); g.byName = null; g.error = '';
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

  // ------------------------------------------------------------------ HDHomeRun: no UDP broadcast in a TV app, so the cloud lookup + hdhomerun.local
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

  // ------------------------------------------------------------------ the remote
  const tz = window.tizen;
  // Keys a Tizen app only receives once registered (arrows, OK, Back and digits always arrive).
  const WANTED = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind', 'MediaTrackPrevious', 'MediaTrackNext',
    'ChannelUp', 'ChannelDown', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue', 'Info', 'Guide', 'ChannelList'];
  try { if (tz && tz.tvinputdevice) for (const k of WANTED) { try { tz.tvinputdevice.registerKey(k); } catch { } } } catch { }
  // Samsung key codes → the keys the shared screens handle.
  const MAP = {
    10009: 'Escape', // Back
    427: 'PageUp', 428: 'PageDown', // CH+ / CH−
    10252: 'MediaPlayPause', 415: 'MediaPlayPause', 19: 'MediaPlayPause',
    413: 'MediaStop', 417: 'ArrowRight', 412: 'ArrowLeft', // ⏩ ⏪ skip in movies
    10232: 'PageUp', 10233: 'PageDown', // previous / next track
    457: 'i', // Info
    458: 'g', // Guide
    403: 'm', // red: options (same as M on a keyboard)
  };
  window.addEventListener('keydown', (e) => {
    const key = MAP[e.keyCode];
    if (!key || e.__wyg) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (key === 'g') { if (window.W && W.go) W.go('guide'); return; }
    const ev = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    ev.__wyg = true;
    (document.activeElement || document.body).dispatchEvent(ev);
  }, true);

  // ------------------------------------------------------------------ the app
  const ok = (v) => Promise.resolve(v);
  const info = () => {
    let version = '1.0.0';
    try { version = tz.application.getAppInfo().version; } catch { }
    return { version, platform: 'tizen', arch: 'tv', brand: 'WygTV' };
  };
  const quit = () => { try { tz.application.getCurrentApplication().exit(); } catch { window.close(); } };
  const REL = 'https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygtv-tizen-latest/';
  const newer = (a, b) => {
    const pa = String(a).split('.').map(Number), pb = String(b).split('.').map(Number);
    for (let i = 0; i < 3; i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) > (pb[i] || 0);
    return false;
  };

  const api = {
    brand: 'WygTV', platform: 'tizen', noFFmpeg: true, isTV: true,
    onUpdateProgress: () => { },
    app: { info: async () => info() },
    net: { setUserAgent: () => ok(), text: (u, t) => text(u, t), json: (u, t) => json(u, t) },
    m3u: {
      load: async (url) => {
        if (/^https?:/i.test(url)) return m3u.parse(await text(url, 120000));
        throw new Error('On a TV, playlists are added by their link (http://…)');
      },
    },
    hdhr: { discover: hdhrDiscover, describe: (b) => hdhr.describe(b), lineup: (b) => hdhr.lineup(b) },
    tablo: { discover: () => tablo.discoverAll(), describe: (b) => tablo.describe(b), lineup: (b) => tablo.lineup(b), watch: (b, id) => tablo.watch(b, id) },
    epg: {
      ensure: async (urls, force) => { await Promise.all((urls || []).filter(Boolean).map((u) => loadGuide(u, force).catch(() => { }))); return guideStatus(); },
      status: () => ok(guideStatus()),
      query: (items, from, to) => ok(guideQuery(items, from, to)),
    },
    stream: {
      probe: () => Promise.reject(new Error('no FFmpeg on a TV')),
      start: () => Promise.reject(new Error('no FFmpeg on a TV')),
      stop: () => ok(), log: () => ok(''),
    },
    shell: { open: () => ok() },
    win: { fullscreen: () => ok(true), quit: () => ok(quit()) },
    ext: {
      play: () => Promise.reject(new Error('Samsung TVs can’t hand a video to another app.')),
      pick: () => ok(''),
    },
    file: {
      // Backups: shown as text to copy is impractical on a TV, so they are kept on the TV itself.
      save: async (name, data) => { localStorage.setItem('wyg.backup', data); return 'this TV (Settings → Restore a backup brings it back)'; },
      open: async () => { const t = localStorage.getItem('wyg.backup'); return t ? { path: 'backup', text: t } : null; },
    },
    update: {
      check: async () => {
        const cur = info().version;
        const i = await json(REL + 'tizen-version.json?t=' + Date.now(), 15000);
        return { current: cur, latest: i.version, available: newer(i.version, cur), kind: 'tizen', url: REL + 'WygTV.wgt', notes: 'Install the new WygTV.wgt from a computer with Samsung’s Tizen tools (see WygTV-Tizen/README.md).' };
      },
      install: async () => { throw new Error('On a Samsung TV, updates are installed from a computer (see WygTV-Tizen/README.md).'); },
    },
  };
  window.wyg = api;
})();
