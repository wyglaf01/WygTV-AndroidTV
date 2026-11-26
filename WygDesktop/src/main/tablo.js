'use strict';
/**
 * Legacy Tablo network DVRs (the black models sold 2013–2023: Tablo DUAL, QUAD, LITE…) through the local
 * REST server every one runs on port 8885: finding one, its channels, its own program guide, and tuning a
 * channel (each tune gives a fresh HLS link). Same rules as the Android app (Tablo.java).
 *
 * The white 4th Gen Tablo has no open local API (it only talks to Tablo's own apps), so it can't be added.
 * Also runs on iOS (scripts/build-web.mjs), where "./net" is the bridge's web requests.
 */
const net = require('./net');

const PORT = 8885;
const CLOUD_DISCOVER = 'https://api.tablotv.com/assocserver/getipinfo/';
const LOCKED = 'This Tablo answered but refuses other apps (its firmware locked the API that ' +
  'this app uses). The white 4th Gen Tablo works the same way. Its channels can still be watched in the Tablo app.';

/** "192.168.1.60", "192.168.1.60:8885", "http://192.168.1.60/" → "http://192.168.1.60:8885". */
function baseFromInput(input) {
  let s = (input || '').trim();
  if (!s) return '';
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  try {
    const u = new URL(s);
    const port = !u.port || u.port === '80' ? PORT : u.port;
    return 'http://' + u.hostname + ':' + port;
  } catch { return ''; }
}

function isRefusal(o) {
  const e = o && typeof o === 'object' && !Array.isArray(o) ? o.error : null;
  if (!e || typeof e !== 'object') return false;
  return /unauthori|forbidden/i.test(String(e.code || '')) || /authentication/i.test(String(e.description || ''));
}

function locked() { const e = new Error(LOCKED); e.locked = true; return e; }

/** The thrown error from an HTTP 401/403 counts as a refusal too. */
async function ask(fn) {
  try {
    const r = await fn();
    if (isRefusal(r)) throw locked();
    return r;
  } catch (e) {
    if (e.locked) throw e;
    if (/HTTP 40[13]\b/.test(String(e.message))) throw locked();
    throw e;
  }
}

const getJson = (url, timeout) => ask(() => net.json(url, { timeout }));
const postJson = (url, body, timeout) => ask(() => net.postJson(url, body, { timeout }));

/** The cloud answer: {"cpes":[{"serverid":"SID_…","name":"Tablo","private_ip":"192.168.1.60"}]}. */
function parseCloud(j) {
  return (j && Array.isArray(j.cpes) ? j.cpes : []).filter((o) => o && o.private_ip)
    .map((o) => ({ id: o.serverid || '', name: o.name || 'Tablo', base: baseFromInput(o.private_ip) })).filter((d) => d.base);
}

/** /server/info → { id, name, model, tuners, base }. */
function parseServerInfo(j, base) {
  if (isRefusal(j)) throw locked();
  if (!j || typeof j !== 'object' || (!j.server_id && !j.name && !j.model)) throw new Error('not a Tablo');
  const m = j.model || {};
  return { id: j.server_id || '', name: j.name || 'Tablo', model: m.name || '', tuners: m.tuners || 0, base };
}

async function describe(base) {
  const b = baseFromInput(base);
  return parseServerInfo(await getJson(b + '/server/info', 8000), b);
}

/** Tablos on this network, from Tablo's cloud lookup (needs internet), each confirmed on the network. */
async function discoverAll() {
  let cloud = [];
  try { cloud = parseCloud(await net.json(CLOUD_DISCOVER, { timeout: 8000 })); } catch { }
  const out = [];
  await Promise.all(cloud.map(async (d) => {
    try { const full = await describe(d.base); if (!full.name || full.name === 'Tablo') full.name = d.name || 'Tablo'; out.push(full); }
    catch (e) { if (e.locked) out.push(Object.assign({ locked: true, model: '', tuners: 0 }, d)); }
  }));
  return out;
}

/** POSTs paths to /batch, 50 at a time → { path: record }. */
async function batch(base, paths) {
  const out = {};
  for (let i = 0; i < paths.length; i += 50) {
    const got = await postJson(base + '/batch', paths.slice(i, i + 50), 30000);
    for (const [k, v] of Object.entries(got || {})) if (v && typeof v === 'object') out[k] = v;
  }
  return out;
}

function pathList(j) {
  if (isRefusal(j)) throw locked();
  if (!Array.isArray(j)) throw new Error('The Tablo gave no list');
  return j.filter((p) => typeof p === 'string' && p.startsWith('/'));
}

const numberOf = (rec) => {
  const c = rec && rec.channel;
  if (!c || c.major == null) return '';
  return c.minor != null ? c.major + '.' + c.minor : String(c.major);
};

const callSign = (c) => { const s = String(c.call_sign || '').trim(); return s && s !== 'null' ? s : String(c.call_sign_src || '').trim(); };

