'use strict';
/**
 * Every TV guide WygDesktop knows about: the playlists' own XMLTV guides, the supplemental guides from
 * Settings and HDHomeRun's guide for antenna tuners. Guides are downloaded at most every 12 hours,
 * cached on disk and asked for by channel (guide id first, then channel name).
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const net = require('./net');
const xmltv = require('./xmltv');
const hdhr = require('./hdhr');

const MAX_AGE = 12 * 3600e3;

class EpgStore {
  constructor(dir) {
    this.dir = dir;
    fs.mkdirSync(dir, { recursive: true });
    this.guides = new Map(); // url → { data, loadedAt, error, loading: Promise, byName: Map }
  }

  file(url) { return path.join(this.dir, crypto.createHash('sha1').update(url).digest('hex') + '.json'); }

  status() {
    const out = {};
    for (const [url, g] of this.guides) {
      out[url] = {
        loadedAt: g.loadedAt || 0, error: g.error || '', loading: !!g.loading,
        channels: g.data ? Object.keys(g.data.programmes).length : 0,
      };
    }
    return out;
  }

  /** Makes sure each guide is loaded (from disk if fresh, else downloaded). Resolves when all have settled. */
  async ensure(urls, force = false) {
    await Promise.all(urls.filter(Boolean).map((u) => this.load(u, force).catch(() => {})));
    return this.status();
  }

  load(url, force) {
    let g = this.guides.get(url);
    if (!g) { g = {}; this.guides.set(url, g); }
    if (g.loading) return g.loading;
    if (!force && g.data && Date.now() - g.loadedAt < MAX_AGE) return Promise.resolve();
    g.loading = (async () => {
      try {
        const f = this.file(url);
        if (!force && !g.data && fs.existsSync(f)) {
          const saved = JSON.parse(await fs.promises.readFile(f, 'utf8'));
          if (Date.now() - saved.loadedAt < MAX_AGE) { this.set(g, saved.data, saved.loadedAt); return; }
        }
        const data = url.startsWith('hdhr:') ? await hdhr.loadGuide(url.slice(5), 24)
          : await xmltv.parseStream(await net.stream(url, { timeout: 180000 }));
        this.set(g, data, Date.now());
        g.error = '';
        await fs.promises.writeFile(f, JSON.stringify({ loadedAt: g.loadedAt, data }));
      } catch (e) {
        g.error = String(e.message || e);
        throw e;
      } finally {
        g.loading = null;
      }
    })();
    return g.loading;
  }

  set(g, data, loadedAt) {
    g.data = data; g.loadedAt = loadedAt; g.byName = null;
  }

  byName(g) {
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

  /**
   * items: [{ key, epgId, name, guides: [url, …] }] in the order the guides should be tried.
   * Returns { key: [[start, end, title, desc], …] } for programmes overlapping [from, to].
   */
  query(items, from, to) {
    const out = {};
    for (const it of items) {
      for (const url of it.guides || []) {
        const g = this.guides.get(url);
        if (!g || !g.data) continue;
        let list = it.epgId ? g.data.programmes[it.epgId] : null;
        if (!list && it.name) {
          const id = this.byName(g).get(xmltv.nameKey(it.name));
          if (id) list = g.data.programmes[id];
        }
        if (!list || !list.length) continue;
        const hit = list.filter((p) => p[1] > from && p[0] < to);
        if (hit.length) { out[it.key] = hit; break; }
      }
    }
    return out;
  }
}

module.exports = { EpgStore };
