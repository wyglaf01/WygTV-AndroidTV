'use strict';
/* Shared helpers, saved settings and the colour themes. Everything hangs off window.W. */
const W = window.W = {};

// ------------------------------------------------------------------ DOM helpers
W.h = function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k === 'html') el.innerHTML = v;
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, v);
    }
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
};

/** A focusable button-like element. */
W.btn = function btn(props, ...kids) {
  const p = Object.assign({ tabindex: '-1' }, props);
  p.class = 'f ' + (p.class || '');
  return W.h('div', p, ...kids);
};

W.img = function img(src, cls, fallbackText) {
  const wrap = W.h('div', { class: 'img ' + (cls || '') });
  if (fallbackText) wrap.append(W.h('span', { class: 'img-fallback' }, fallbackText));
  if (src && /^(https?:|file:|data:|\.)/.test(src)) {
    const i = W.h('img', { loading: 'lazy', decoding: 'async', src, alt: '', referrerpolicy: 'no-referrer' });
    i.onerror = () => i.remove();
    i.onload = () => wrap.classList.add('loaded');
    wrap.append(i);
  }
  return wrap;
};

W.$ = (sel, root) => (root || document).querySelector(sel);
W.$$ = (sel, root) => [...(root || document).querySelectorAll(sel)];

W.searchKey = (s) => (s || '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
/** 0 = starts with the query, 1 = contains every word, -1 = no match. */
W.searchRank = (nameKey, q) => {
  if (!q) return -1;
  if (nameKey.startsWith(q)) return 0;
  for (const w of q.split(' ')) if (!nameKey.includes(w)) return -1;
  return 1;
};

W.fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
W.fmtDur = (s) => {
  s = Math.max(0, Math.floor(s || 0));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0');
};
W.debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
W.uid = () => Math.random().toString(36).slice(2, 10);

// ------------------------------------------------------------------ saved data (localStorage)
W.load = (key, def) => { try { const v = localStorage.getItem('wyg.' + key); return v == null ? def : JSON.parse(v); } catch { return def; } };
W.save = (key, val) => { try { localStorage.setItem('wyg.' + key, JSON.stringify(val)); } catch (e) { console.warn('save failed', key, e); } };

W.DEFAULT_OMDB_KEY = '5b5c4d64'; // Eddie's OMDb key, same default as the Android app.

W.settings = Object.assign({
  theme: 'royal', playlists: [], extraGuides: [], extraFirst: false, omdbKey: '', tmdbKey: '', userAgent: '',
  extPlayer: '', alwaysConvert: false, liveFormat: 'auto', startup: 'home', guideRows: 4, art: true,
}, W.load('settings', {}));
W.saveSettings = () => W.save('settings', W.settings);

/** Favorites, Recent, My List, watch progress and hidden categories. */
W.favs = W.load('favs', []);
W.recent = W.load('recent', []);
W.myList = W.load('mylist', []);
W.progress = W.load('progress', {});
W.hidden = W.load('hidden', {});
W.isFav = (key) => W.favs.includes(key);
W.toggleFav = (key) => {
  const i = W.favs.indexOf(key);
  if (i >= 0) W.favs.splice(i, 1); else W.favs.push(key);
  W.save('favs', W.favs);
  return i < 0;
};
W.addRecent = (key) => {
  W.recent = [key, ...W.recent.filter((k) => k !== key)].slice(0, 40);
  W.save('recent', W.recent);
};
W.inMyList = (key) => W.myList.some((m) => m.key === key);
W.toggleMyList = (item) => {
  const i = W.myList.findIndex((m) => m.key === item.key);
  if (i >= 0) W.myList.splice(i, 1); else W.myList.unshift({ key: item.key, kind: item.kind, name: item.name, poster: item.poster });
  W.save('mylist', W.myList);
  return i < 0;
};
W.setProgress = (key, pos, dur, extra) => {
  if (!key || !(dur > 0)) return;
  if (pos > dur * 0.95) W.progress[key] = Object.assign({ pos: 0, dur, done: true, t: Date.now() }, extra);
  else if (pos > 15) W.progress[key] = Object.assign({ pos, dur, t: Date.now() }, extra);
  else return;
  const keys = Object.keys(W.progress);
  if (keys.length > 800) keys.sort((a, b) => W.progress[a].t - W.progress[b].t).slice(0, 100).forEach((k) => delete W.progress[k]);
  W.save('progress', W.progress);
};

// ------------------------------------------------------------------ colour themes (same palettes as the Android app)
W.THEMES = {
  royal: { label: 'Royal blue & bronze (default)', p: [0xff060C24, 0xff1A3480, 0xe60A1640, 0xffA8743A, 0xffF0C080, 0xff2D52C4, 0xffE6B070, 0xffD9A060, 0xffF4EFE6, 0xffC6CADD, 0xff8C93B8, 0xff1A0F04, 0xffF0C88A, 0xffB0743A, 0xffFFE6BC, 0xffCD7F32, 0xff23408F] },
  steampunk: { label: 'Steampunk (brass & leather)', p: [0xff140B06, 0xff3A2414, 0xe61C100A, 0xff8A6A3A, 0xffF0C878, 0xff9C5B22, 0xffEACB86, 0xffE8B86A, 0xffF5E8CA, 0xffD9BF94, 0xffB89F7C, 0xff2A1708, 0xffF0D797, 0xffBA904A, 0xffFFF0C1, 0xffD9822B, 0xff6E3E1C] },
  blue: { label: 'Blue', p: [0xff070D1C, 0xff142A4E, 0xe60A1428, 0xff3F5F92, 0xff8FB8FF, 0xff2F5FB0, 0xff9CC3FF, 0xff8DB6F5, 0xffEAF1FF, 0xffB4C6E4, 0xff7F93B5, 0xff081428, 0xffA9CBFF, 0xff5D8FD9, 0xffE1ECFF, 0xff4C9BFF, 0xff1C3F78] },
  cyan: { label: 'Cyan', p: [0xff04161A, 0xff0F3A42, 0xe6061F24, 0xff2F7A86, 0xff7FE7F2, 0xff148A98, 0xff8FEFF7, 0xff6FDCE8, 0xffE6FBFD, 0xffA8D9DE, 0xff6FA4AA, 0xff04191D, 0xff9FF3FA, 0xff3BBFCC, 0xffDDFCFF, 0xff22C8D8, 0xff12555F] },
  brown: { label: 'Brown', p: [0xff1A100A, 0xff4A2E1C, 0xe62A1A10, 0xff7A5638, 0xffD9A877, 0xff8A5530, 0xffE3BE96, 0xffD9A877, 0xffF6EADC, 0xffD5BFA6, 0xffA88E74, 0xff2A180C, 0xffE8C9A4, 0xffA9764A, 0xffFBEBD8, 0xffC07A45, 0xff6A4128] },
  gold: { label: 'Gold', p: [0xff171104, 0xff4A3A10, 0xe6261D06, 0xffA88A30, 0xffFFE07A, 0xffB58A12, 0xffFFD966, 0xffFFCF40, 0xffFFF6DC, 0xffE6D59E, 0xffB3A268, 0xff2A2004, 0xffFFE48A, 0xffD4A520, 0xffFFF6C8, 0xffF2B705, 0xff6B5412] },
  green: { label: 'Green', p: [0xff07150A, 0xff173F22, 0xe60B2112, 0xff3F7F4E, 0xff9BE6A8, 0xff2E8A45, 0xffA6EBB2, 0xff8FDE9E, 0xffEAFBEE, 0xffB5DDBD, 0xff7FA889, 0xff06180B, 0xffB2F0BD, 0xff4FB866, 0xffE2FCE6, 0xff3FCF63, 0xff1F5A2E] },
};
const VARS = ['bg', 'panel', 'panel-end', 'stroke', 'stroke-focus', 'focus', 'accent', 'header', 'text', 'muted', 'hint', 'ink', 'tile-a', 'tile-b', 'tile-stroke', 'copper', 'airing'];
const css = (c) => `rgba(${(c >>> 16) & 255},${(c >>> 8) & 255},${c & 255},${(((c >>> 24) & 255) / 255).toFixed(3)})`;
W.applyTheme = (key) => {
  const t = W.THEMES[key] || W.THEMES.royal;
  const root = document.documentElement.style;
  t.p.forEach((c, i) => {
    root.setProperty('--' + VARS[i], css(c));
    root.setProperty('--' + VARS[i] + '-rgb', `${(c >>> 16) & 255},${(c >>> 8) & 255},${c & 255}`);
  });
  document.documentElement.dataset.theme = W.THEMES[key] ? key : 'royal';
};
W.applyTheme(W.settings.theme);

// ------------------------------------------------------------------ toasts
W.toast = (msg, ms = 3200) => {
  let box = W.$('#toasts');
  const t = W.h('div', { class: 'toast' }, msg);
  box.append(t);
  setTimeout(() => t.classList.add('out'), ms);
  setTimeout(() => t.remove(), ms + 400);
};

/** The bridge to the main process; a stand-in that works in a plain browser for design previews. */
W.api = window.wyg || null;