/** Channel records (path → record) → [{ id, num, name, network, hd }], sorted by number. */
function parseChannels(details) {
  const out = [];
  for (const [path, rec] of Object.entries(details || {})) {
    const c = rec && rec.channel;
    if (!c) continue;
    const id = String(rec.object_id != null ? rec.object_id : (path.match(/(\d+)\/?$/) || [])[1] || '');
    const num = numberOf(rec);
    if (!id || !num) continue;
    const network = String(c.network || '').trim();
    out.push({ id, num, name: callSign(c) || num, network: network === 'null' ? '' : network, hd: /^hd/i.test(String(c.resolution || '')) });
  }
  const key = (n) => n.split('.').map(Number);
  return out.sort((a, b) => { const x = key(a.num), y = key(b.num); return (x[0] - y[0]) || ((x[1] || 0) - (y[1] || 0)); });
}

/** The Tablo's channels: { device, channels: [{ id, num, name, network, hd }] }. */
async function lineup(base) {
  const b = baseFromInput(base);
  const device = await describe(b);
  const paths = pathList(await getJson(b + '/guide/channels', 20000)).filter((p) => p.startsWith('/guide/channels/'));
  return { device, channels: parseChannels(await batch(b, paths)) };
}

/** The watch answer → the HLS link to play. */
function parseWatch(j, base) {
  if (isRefusal(j)) throw locked();
  let url = String(j && j.playlist_url || '').trim();
  if (!url) throw new Error(j && j.error && j.error.description ? j.error.description : 'the Tablo gave no stream link');
  if (url.startsWith('/')) url = 'http://' + new URL(base).hostname + url;
  return url;
}

/** Tunes a channel (by its id) and gives its HLS link. Each tune uses one of the Tablo's tuners while it plays. */
async function watch(base, id) {
  const b = baseFromInput(base);
  try { return parseWatch(await postJson(b + '/guide/channels/' + encodeURIComponent(id) + '/watch', {}, 20000), b); }
  catch (e) {
    if (e.locked) throw e;
    throw new Error('The Tablo couldn’t tune this channel (' + e.message + '). All of its tuners may be in use, or it may be off.');
  }
}

/** "2023-01-03T01:00Z" (also with seconds or an offset) → ms, or NaN. */
const parseTime = (s) => (s ? Date.parse(String(s).replace(/T(\d\d:\d\d)Z$/, 'T$1:00Z')) : NaN);

/**
 * Adds one airing record (an episode, movie airing, sports event or program airing, each with an
 * "airing_details" block) to a guide shaped like an XMLTV one ({ channels, programmes }), keyed by channel number.
 */
function addAiring(rec, numbers, into) {
  const ad = rec && rec.airing_details;
  if (!ad) return false;
  let num = numberOf(ad.channel);
  if (!num) num = numbers[ad.channel_path || (ad.channel && ad.channel.path) || ''] || '';
  const start = parseTime(ad.datetime), dur = Number(ad.duration) || 0;
  if (!num || !(start > 0) || dur <= 0) return false;
  let title = String(ad.show_title || '').trim(), episode = '', desc = '';
  for (const k of ['episode', 'event', 'program', 'movie_airing', 'movie']) {
    const o = rec[k];
    if (!o || typeof o !== 'object') continue;
    if (!episode && (k === 'episode' || k === 'event') && o.title) episode = String(o.title).trim();
    if (!desc && o.description) desc = String(o.description).trim();
    if (!title && o.title) title = String(o.title).trim();
  }
  if (episode === title) episode = '';
  if (episode) desc = desc ? episode + ' — ' + desc : episode;
  into.channels[num] ||= { names: [num], icon: '' };
  const progs = into.programmes[num] ||= [];
  if (!progs.some((p) => p[0] === start)) progs.push([start, start + dur * 1000, title || 'Program', desc]);
  return true;
}

/** The Tablo's own guide, about `hours` ahead, shaped like an XMLTV guide and keyed by channel number. */
async function loadGuide(base, hours) {
  const b = baseFromInput(base);
  const chans = await batch(b, pathList(await getJson(b + '/guide/channels', 20000)));
  const numbers = {};
  for (const [path, rec] of Object.entries(chans)) { const n = numberOf(rec); if (n) { numbers[path] = n; } }
  const out = { channels: {}, programmes: {} };
  for (const [path, rec] of Object.entries(chans)) {
    const n = numbers[path];
    if (n) out.channels[n] = { names: [callSign(rec.channel) || n, n], icon: '' };
  }
  const airings = await batch(b, pathList(await getJson(b + '/guide/airings', 30000)));
  for (const rec of Object.values(airings)) addAiring(rec, numbers, out);
  const now = Date.now(), until = now + hours * 3600000;
  for (const [n, list] of Object.entries(out.programmes)) {
    out.programmes[n] = list.filter((p) => p[1] > now && p[0] < until).sort((x, y) => x[0] - y[0]);
  }
  return out;
}

module.exports = { PORT, LOCKED, baseFromInput, isRefusal, parseCloud, parseServerInfo, parseChannels, parseWatch, parseTime, addAiring,
  describe, discoverAll, lineup, watch, loadGuide };
