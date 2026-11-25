'use strict';
/** HTTP helpers for the main process (no browser CORS rules apply here). */
const { Readable } = require('stream');

let userAgent = 'WygDesktop/1.0';
function setUserAgent(ua) { if (ua && ua.trim()) userAgent = ua.trim(); }
function getUserAgent() { return userAgent; }

function doFetch(url, opts) {
  let f = globalThis.fetch;
  try {
    const { net } = require('electron');
    if (net && typeof net.fetch === 'function') f = net.fetch.bind(net);
  } catch { /* plain Node (tests) */ }
  return f(url, opts);
}

async function get(url, { timeout = 30000, headers = {} } = {}) {
  if (!/^https?:\/\//i.test(url)) throw new Error('Only http(s) links are supported: ' + url);
  const res = await doFetch(url, {
    headers: { 'User-Agent': userAgent, Accept: '*/*', ...headers },
    signal: AbortSignal.timeout(timeout),
    redirect: 'follow',
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} from ${new URL(url).host}`);
  return res;
}

async function text(url, opts) {
  const res = await get(url, opts);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf[0] === 0x1f && buf[1] === 0x8b) return require('zlib').gunzipSync(buf).toString('utf8');
  return buf.toString('utf8');
}

async function json(url, opts) {
  const t = await text(url, opts);
  try { return JSON.parse(t); } catch { throw new Error('The server did not answer with JSON (' + t.slice(0, 80).replace(/\s+/g, ' ') + '…)'); }
}

async function stream(url, opts) {
  const res = await get(url, opts);
  return Readable.fromWeb(res.body);
}

module.exports = { get, text, json, stream, setUserAgent, getUserAgent };
