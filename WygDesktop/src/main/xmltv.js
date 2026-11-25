'use strict';
/**
 * Streaming XMLTV reader. Guides can be hundreds of megabytes, so the text is scanned chunk by chunk and
 * only programmes inside a time window (by default 4 hours back to 36 hours ahead) are kept.
 *
 * Result: { channels: { id: [display names] }, programmes: { id: [[startMs, endMs, title, desc], …] } }
 */
const zlib = require('zlib');

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };

function decode(s) {
  if (!s) return '';
  s = s.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1');
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
    if (e[0] === '#') {
      const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      try { return String.fromCodePoint(code); } catch { return m; }
    }
    return ENTITIES[e.toLowerCase()] ?? m;
  }).trim();
}

/** "20261122030000 +0100" → epoch ms. Missing offset means UTC. */
function parseTime(s) {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?(?:\s*([+-]\d{2}):?(\d{2})?)?/.exec((s || '').trim());
  if (!m) return NaN;
  const utc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  if (!m[7]) return utc;
  const sign = m[7][0] === '-' ? -1 : 1;
  const off = sign * (Math.abs(parseInt(m[7], 10)) * 60 + parseInt(m[8] || '0', 10));
  return utc - off * 60000;
}

function attr(tag, name) {
  const m = new RegExp('\\s' + name + '\\s*=\\s*("([^"]*)"|\'([^\']*)\')').exec(tag);
  return m ? decode(m[2] ?? m[3]) : '';
}

function firstText(body, tag) {
  const m = new RegExp('<' + tag + '(?:\\s[^>]*)?>([\\s\\S]*?)</' + tag + '>').exec(body);
  return m ? decode(m[1]) : '';
}

class Parser {
  constructor(opts = {}) {
    const now = opts.now ?? Date.now();
    this.from = opts.from ?? now - 4 * 3600e3;
    this.to = opts.to ?? now + 36 * 3600e3;
    this.buf = '';
    this.channels = {};
    this.programmes = {};
    this.count = 0;
  }

  push(text) {
    this.buf += text;
    let pos = 0;
    const re = /<(channel|programme)\b([^>]*?)(\/>|>([\s\S]*?)<\/\1\s*>)/g;
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(this.buf))) {
      pos = re.lastIndex;
      if (m[1] === 'channel') this.channel(m[2], m[4] || '');
      else this.programme(m[2], m[4] || '');
    }
    // Keep only the unfinished tail (from the last opening tag we could not complete).
    let tail = this.buf.slice(pos);
    const open = tail.search(/<(channel|programme)\b/);
    this.buf = open >= 0 ? tail.slice(open) : tail.slice(-20);
  }

  channel(attrs, body) {
    const id = attr(attrs, 'id');
    if (!id) return;
    const names = [];
    const re = /<display-name[^>]*>([\s\S]*?)<\/display-name>/g;
    let m;
    while ((m = re.exec(body))) { const n = decode(m[1]); if (n && !names.includes(n)) names.push(n); }
    const icon = /<icon[^>]*src\s*=\s*"([^"]*)"/.exec(body);
    this.channels[id] = { names, icon: icon ? decode(icon[1]) : '' };
  }

  programme(attrs, body) {
    const start = parseTime(attr(attrs, 'start'));
    const stop = parseTime(attr(attrs, 'stop'));
    const ch = attr(attrs, 'channel');
    if (!ch || !(start < this.to) || !(stop > this.from) || !(stop > start)) return;
    let title = firstText(body, 'title') || 'Program';
    const sub = firstText(body, 'sub-title');
    let desc = firstText(body, 'desc');
    if (sub) desc = desc ? sub + ' — ' + desc : sub;
    (this.programmes[ch] ||= []).push([start, stop, title, desc]);
    this.count++;
  }

  finish() {
    for (const id of Object.keys(this.programmes)) {
      const list = this.programmes[id].sort((a, b) => a[0] - b[0]);
      const out = [];
      for (const p of list) if (!out.length || out[out.length - 1][0] !== p[0]) out.push(p);
      this.programmes[id] = out;
    }
    return { channels: this.channels, programmes: this.programmes };
  }
}

/** Parses a whole guide from a Buffer (gzip or plain). */
function parseBuffer(buf, opts) {
  if (buf[0] === 0x1f && buf[1] === 0x8b) buf = zlib.gunzipSync(buf);
  const p = new Parser(opts);
  const CHUNK = 1 << 20;
  for (let i = 0; i < buf.length; i += CHUNK) p.push(buf.toString('utf8', i, Math.min(buf.length, i + CHUNK)));
  return p.finish();
}

/** Parses a guide from a stream of Buffers (a download), un-gzipping on the fly when needed. */
function parseStream(readable, opts) {
  return new Promise((resolve, reject) => {
    const p = new Parser(opts);
    let decoder = null, first = true;
    const { StringDecoder } = require('string_decoder');
    const sd = new StringDecoder('utf8');
    const feed = (chunk) => p.push(sd.write(chunk));
    readable.on('error', reject);
    readable.on('data', (chunk) => {
      if (first) {
        first = false;
        if (chunk[0] === 0x1f && chunk[1] === 0x8b) {
          decoder = zlib.createGunzip();
          decoder.on('data', feed);
          decoder.on('error', reject);
          decoder.on('end', () => { p.push(sd.end()); resolve(p.finish()); });
        }
      }
      if (decoder) decoder.write(chunk); else feed(chunk);
    });
    readable.on('end', () => {
      if (decoder) decoder.end();
      else { p.push(sd.end()); resolve(p.finish()); }
    });
  });
}

/** Lower-case, accents and punctuation stripped, trailing HD/FHD/4K etc. dropped: for matching channels by name. */
function nameKey(s) {
  return (s || '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase()
    .replace(/^\s*[\[(|]?[a-z]{2,3}[\])|:]\s*[-:|]?\s*/, '')
    .replace(/\b(uhd|fhd|hd|sd|4k|hevc|h265|720p|1080p|backup|east|\+1)\b/g, ' ')
    .replace(/[^\p{L}\p{N}]+/gu, '').trim();
}

module.exports = { Parser, parseBuffer, parseStream, parseTime, decode, nameKey };
