'use strict';
/**
 * SiliconDust HDHomeRun network tuners ("Antenna TV"): finding them on the home network, reading a
 * tuner's channel lineup and SiliconDust's free guide for those channels. Same rules as the Android app.
 */
const dgram = require('dgram');
const os = require('os');
const net = require('./net');

const PORT = 65001;
const TYPE_DISCOVER_REQ = 0x0002, TYPE_DISCOVER_RPY = 0x0003;
const TAG_DEVICE_TYPE = 0x01, TAG_DEVICE_ID = 0x02, TAG_TUNER_COUNT = 0x10,
  TAG_LINEUP_URL = 0x27, TAG_BASE_URL = 0x2A, TAG_DEVICE_AUTH = 0x2B;
const CLOUD_DISCOVER = 'https://api.hdhomerun.com/discover';
const GUIDE_API = 'https://api.hdhomerun.com/api/guide.php';

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function tag(t, value) {
  const len = value.length;
  const head = len < 128 ? Buffer.from([t, len]) : Buffer.from([t, (len & 0x7f) | 0x80, len >> 7]);
  return Buffer.concat([head, value]);
}

function be32(v) { const b = Buffer.alloc(4); b.writeUInt32BE(v >>> 0); return b; }

function frame(type, payload) {
  const head = Buffer.alloc(4);
  head.writeUInt16BE(type, 0); head.writeUInt16BE(payload.length, 2);
  const body = Buffer.concat([head, payload]);
  const crc = Buffer.alloc(4); crc.writeUInt32LE(crc32(body));
  return Buffer.concat([body, crc]);
}

function discoverRequest() {
  return frame(TYPE_DISCOVER_REQ, Buffer.concat([tag(TAG_DEVICE_TYPE, be32(0xFFFFFFFF)), tag(TAG_DEVICE_ID, be32(0xFFFFFFFF))]));
}

/** A discovery reply → { id, base, lineupUrl, tuners, auth, isTuner } or null. */
function parseReply(buf, fromIp) {
  if (buf.length < 8 || buf.readUInt16BE(0) !== TYPE_DISCOVER_RPY) return null;
  const len = buf.readUInt16BE(2);
  if (buf.length < 4 + len + 4) return null;
  if (crc32(buf.subarray(0, 4 + len)) !== buf.readUInt32LE(4 + len)) return null;
  const d = { id: '', base: '', lineupUrl: '', tuners: 0, auth: '', isTuner: false };
  let i = 4;
  const end = 4 + len;
  while (i + 2 <= end) {
    const t = buf[i++];
    let l = buf[i++];
    if (l & 0x80) l = (l & 0x7f) | (buf[i++] << 7);
    const v = buf.subarray(i, i + l);
    i += l;
    if (t === TAG_DEVICE_TYPE && l === 4) d.isTuner = v.readUInt32BE(0) === 1;
    else if (t === TAG_DEVICE_ID && l === 4) d.id = v.readUInt32BE(0).toString(16).toUpperCase().padStart(8, '0');
    else if (t === TAG_TUNER_COUNT && l >= 1) d.tuners = v[0];
    else if (t === TAG_BASE_URL) d.base = v.toString('utf8');
    else if (t === TAG_LINEUP_URL) d.lineupUrl = v.toString('utf8');
    else if (t === TAG_DEVICE_AUTH) d.auth = v.toString('utf8');
  }
  if (!d.base && fromIp) d.base = 'http://' + fromIp;
  return d;
}

function broadcastAddresses() {
  const out = new Set(['255.255.255.255']);
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      const ip = a.address.split('.').map(Number), mask = a.netmask.split('.').map(Number);
      out.add(ip.map((o, k) => (o & mask[k]) | (~mask[k] & 255)).join('.'));
    }
  }
  return [...out];
}

function discoverLocal(waitMs = 1500) {
  return new Promise((resolve) => {
    const found = [];
    let sock;
    try { sock = dgram.createSocket({ type: 'udp4', reuseAddr: true }); } catch { resolve(found); return; }
    sock.on('error', () => { try { sock.close(); } catch { } resolve(found); });
    sock.on('message', (msg, rinfo) => {
      const d = parseReply(msg, rinfo.address);
      if (d && d.isTuner && !found.some((f) => f.base === d.base)) found.push(d);
    });
    sock.bind(0, () => {
      try { sock.setBroadcast(true); } catch { }
      const pkt = discoverRequest();
      for (const addr of broadcastAddresses()) sock.send(pkt, PORT, addr, () => { });
      setTimeout(() => { try { sock.close(); } catch { } resolve(found); }, waitMs);
    });
  });
}

