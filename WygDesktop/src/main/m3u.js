'use strict';
/** M3U / M3U8 playlists: #EXTINF lines with tvg-* attributes and group-title. */

function attrs(s) {
  const out = {};
  const re = /([\w-]+)\s*=\s*"([^"]*)"/g;
  let m;
  while ((m = re.exec(s))) out[m[1].toLowerCase()] = m[2].trim();
  return out;
}

/** Where a comma separates the attributes from the title (commas inside quotes don't count). */
function titleComma(line) {
  let q = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') q = !q;
    else if (c === ',' && !q) return i;
  }
  return -1;
}

const VOD_EXT = /\.(mp4|mkv|avi|mov|m4v|wmv|webm|flv|mpg|mpeg)(\?|$)/i;

function parse(text) {
  const lines = String(text || '').replace(/^﻿/, '').split(/\r?\n/);
  let guide = '';
  const items = [];
  let cur = null;
  for (let raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXTM3U')) {
      const a = attrs(line);
      guide = (a['url-tvg'] || a['x-tvg-url'] || '').split(',')[0].trim();
    } else if (line.startsWith('#EXTINF')) {
      const comma = titleComma(line);
      const a = attrs(comma >= 0 ? line.slice(0, comma) : line);
      cur = {
        name: (comma >= 0 ? line.slice(comma + 1) : '').trim() || a['tvg-name'] || 'Channel',
        logo: a['tvg-logo'] || a.logo || '', group: a['group-title'] || '', epgId: a['tvg-id'] || '',
        num: a['tvg-chno'] || a['channel-number'] || '', tvgName: a['tvg-name'] || '',
      };
    } else if (line.startsWith('#EXTGRP:')) {
      if (cur && !cur.group) cur.group = line.slice(8).trim();
    } else if (line.startsWith('#EXTVLCOPT:')) {
      const m = /http-user-agent=(.*)/i.exec(line);
      if (cur && m) cur.ua = m[1].trim();
    } else if (!line.startsWith('#')) {
      const it = cur || { name: line.split('/').pop(), logo: '', group: '', epgId: '', num: '' };
      it.url = line;
      it.kind = VOD_EXT.test(line) || /\/(movie|movies)\//i.test(line) ? 'movie' : /\/series\//i.test(line) ? 'episode' : 'live';
      items.push(it);
      cur = null;
    }
  }
  return { guide, items };
}

module.exports = { parse };
