'use strict';
/*
 * Playlists (Xtream Codes, M3U links/files, antenna tuners: HDHomeRun, or a black Tablo saved as type 'hdhr'
 * with tablo: true), their channels, movies and
 * series, and the TV guide lookups.
 */
(function () {
  const api = () => W.api;
  const enc = encodeURIComponent;
  const D = W.data = { lists: new Map(), byKey: new Map(), events: new EventTarget() };

  D.emit = (name, detail) => D.events.dispatchEvent(new CustomEvent(name, { detail }));
  D.on = (name, fn) => { D.events.addEventListener(name, fn); return () => D.events.removeEventListener(name, fn); };

  const host = (pl) => (pl.host || '').trim().replace(/\/+$/, '');
  const xbase = (pl) => `${host(pl)}/player_api.php?username=${enc(pl.user)}&password=${enc(pl.pass)}`;
  D.xbase = xbase;

  /** The playlist's own TV guide link. */
  D.providerGuide = (pl, st) => {
    if (pl.type === 'hdhr') return (pl.tablo ? 'tablo:' : 'hdhr:') + pl.base; // a tuner's own guide
    if (pl.epgUrl) return pl.epgUrl.trim();
    if (pl.type === 'xtream') return `${host(pl)}/xmltv.php?username=${enc(pl.user)}&password=${enc(pl.pass)}`;
    return st && st.m3uGuide || '';
  };

  D.guidesFor = (ch) => {
    const st = D.lists.get(ch.pid);
    const own = st ? D.providerGuide(st.pl, st) : '';
    const extras = ch.hdhr ? [] : (W.settings.extraGuides || []);
    const list = W.settings.extraFirst ? [...extras, own] : [own, ...extras];
    return [...new Set(list.filter(Boolean))];
  };

  D.allGuideUrls = () => {
    const out = new Set();
    for (const st of D.lists.values()) { const g = D.providerGuide(st.pl, st); if (g) out.add(g); }
    for (const g of W.settings.extraGuides || []) out.add(g);
    return [...out];
  };

  D.playlistName = (pl) => pl.name || (pl.type === 'hdhr' ? (pl.tablo ? 'Tablo' : 'Antenna TV') : pl.type === 'xtream' ? (() => { try { return new URL(pl.host).hostname; } catch { return 'Xtream'; } })() : 'M3U playlist');

  // ------------------------------------------------------------------ loading
  D.loadAll = async function loadAll() {
    const pls = W.settings.playlists || [];
    for (const id of [...D.lists.keys()]) if (!pls.some((p) => p.id === id)) D.lists.delete(id);
    await Promise.all(pls.map((pl) => D.loadLive(pl).catch(() => {})));
    D.reindex();
    D.emit('loaded');
    W.guide.ensureAll();
  };

  D.state = (pl) => {
    let st = D.lists.get(pl.id);
    if (!st) { st = { pl, live: null, vod: null, series: null, error: '', loading: false }; D.lists.set(pl.id, st); }
    st.pl = pl;
    return st;
  };

  D.loadLive = async function loadLive(pl, force) {
    const st = D.state(pl);
    if (st.live && !force) return st;
    st.loading = true; st.error = '';
    D.emit('status');
    try {
      if (pl.type === 'xtream') {
        const info = await api().net.json(xbase(pl), 30000);
        if (info && info.user_info && info.user_info.auth === 0) throw new Error('The provider refused the username or password');
        st.info = info && info.user_info || {};
        const formats = (st.info.allowed_output_formats || []).map(String);
        const ext = W.settings.liveFormat === 'ts' ? 'ts' : W.settings.liveFormat === 'hls' ? 'm3u8'
          : formats.includes('m3u8') ? 'm3u8' : 'ts';
        const [cats, streams] = await Promise.all([
          api().net.json(xbase(pl) + '&action=get_live_categories', 60000),
          api().net.json(xbase(pl) + '&action=get_live_streams', 120000),
        ]);
        const h = host(pl);
        st.live = {
          cats: (cats || []).map((c) => ({ id: String(c.category_id), name: c.category_name || 'Category' })),
          chans: (streams || []).map((s) => ({
            key: `${pl.id}|${s.stream_id}`, pid: pl.id, id: s.stream_id, name: (s.name || '').trim() || 'Channel',
            logo: s.stream_icon || '', cat: String(s.category_id ?? ''), epgId: s.epg_channel_id || '', num: s.num ? String(s.num) : '',
            url: `${h}/live/${enc(pl.user)}/${enc(pl.pass)}/${s.stream_id}.${ext}`, alt: `${h}/live/${enc(pl.user)}/${enc(pl.pass)}/${s.stream_id}.${ext === 'ts' ? 'm3u8' : 'ts'}`,
          })),
        };
      } else if (pl.type === 'm3u') {
        const r = await api().m3u.load(pl.url);
        st.m3uGuide = r.guide || '';
        const groups = new Map();
        const chans = [], movies = [];
        r.items.forEach((it, i) => {
          const group = it.group || 'Other';
          if (it.kind === 'live') {
            if (!groups.has(group)) groups.set(group, { id: group, name: group });
            chans.push({ key: `${pl.id}|${i}`, pid: pl.id, id: i, name: it.name, logo: it.logo, cat: group, epgId: it.epgId, num: it.num, url: it.url, tvgName: it.tvgName });
          } else {
            movies.push({ key: `${pl.id}|m|${i}`, kind: 'movie', pid: pl.id, id: i, name: it.name, poster: it.logo, cat: group, url: it.url });
          }
        });
        st.live = { cats: [...groups.values()], chans };
        const mg = [...new Set(movies.map((m) => m.cat))].map((g) => ({ id: g, name: g }));
        st.vod = { cats: mg, items: movies };
        st.series = { cats: [], items: [] };
      } else if (pl.type === 'hdhr' && pl.tablo) {
        // A black Tablo: channels have no fixed link; the player asks the Tablo to tune one as it plays it.
        const r = await api().tablo.lineup(pl.base);
        st.device = r.device;
        st.live = {
          cats: [{ id: 'antenna', name: 'All antenna channels' }],
          chans: r.channels.map((c) => ({
            key: `${pl.id}|${c.num}`, pid: pl.id, id: c.num, name: c.name + (c.hd ? ' HD' : '') + (c.network && !c.name.toUpperCase().includes(c.network.toUpperCase()) ? ' (' + c.network + ')' : ''),
            logo: '', cat: 'antenna', epgId: c.num, num: c.num, url: '', hdhr: true, tablo: { base: pl.base, id: c.id },
          })),
        };
        st.vod = { cats: [], items: [] }; st.series = { cats: [], items: [] };
      } else if (pl.type === 'hdhr') {
        const r = await api().hdhr.lineup(pl.base);
        st.device = r.device;
        const cat = { id: 'antenna', name: 'All antenna channels' };
        st.live = {
          cats: [cat],
          chans: r.channels.map((c) => ({
            key: `${pl.id}|${c.num}`, pid: pl.id, id: c.num, name: c.name, logo: '', cat: 'antenna', epgId: c.num, num: c.num, url: c.url,
            hdhr: true, convert: /h264|avc/i.test(c.video) ? 'copy' : 'x264',
          })),
        };
        st.vod = { cats: [], items: [] }; st.series = { cats: [], items: [] };
      }
    } catch (e) {
      st.error = e.message || String(e);
      st.live = st.live || { cats: [], chans: [] };
    } finally {
      st.loading = false;
      D.emit('status');
    }
    return st;
  };

  D.loadVod = async function loadVod(pl, kind) {
    const st = D.state(pl);
    if (st[kind]) return st[kind];
    if (pl.type !== 'xtream') return (st[kind] = st[kind] || { cats: [], items: [] });
    if (st['_' + kind]) return st['_' + kind];
    st['_' + kind] = (async () => {
      const h = host(pl);
      try {
        if (kind === 'vod') {
          const [cats, items] = await Promise.all([
            api().net.json(xbase(pl) + '&action=get_vod_categories', 60000),
            api().net.json(xbase(pl) + '&action=get_vod_streams', 180000),
          ]);
          st.vod = {
            cats: (cats || []).map((c) => ({ id: String(c.category_id), name: c.category_name || 'Category' })),
            items: (items || []).map((m) => ({
              key: `${pl.id}|m|${m.stream_id}`, kind: 'movie', pid: pl.id, id: m.stream_id, name: (m.name || '').trim(), poster: m.stream_icon || '',
              cat: String(m.category_id ?? ''), rating: parseFloat(m.rating) || 0, added: +m.added || 0, tmdb: m.tmdb || '',
              url: `${h}/movie/${enc(pl.user)}/${enc(pl.pass)}/${m.stream_id}.${m.container_extension || 'mp4'}`,
            })),
          };
        } else {
          const [cats, items] = await Promise.all([
            api().net.json(xbase(pl) + '&action=get_series_categories', 60000),
            api().net.json(xbase(pl) + '&action=get_series', 180000),
          ]);
          st.series = {
            cats: (cats || []).map((c) => ({ id: String(c.category_id), name: c.category_name || 'Category' })),
            items: (items || []).map((s) => ({
              key: `${pl.id}|s|${s.series_id}`, kind: 'series', pid: pl.id, id: s.series_id, name: (s.name || '').trim(), poster: s.cover || '',
              cat: String(s.category_id ?? ''), rating: parseFloat(s.rating) || 0, plot: s.plot || '', year: (s.releaseDate || s.release_date || '').slice(0, 4),
              genre: s.genre || '', added: +s.last_modified || 0, tmdb: s.tmdb || '',
            })),
          };
        }
      } catch (e) {
        st[kind] = { cats: [], items: [], error: e.message };
      }
      st['_' + kind] = null;
      D.reindex();
      return st[kind];
    })();
    return st['_' + kind];
  };

  D.loadAllVod = async (kind) => {
    await Promise.all((W.settings.playlists || []).map((pl) => D.loadVod(pl, kind)));
    return D.vodItems(kind);
  };

  D.vodItems = (kind) => {
    const out = [];
    for (const st of D.lists.values()) if (st[kind]) out.push(...st[kind].items);
    return out;
  };

  D.vodCats = (kind) => {
    const out = [];
    const multi = D.lists.size > 1;
    for (const st of D.lists.values()) {
      if (!st[kind]) continue;
      for (const c of st[kind].cats) out.push({ key: `${st.pl.id}|${c.id}`, pid: st.pl.id, id: c.id, name: (multi ? D.playlistName(st.pl) + ' · ' : '') + c.name });
    }
    return out;
  };

  D.seriesInfo = async (item) => {
    const st = D.lists.get(item.pid);
    const pl = st.pl;
    const h = host(pl);
    const r = await api().net.json(xbase(pl) + '&action=get_series_info&series_id=' + item.id, 60000);
    const seasons = [];
    const eps = r && r.episodes || {};
    for (const [season, list] of Object.entries(eps)) {
      seasons.push({
        season: +season,
        episodes: (list || []).map((e) => ({
          key: `${pl.id}|e|${e.id}`, kind: 'episode', pid: pl.id, id: e.id, season: +season, num: +e.episode_num || 0,
          name: e.title || `Episode ${e.episode_num}`, plot: e.info && e.info.plot || '', dur: e.info && (+e.info.duration_secs || 0),
          image: e.info && e.info.movie_image || '', url: `${h}/series/${enc(pl.user)}/${enc(pl.pass)}/${e.id}.${e.container_extension || 'mp4'}`,
          series: item.key, seriesName: item.name,
        })),
      });
    }
    seasons.sort((a, b) => a.season - b.season);
    return { info: r && r.info || {}, seasons };
  };

  D.vodInfo = async (item) => {
    const st = D.lists.get(item.pid);
    if (!st || st.pl.type !== 'xtream') return {};
    const r = await api().net.json(xbase(st.pl) + '&action=get_vod_info&vod_id=' + item.id, 30000);
    return r && r.info || {};
  };

  D.reindex = () => {
    D.byKey.clear();
    for (const st of D.lists.values()) {
      for (const c of st.live ? st.live.chans : []) D.byKey.set(c.key, c);
      for (const k of ['vod', 'series']) for (const m of st[k] ? st[k].items : []) D.byKey.set(m.key, m);
    }
  };

  D.channel = (key) => D.byKey.get(key);
  D.allChannels = () => { const out = []; for (const st of D.lists.values()) if (st.live) out.push(...st.live.chans); return out; };

  D.hiddenCat = (pid, catId) => !!W.hidden[pid + '|' + catId];
  D.setHiddenCat = (pid, catId, on) => { if (on) W.hidden[pid + '|' + catId] = 1; else delete W.hidden[pid + '|' + catId]; W.save('hidden', W.hidden); };

  /** Channels of a Live TV menu entry: 'favs', 'recent', 'all:<pid>', 'cat:<pid>|<catId>', 'pl:<pid>'. */
  D.channelsFor = (sel) => {
    if (sel === 'favs') return W.favs.map(D.channel).filter(Boolean);
    if (sel === 'recent') return W.recent.map(D.channel).filter(Boolean);
    const [kind, rest] = [sel.slice(0, sel.indexOf(':')), sel.slice(sel.indexOf(':') + 1)];
    if (kind === 'pl' || kind === 'all') {
      const st = D.lists.get(rest);
      return st && st.live ? st.live.chans.filter((c) => !D.hiddenCat(rest, c.cat)) : [];
    }
    if (kind === 'cat') {
      const bar = rest.indexOf('|');
      const st = D.lists.get(rest.slice(0, bar));
      const cat = rest.slice(bar + 1);
      return st && st.live ? st.live.chans.filter((c) => c.cat === cat) : [];
    }
    return [];
  };

  D.selName = (sel) => {
    if (sel === 'favs') return 'Favorites';
    if (sel === 'recent') return 'Recent';
    const rest = sel.slice(sel.indexOf(':') + 1);
    if (sel.startsWith('pl:') || sel.startsWith('all:')) { const st = D.lists.get(rest); return st ? (st.pl.type === 'hdhr' ? '📡 ' : '') + D.playlistName(st.pl) + (sel.startsWith('all:') ? ' · All channels' : '') : ''; }
    const bar = rest.indexOf('|');
    const st = D.lists.get(rest.slice(0, bar));
    const c = st && st.live && st.live.cats.find((x) => x.id === rest.slice(bar + 1));
    return c ? c.name : '';
  };

  // ------------------------------------------------------------------ TV guide
  const G = W.guide = { cache: new Map(), status: {} };
  const WINDOW_BACK = 3 * 3600e3, WINDOW_AHEAD = 26 * 3600e3;

  G.ensureAll = async (force) => {
    const urls = D.allGuideUrls();
    if (!urls.length) return;
    D.emit('guides-loading');
    try { G.status = await api().epg.ensure(urls, !!force); } catch { }
    G.cache.clear();
    D.emit('guides');
  };

  /** Programmes for many channels at once: { key: [[start, end, title, desc], …] }. */
  G.get = async (chans) => {
    const now = Date.now();
    const need = chans.filter((c) => { const e = G.cache.get(c.key); return !e || now - e.t > 10 * 60e3; });
    for (let i = 0; i < need.length; i += 400) {
      const part = need.slice(i, i + 400);
      let res = {};
      try {
        res = await api().epg.query(part.map((c) => ({ key: c.key, epgId: c.epgId, name: c.tvgName || c.name, guides: D.guidesFor(c) })), now - WINDOW_BACK, now + WINDOW_AHEAD);
      } catch { }
      for (const c of part) G.cache.set(c.key, { t: now, list: res[c.key] || [] });
    }
    const out = {};
    for (const c of chans) out[c.key] = (G.cache.get(c.key) || { list: [] }).list;
    return out;
  };

  G.nowNext = (list, at = Date.now()) => {
    const i = list.findIndex((p) => p[0] <= at && p[1] > at);
    if (i >= 0) return { now: list[i], next: list[i + 1] || null, later: list.slice(i + 1, i + 6) };
    const j = list.findIndex((p) => p[0] > at);
    return { now: null, next: j >= 0 ? list[j] : null, later: j >= 0 ? list.slice(j, j + 5) : [] };
  };
})();
