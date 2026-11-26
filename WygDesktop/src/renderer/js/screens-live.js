'use strict';
/* Home, Live TV and the TV guide. */
(function () {
  const { h, btn } = W;
  const D = W.data;

  W.header = function header(title, actions = [], opts = {}) {
    const clock = h('div', { class: 'clock' }, W.fmtTime(Date.now()));
    const t = setInterval(() => { if (!clock.isConnected) clearInterval(t); else clock.textContent = W.fmtTime(Date.now()); }, 15000);
    return h('div', { class: 'header' },
      opts.noBack ? null : btn({ class: 'hbtn back', onclick: () => W.back(), title: 'Back (Esc)' }, '‹'),
      h('img', { class: 'header-logo', src: 'assets/icon.png', alt: '' }),
      h('div', { class: 'header-title' }, title),
      h('div', { class: 'header-actions' }, actions),
      clock);
  };

  /** Lazily fills guide info for rows as they scroll into view. fill(row, programmes) is called per row. */
  W.guideFiller = function guideFiller(rootEl, fill) {
    const pending = new Map();
    const flush = W.debounce(async () => {
      const rows = [...pending.values()];
      pending.clear();
      if (!rows.length) return;
      const g = await W.guide.get(rows.map((r) => r._ch));
      for (const r of rows) if (r.isConnected) fill(r, g[r._ch.key] || []);
    }, 60);
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) if (e.isIntersecting) { pending.set(e.target._ch.key + '#' + Math.random(), e.target); io.unobserve(e.target); }
      flush();
    }, { root: rootEl, rootMargin: '400px 0px' });
    return { watch: (row) => io.observe(row), stop: () => io.disconnect() };
  };

  // ------------------------------------------------------------------ Home
  W.screens.home = {
    render() {
      const pls = W.settings.playlists || [];
      if (!pls.length) return W.screens.home.welcome();
      const tiles = [
        ['live', '📺', 'Live TV', () => W.go('live')],
        ['guide', '▦', 'TV Guide', () => W.go('guide')],
        ['movies', '🎬', 'Movies', () => W.go('vod', { kind: 'vod' })],
        ['series', '🎞', 'Series', () => W.go('vod', { kind: 'series' })],
        ['search', '⌕', 'Search', () => W.go('search')],
        ['settings', '⚙', 'Settings', () => W.go('settings')],
      ];
      const row2 = [];
      if (W.recent.length) row2.push(['recent', '🕘', 'Recent', () => W.go('live', { sel: 'recent' })]);
      row2.push(['favs', '★', 'Favorites', () => W.go('live', { sel: 'favs' })]);
      for (const pl of pls.filter((p) => p.type === 'hdhr')) row2.push(['ant' + pl.id, '📡', D.playlistName(pl), () => W.go('live', { sel: 'pl:' + pl.id })]);
      const recentRow = h('div', { class: 'home-strip', 'data-row': '' });
      const fillRecent = () => {
        recentRow.replaceChildren();
        const chans = W.recent.map(D.channel).filter(Boolean).slice(0, 12);
        if (!chans.length) return;
        recentRow.append(h('div', { class: 'strip-title' }, 'Recent channels'),
          h('div', { class: 'strip' }, chans.map((c) => btn({ class: 'chip', 'data-k': 'rc' + c.key, onclick: () => W.player.playChannel(c, { full: true, list: chans }) },
            W.img(c.logo, 'chip-logo', (c.name || '?')[0]), h('span', null, c.name)))));
      };
      const contRow = h('div', { class: 'home-strip' });
      const fillCont = () => {
        contRow.replaceChildren();
        const items = Object.entries(W.progress).filter(([k, p]) => !p.done && !p.series && (k.includes('|m|') || k.includes('|s|')))
          .sort((a, b) => b[1].t - a[1].t).slice(0, 12).map(([k]) => D.byKey.get(k)).filter(Boolean);
        if (!items.length) return;
        contRow.append(h('div', { class: 'strip-title' }, 'Continue watching'),
          h('div', { class: 'strip' }, items.map((it) => W.posterCard(it))));
      };
      fillRecent(); fillCont();
      const status = h('div', { class: 'home-status' });
      const fillStatus = () => {
        const bad = [...D.lists.values()].filter((s) => s.error);
        const loading = [...D.lists.values()].some((s) => s.loading);
        status.textContent = loading ? 'Loading playlists…' : bad.length ? '⚠ ' + bad.map((s) => D.playlistName(s.pl) + ': ' + s.error).join(' · ') : '';
      };
      fillStatus();
      const offs = [D.on('status', fillStatus), D.on('loaded', () => { fillRecent(); fillCont(); fillStatus(); })];
      W.current().cleanup = () => offs.forEach((f) => f());
      return h('div', { class: 'home' },
        h('div', { class: 'home-top' },
          h('img', { class: 'home-logo', src: 'assets/icon.png', alt: '' }),
          h('div', { class: 'home-brand' }, h('div', { class: 'brand' }, W.brand), h('div', { class: 'brand-sub' }, 'Live TV · Movies · Series · Antenna')),
          h('div', { class: 'clock big' }, W.fmtTime(Date.now()))),
        h('div', { class: 'tiles' }, tiles.map(([k, icon, label, fn], i) => btn({ class: 'tile', 'data-k': k, 'data-autofocus': i === 0 ? '' : null, onclick: fn },
          h('div', { class: 'tile-icon' }, icon), h('div', { class: 'tile-label' }, label)))),
        h('div', { class: 'tiles small' }, row2.map(([k, icon, label, fn]) => btn({ class: 'tile small', 'data-k': k, onclick: fn },
          h('div', { class: 'tile-icon' }, icon), h('div', { class: 'tile-label' }, label)))),
        recentRow, contRow, status,
        h('div', { class: 'keys-hint' }, W.isTV ? 'Arrows move · OK plays · hold OK for options · Back goes back' : W.isIOS || W.touchScreen ? 'Tap to open or play · touch and hold for options · ‹ or swipe from the left edge goes back' : 'Arrows move · Enter plays · hold Enter or right-click for options · Esc goes back · S searches · F11 full screen'));
    },
    welcome() {
      return h('div', { class: 'home welcome' },
        h('img', { class: 'welcome-logo', src: 'assets/icon.png', alt: '' }),
        h('div', { class: 'brand' }, 'Welcome to ' + W.brand),
        h('div', { class: 'welcome-text' }, 'Add your IPTV service or antenna tuner to get started.'),
        h('div', { class: 'tiles' },
          btn({ class: 'tile', 'data-autofocus': '', onclick: () => W.addPlaylist('xtream') }, h('div', { class: 'tile-icon' }, '🔑'), h('div', { class: 'tile-label' }, 'Xtream login')),
          btn({ class: 'tile', onclick: () => W.addPlaylist('m3u') }, h('div', { class: 'tile-icon' }, '📄'), h('div', { class: 'tile-label' }, 'M3U playlist')),
          btn({ class: 'tile', onclick: () => W.addPlaylist('hdhr') }, h('div', { class: 'tile-icon' }, '📡'), h('div', { class: 'tile-label' }, 'Antenna TV (HDHomeRun)')),
          btn({ class: 'tile', onclick: () => W.addPlaylist('tablo') }, h('div', { class: 'tile-icon' }, '📡'), h('div', { class: 'tile-label' }, 'Antenna TV (Tablo)')),
          btn({ class: 'tile', onclick: () => W.restoreBackup() }, h('div', { class: 'tile-icon' }, '⤓'), h('div', { class: 'tile-label' }, 'Restore a backup'))));
    },
  };

  // ------------------------------------------------------------------ Live TV
  /** Entries of the Live TV menu: Favorites, Recent, antenna tuners, then each playlist's categories. */
  function liveMenu() {
    const items = [{ sel: 'favs', label: '★ Favorites', count: W.favs.length }, { sel: 'recent', label: '🕘 Recent', count: W.recent.length }];
    for (const st of D.lists.values()) {
      if (st.pl.type === 'hdhr') items.push({ sel: 'pl:' + st.pl.id, label: '📡 ' + D.playlistName(st.pl), count: st.live ? st.live.chans.length : 0, error: st.error });
    }
    for (const st of D.lists.values()) {
      if (st.pl.type === 'hdhr') continue;
      items.push({ heading: D.playlistName(st.pl), error: st.error, loading: st.loading });
      if (!st.live) continue;
      items.push({ sel: 'all:' + st.pl.id, label: 'All channels', count: st.live.chans.length });
      const counts = {};
      for (const c of st.live.chans) counts[c.cat] = (counts[c.cat] || 0) + 1;
      for (const c of st.live.cats) {
        if (D.hiddenCat(st.pl.id, c.id) || !counts[c.id]) continue;
        items.push({ sel: `cat:${st.pl.id}|${c.id}`, label: c.name, count: counts[c.id], pid: st.pl.id, cat: c.id });
      }
    }
    return items;
  }
  W.liveMenu = liveMenu;

  W.channelOptions = async function channelOptions(ch, ctx = {}) {
    const fav = W.isFav(ch.key);
    await W.menu(ch.name, [
      { label: '▶ Play full screen', action: () => W.player.playChannel(ch, { full: true, list: ctx.list, sel: ctx.sel }) },
      { label: fav ? '★ Remove from Favorites' : '☆ Add to Favorites', action: () => { const on = W.toggleFav(ch.key); W.toast(on ? 'Added to Favorites' : 'Removed from Favorites'); ctx.changed && ctx.changed(); } },
      { label: '▦ Show in TV Guide', action: () => W.go('guide', { sel: ctx.sel, focus: ch.key }) },
      W.isTV ? null : { label: '⚙ Play converted with FFmpeg', sub: 'For channels with no sound or no picture', action: () => W.player.play({ kind: 'live', key: ch.key, name: ch.name, url: ch.url, num: ch.num, convert: 'x264', ch }, { full: true, list: ctx.list, sel: ctx.sel }) },
      W.isTV ? null : { label: '↗ Open in ' + W.extLabel, action: () => W.api.ext.play(W.settings.extPlayer, ch.url, ch.name).catch((e) => W.alert('External player', e.message)) },
    ]);
  };

  W.screens.live = {
    render(args, entry) {
      const menu = liveMenu();
      let sel = args.sel || W.load('liveSel', '') || '';
      if (!menu.some((m) => m.sel === sel)) sel = (menu.find((m) => m.sel && m.count) || menu[0]).sel;
      const menuCol = h('div', { class: 'col menu-col', 'data-col': '', 'data-wrap': '' });
      const chanCol = h('div', { class: 'col chan-col', 'data-col': '', 'data-wrap': '' });
      const previewBox = h('div', { class: 'preview-box' }, h('div', { class: 'preview-idle' }, h('img', { src: 'assets/icon.png', alt: '' }), h('div', null, (W.isTV ? 'Press OK to watch here · OK again for full screen' : 'Press Enter to watch here · Enter again for full screen'))));
      const info = h('div', { class: 'live-info' });
      const chanTitle = h('div', { class: 'col-title' });
      let chans = [];
      let filler = null;

      const showInfo = async (ch) => {
        info.replaceChildren(h('div', { class: 'li-name' }, (ch.num ? ch.num + '  ' : '') + ch.name));
        const g = await W.guide.get([ch]);
        if (!info.isConnected) return;
        const list = g[ch.key] || [];
        const nn = W.guide.nowNext(list);
        const rows = [];
        if (nn.now) rows.push(nn.now);
        rows.push(...nn.later);
        info.append(
          nn.now ? h('div', { class: 'li-now' },
            h('div', { class: 'li-prog' }, nn.now[2]),
            h('div', { class: 'li-time' }, W.fmtTime(nn.now[0]) + ' – ' + W.fmtTime(nn.now[1]), h('span', { class: 'li-bar' }, h('span', { style: { width: Math.min(100, (Date.now() - nn.now[0]) / (nn.now[1] - nn.now[0]) * 100) + '%' } }))),
            nn.now[3] ? h('div', { class: 'li-desc' }, nn.now[3]) : null) : h('div', { class: 'li-none' }, list.length ? '' : 'No guide information for this channel'),
          h('div', { class: 'mini-guide' }, rows.slice(nn.now ? 1 : 0, 1 + (W.settings.guideRows || 4)).map((p) => h('div', { class: 'mg-row' }, h('span', { class: 'mg-time' }, W.fmtTime(p[0])), h('span', { class: 'mg-title' }, p[2])))));
      };

      const renderMenu = () => {
        menuCol.replaceChildren(
          btn({ class: 'item small', onclick: () => W.go('search', { only: 'live' }) }, '⌕ Search channels'),
          btn({ class: 'item small', onclick: () => manageHidden() }, '◐ Hide / show categories'),
          ...menu.map((m) => m.heading ? h('div', { class: 'menu-heading' }, m.heading, m.loading ? ' · loading…' : '', m.error ? h('span', { class: 'err' }, ' ⚠ ' + m.error) : '')
            : btn({
              class: 'item' + (m.sel === sel ? ' active' : ''), 'data-k': 'm:' + m.sel, 'data-autofocus': m.sel === sel && !args.focus ? '' : null,
              onclick: () => { select(m.sel); const first = W.$('.f', chanCol.querySelector('.chan-list') || chanCol); if (first) W.focus(first); },
              _long: null,
            }, h('span', { class: 'item-label' }, m.label), h('span', { class: 'count' }, m.count || ''))));
        for (const el of W.$$('.item[data-k^="m:cat:"]', menuCol)) {
          const m = menu.find((x) => 'm:' + x.sel === el.dataset.k);
          el._long = () => W.menu(m.label, [{ label: '◐ Hide this category', action: () => { D.setHiddenCat(m.pid, m.cat, true); W.toast('Hidden. Bring it back with “Hide / show categories”.'); W.refresh(); } }]);
          el._onfocus = W.keyOnly(W.debounce(() => { if (el === document.activeElement && m.sel !== sel) select(m.sel); }, 350));
        }
        for (const el of W.$$('.item[data-k^="m:"]:not([data-k^="m:cat:"])', menuCol)) {
          const s = el.dataset.k.slice(2);
          el._onfocus = W.keyOnly(W.debounce(() => { if (el === document.activeElement && s !== sel) select(s); }, 350));
        }
      };

      const renderChannels = () => {
        if (filler) filler.stop();
        chans = D.channelsFor(sel);
        chanTitle.textContent = D.selName(sel) + '  ·  ' + chans.length + ' channels';
        const list = h('div', { class: 'chan-list' });
        filler = W.guideFiller(chanCol, (row, progs) => {
          const nn = W.guide.nowNext(progs);
          const now = row.querySelector('.ch-now');
          if (nn.now) {
            now.textContent = nn.now[2];
            row.querySelector('.ch-bar span').style.width = Math.min(100, (Date.now() - nn.now[0]) / (nn.now[1] - nn.now[0]) * 100) + '%';
          } else now.textContent = '';
        });
        const playing = W.player.item();
        for (const ch of chans) {
          const row = btn({
            class: 'chan' + (playing && playing.key === ch.key ? ' playing' : ''), 'data-k': 'c:' + ch.key,
            'data-autofocus': args.focus === ch.key ? '' : null,
            onclick: () => {
              const p = W.player.item();
              if (p && p.key === ch.key && !W.player.isFull()) W.player.enterFull();
              else W.player.playChannel(ch, { host: previewBox, list: chans, sel, listName: D.selName(sel) });
              W.$$('.chan.playing', chanCol).forEach((x) => x.classList.remove('playing'));
              row.classList.add('playing');
            },
          },
          h('span', { class: 'ch-num' }, ch.num || ''),
          W.img(ch.logo, 'ch-logo', (ch.name || '?').slice(0, 1)),
          h('span', { class: 'ch-text' }, h('span', { class: 'ch-name' }, ch.name, W.isFav(ch.key) ? h('span', { class: 'star' }, ' ★') : ''), h('span', { class: 'ch-now' }), h('span', { class: 'ch-bar' }, h('span'))));
          row._ch = ch;
          row._long = () => W.channelOptions(ch, { list: chans, sel, changed: () => W.refresh() });
          row._onfocus = W.debounce(() => { if (row === document.activeElement) showInfo(ch); }, 200);
          row.addEventListener('dblclick', () => { W.player.playChannel(ch, { full: true, list: chans, sel }); });
          list.append(row);
          filler.watch(row);
        }
        if (!chans.length) list.append(h('div', { class: 'empty' }, sel === 'favs' ? (W.isTV ? 'No favorites yet. Hold OK on a channel to add it.' : 'No favorites yet. Hold Enter (or right-click) on a channel to add it.') : sel === 'recent' ? 'Channels you watch show up here.' : 'No channels here.'));
        chanCol.replaceChildren(chanTitle, list);
        chanCol.scrollTop = 0;
      };

      const select = (s) => {
        sel = s; W.save('liveSel', s);
        W.$$('.item.active', menuCol).forEach((x) => x.classList.remove('active'));
        const el = menuCol.querySelector(`[data-k="${CSS.escape('m:' + s)}"]`);
        if (el) el.classList.add('active');
        renderChannels();
      };

      async function manageHidden() {
        const cats = [];
        for (const st of D.lists.values()) if (st.live && st.pl.type !== 'hdhr') for (const c of st.live.cats) cats.push({ pid: st.pl.id, c, pl: D.playlistName(st.pl) });
        const loop = async (focus) => {
          const i = await W.menu('Hide / show categories', [{ label: 'Show all categories' }, ...cats.map((x) => ({ label: (D.hiddenCat(x.pid, x.c.id) ? '◌ ' : '● ') + x.c.name, sub: D.hiddenCat(x.pid, x.c.id) ? 'Hidden · ' + x.pl : x.pl }))], { focus, wide: true });
          if (i < 0) { W.refresh(); return; }
          if (i === 0) { cats.forEach((x) => D.setHiddenCat(x.pid, x.c.id, false)); W.refresh(); return; }
          const x = cats[i - 1];
          D.setHiddenCat(x.pid, x.c.id, !D.hiddenCat(x.pid, x.c.id));
          loop(i);
        };
        loop(0);
      }

      renderMenu();
      renderChannels();
      if (chans[0]) showInfo(chans[0]);
      const offs = [D.on('guides', () => { if (chanCol.isConnected) { renderChannels(); } }), D.on('loaded', () => W.refresh())];
      W.player.onExitFull = () => {
        const p = W.player.item();
        if (p && chanCol.isConnected) { const r = chanCol.querySelector(`[data-k="${CSS.escape('c:' + p.key)}"]`); W.$$('.chan.playing', chanCol).forEach((x) => x.classList.remove('playing')); if (r) { r.classList.add('playing'); W.focus(r, { block: 'center' }); } }
      };
      entry.cleanup = () => { offs.forEach((f) => f()); if (filler) filler.stop(); W.player.onExitFull = null; if (!W.player.isFull() && !W.player.isMini()) W.player.stop(); };

      return h('div', { class: 'screen live' },
        W.header('Live TV', [
          btn({ class: 'hbtn', onclick: () => W.go('search', { only: 'live' }) }, '⌕ Search'),
          btn({ class: 'hbtn', onclick: () => W.go('guide', { sel }) }, '▦ TV Guide'),
          btn({ class: 'hbtn', onclick: () => W.go('settings') }, '⚙'),
        ]),
        h('div', { class: 'live-body' }, menuCol, chanCol, h('div', { class: 'col right-col' }, previewBox, info)));
    },
  };

  // ------------------------------------------------------------------ TV Guide grid
  const PX_PER_MIN = 6;
  W.screens.guide = {
    render(args, entry) {
      const menu = liveMenu().filter((m) => m.sel);
      let sel = args.sel || W.load('liveSel', '') || '';
      if (!menu.some((m) => m.sel === sel)) sel = (menu.find((m) => m.count) || menu[0] || {}).sel || 'favs';
      const start = Math.floor((Date.now() - 30 * 60e3) / (30 * 60e3)) * 30 * 60e3;
      const hours = 12;
      const width = hours * 60 * PX_PER_MIN;
      const x = (t) => Math.max(0, Math.min(width, (t - start) / 60e3 * PX_PER_MIN));
      const detail = h('div', { class: 'guide-detail' });
      const grid = h('div', { class: 'guide-grid', 'data-col': '' });
      const catBtn = btn({ class: 'hbtn', onclick: async () => {
        const i = await W.menu('Show channels from', menu.map((m) => ({ label: m.label, sub: m.count ? m.count + ' channels' : '', checked: m.sel === sel })), { wide: true });
        if (i >= 0) W.replace('guide', { sel: menu[i].sel });
      } }, '☰ ' + D.selName(sel));

      const chans = D.channelsFor(sel);
      const ruler = h('div', { class: 'g-ruler', style: { width: width + 'px' } });
      for (let t = start; t < start + hours * 3600e3; t += 30 * 60e3) ruler.append(h('div', { class: 'g-tick', style: { left: x(t) + 'px' } }, W.fmtTime(t)));
      const nowLine = h('div', { class: 'g-now', style: { left: 'calc(var(--g-chan) + ' + x(Date.now()) + 'px)' } });
      grid.append(h('div', { class: 'g-head' }, h('div', { class: 'g-corner' }, new Date().toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' })), ruler), nowLine);

      const showDetail = (ch, p) => {
        detail.replaceChildren(
          h('div', { class: 'gd-title' }, p ? p[2] : ch.name),
          h('div', { class: 'gd-sub' }, (ch.num ? ch.num + ' · ' : '') + ch.name + (p ? ' · ' + W.fmtTime(p[0]) + ' – ' + W.fmtTime(p[1]) : '')),
          p && p[3] ? h('div', { class: 'gd-desc' }, p[3]) : null);
      };

      const filler = W.guideFiller(grid, (row, progs) => {
        const track = row.querySelector('.g-track');
        track.replaceChildren();
        const ch = row._ch;
        const vis = progs.filter((p) => p[1] > start && p[0] < start + hours * 3600e3);
        if (!vis.length) {
          track.append(cell(ch, null, 0, Math.min(width, x(Date.now() + 3 * 3600e3))));
          return;
        }
        let lastEnd = 0;
        for (const p of vis) {
          const l = Math.max(x(p[0]), lastEnd), r = x(p[1]);
          if (r - l < 2) continue;
          track.append(cell(ch, p, l, r - l));
          lastEnd = r;
        }
        if (args.focus === ch.key && !row._focused) {
          row._focused = true;
          const live = W.$$('.g-cell', track).find((c) => c._p && c._p[0] <= Date.now() && c._p[1] > Date.now()) || track.firstChild;
          if (live) W.focus(live, { block: 'center' });
        }
      });

      function cell(ch, p, left, w) {
        const now = Date.now();
        const c = btn({
          class: 'g-cell' + (p && p[0] <= now && p[1] > now ? ' airing' : '') + (p && p[1] <= now ? ' past' : ''),
          style: { left: left + 'px', width: Math.max(w - 3, 8) + 'px' },
          'data-k': 'g:' + ch.key + ':' + (p ? p[0] : 0),
          onclick: () => W.player.playChannel(ch, { full: true, list: chans, sel }),
        }, h('span', { class: 'gc-title' }, p ? p[2] : ch.name + ' · no guide info'), p ? h('span', { class: 'gc-time' }, W.fmtTime(p[0])) : null);
        c._p = p;
        c._onfocus = () => showDetail(ch, p);
        c._long = () => W.channelOptions(ch, { list: chans, sel });
        return c;
      }

      for (const ch of chans) {
        const row = h('div', { class: 'g-row' },
          btn({ class: 'g-chan', 'data-k': 'gc:' + ch.key, onclick: () => W.player.playChannel(ch, { full: true, list: chans, sel }), _onfocus: null },
            h('span', { class: 'ch-num' }, ch.num || ''), W.img(ch.logo, 'ch-logo', (ch.name || '?')[0]), h('span', { class: 'ch-name' }, ch.name, W.isFav(ch.key) ? ' ★' : '')),
          h('div', { class: 'g-track', style: { width: width + 'px' } }, h('div', { class: 'g-loading' }, '…')));
        row._ch = ch;
        row.firstChild._onfocus = () => showDetail(ch, null);
        row.firstChild._long = () => W.channelOptions(ch, { list: chans, sel });
        grid.append(row);
        filler.watch(row);
      }
      if (!chans.length) grid.append(h('div', { class: 'empty' }, 'No channels in ' + D.selName(sel) + '.'));
      requestAnimationFrame(() => { grid.scrollLeft = Math.max(0, x(Date.now()) - 120); });
      const nowTimer = setInterval(() => { nowLine.style.left = 'calc(var(--g-chan) + ' + x(Date.now()) + 'px)'; }, 60000);
      const off = D.on('guides', () => W.refresh());
      entry.cleanup = () => { filler.stop(); clearInterval(nowTimer); off(); };
      return h('div', { class: 'screen guide' },
        W.header('TV Guide', [catBtn, btn({ class: 'hbtn', onclick: () => W.go('search', { only: 'live' }) }, '⌕ Search'),
          btn({ class: 'hbtn', onclick: () => { W.guide.ensureAll(true); W.toast('Refreshing the guides…'); } }, '⟳ Refresh')]),
        detail, grid);
    },
  };
})();