async function discoverCloud() {
  try {
    const list = await net.json(CLOUD_DISCOVER, { timeout: 8000 });
    return (Array.isArray(list) ? list : []).filter((o) => o && (o.LocalIP || o.BaseURL) && (o.TunerCount || o.DeviceID))
      .map((o) => o.BaseURL || 'http://' + o.LocalIP);
  } catch { return []; }
}

/** "192.168.1.50", "hdhomerun.local", "http://x/discover.json" … → "http://192.168.1.50". */
function baseFromInput(input) {
  let s = (input || '').trim();
  if (!s) return '';
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  try { const u = new URL(s); return u.protocol + '//' + u.host; } catch { return ''; }
}

/** discover.json of one tuner → { id, name, model, base, lineupUrl, tuners, auth }. */
async function describe(base) {
  const j = await net.json(baseFromInput(base) + '/discover.json', { timeout: 6000 });
  return {
    id: j.DeviceID || '', name: j.FriendlyName || 'HDHomeRun', model: j.ModelNumber || '',
    base: j.BaseURL || baseFromInput(base), lineupUrl: j.LineupURL || baseFromInput(base) + '/lineup.json',
    tuners: j.TunerCount || 0, auth: j.DeviceAuth || '',
  };
}

/** Every tuner found by broadcast and by SiliconDust's cloud lookup, described. */
async function discoverAll() {
  const [local, cloud] = await Promise.all([discoverLocal(), discoverCloud()]);
  const bases = new Set([...local.map((d) => d.base), ...cloud]);
  if (!bases.size) bases.add('http://hdhomerun.local');
  const out = [];
  await Promise.all([...bases].map(async (b) => {
    try { const d = await describe(b); if (!out.some((o) => o.id && o.id === d.id)) out.push(d); } catch { }
  }));
  return out;
}

function isDrm(o) {
  return o.DRM === 1 || String(o.Tags || '').split(',').some((t) => t.trim().toLowerCase() === 'drm');
}

/** lineup.json → channels: [{ id, num, name, url, hd, video, audio }]. Copy-protected channels are skipped. */
async function lineup(base) {
  const d = await describe(base);
  const list = await net.json(d.lineupUrl, { timeout: 15000 });
  return {
    device: d,
    channels: (Array.isArray(list) ? list : []).filter((o) => o && o.URL && !isDrm(o)).map((o) => ({
      id: String(o.GuideNumber || ''), num: String(o.GuideNumber || ''), name: String(o.GuideName || o.GuideNumber || ''),
      url: o.URL, hd: o.HD === 1, video: String(o.VideoCodec || ''), audio: String(o.AudioCodec || ''),
    })),
  };
}

/** guide.php JSON into the same shape as an XMLTV guide, keyed by channel number. */
function parseGuide(list, into) {
  let latest = 0;
  for (const ch of Array.isArray(list) ? list : []) {
    const num = String(ch.GuideNumber || '').trim();
    if (!num || !Array.isArray(ch.Guide)) continue;
    into.channels[num] ||= { names: [ch.GuideName || num], icon: ch.ImageURL || '' };
    const progs = into.programmes[num] ||= [];
    for (const p of ch.Guide) {
      const s = p.StartTime * 1000, e = p.EndTime * 1000;
      if (!(e > s) || progs.some((x) => x[0] === s)) continue;
      let desc = (p.Synopsis || '').trim();
      if (p.EpisodeTitle) desc = desc ? p.EpisodeTitle + ' — ' + desc : p.EpisodeTitle;
      progs.push([s, e, p.Title || 'Program', desc]);
      latest = Math.max(latest, p.EndTime);
    }
    progs.sort((a, b) => a[0] - b[0]);
  }
  return latest;
}

async function loadGuide(base, hours) {
  const d = await describe(base);
  if (!d.auth) throw new Error('The tuner gave no DeviceAuth');
  const out = { channels: {}, programmes: {} };
  const now = Math.floor(Date.now() / 1000), until = now + hours * 3600;
  let start = 0;
  for (let call = 0; call < 8; call++) {
    const url = GUIDE_API + '?DeviceAuth=' + encodeURIComponent(d.auth) + (start ? '&Start=' + start : '');
    const latest = parseGuide(await net.json(url, { timeout: 20000 }), out);
    if (latest <= Math.max(start, now) || latest >= until) break;
    start = latest;
  }
  return out;
}

module.exports = { discoverRequest, parseReply, frame, crc32, discoverAll, describe, lineup, loadGuide, parseGuide, baseFromInput, isDrm };
