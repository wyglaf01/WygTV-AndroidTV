'use strict';
/* Movies, Series, the details page and Search. */
(function () {
  const { h, btn } = W;
  const D = W.data;

  W.posterCard = function posterCard(it, extra = {}) {
    const p = W.progress[it.key];
    const card = btn({ class: 'poster', 'data-k': 'p:' + it.key, onclick: () => W.go('details', { key: it.key }) },
      W.img(it.poster, 'poster-img', it.name),
      it.rating ? h('span', { class: 'badge' }, '★ ' + (+it.rating).toFixed(1)) : null,
      W.inMyList(it.key) ? h('span', { class: 'badge left' }, '＋') : null,
      p && !p.done && p.dur ? h('span', { class: 'poster-progress' }, h('span', { style: { width: (p.pos / p.dur * 100) + '%' } })) : null,
      h('span', { class: 'poster-title' }, it.name));
    card._long = () => {
      const on = W.toggleMyList(it);
      W.toast(on ? 'Added to My List' : 'Removed from My List');
      if (extra.changed) extra.changed();
    };
    return card;
  };

  // ------------------------------------------------------------------ Movies / Series
  W.screens.vod = {
    render(args, entry) {
      const kind = args.kind || 'vod';
      const label = kind === 'vod' ? 'Movies' : 'Series';
      const itemKind = kind === 'vod' ? 'movie' : 'series';
      const menuCol = h('div', { class: 'col menu-col', 'data-col': '', 'data-wrap': '' });
      const gridCol = h('div', { class: 'col grid-col', 'data-col': '' });
      let sel = args.sel || W.load('vodSel.' + kind, 'all');
      let io = null;

      const items = () => D.vodItems(kind);
      const listFor = (s) => {
        const all = items();
        if (s === 'mylist') return W.myList.filter((m) => m.kind === itemKind).map((m) => D.byKey.get(m.key) || m);
        if (s === 'continue') return Object.entries(W.progress).filter(([k, p]) => !p.done && k.includes(kind === 'vod' ? '|m|' : '|s|'))
          .sort((a, b) => b[1].t - a[1].t).map(([k]) => D.byKey.get(k)).filter(Boolean);
        if (s === 'recent') return [...all].sort((a, b) => (b.added || 0) - (a.added || 0)).slice(0, 300);
        if (s === 'all') return all.filter((m) => !D.hiddenCat(m.pid, 'v' + m.cat));
        const [pid, cat] = [s.slice(0, s.indexOf('|')), s.slice(s.indexOf('|') + 1)];
        return all.filter((m) => m.pid === pid && m.cat === cat);
      };

      const renderMenu = () => {
        const cats = D.vodCats(kind).filter((c) => !D.hiddenCat(c.pid, 'v' + c.id));
        const entries = [['search', '⌕ Search ' + label.toLowerCase()], ['mylist', '＋ My List'], ['continue', '▶ Continue watching'], ['recent', '✦ Recently added'], ['all', 'All ' + label.toLowerCase()],
          ...cats.map((c) => [c.key, c.name, c])];
        menuCol.replaceChildren(...entries.map(([k, l, c]) => {
          const el = btn({ class: 'item' + (k === sel ? ' active' : ''), 'data-k': 'm:' + k, 'data-autofocus': k === sel ? '' : null,
            onclick: () => { if (k === 'search') { W.go('search', { only: kind }); return; } select(k); const f = W.$('.poster', gridCol); if (f) W.focus(f); } }, h('span', { class: 'item-label' }, l));
          if (k !== 'search') el._onfocus = W.debounce(() => { if (el === document.activeElement && sel !== k) select(k); }, 400);
          if (c) el._long = () => W.menu(c.name, [{ label: '◐ Hide this category', action: () => { D.setHiddenCat(c.pid, 'v' + c.id, true); W.toast('Hidden. Settings → Hidden categories brings it back.'); renderMenu(); } }]);
          return el;
        }));
      };

      const renderGrid = () => {
        if (io) io.disconnect();
        const list = listFor(sel);
        const title = h('div', { class: 'col-title' }, (menuCol.querySelector('.item.active .item-label') || {}).textContent || label, '  ·  ' + list.length);
        const grid = h('div', { class: 'poster-grid' });
        const sentinel = h('div', { class: 'sentinel' });
        let shown = 0;
        const more = () => {
          const part = list.slice(shown, shown + 120);
          shown += part.length;
          grid.append(...part.map((it) => W.posterCard(it, { changed: () => { if (sel === 'mylist') renderGrid(); } })));
          if (shown >= list.length) sentinel.remove();
        };
        more();
        io = new IntersectionObserver((e) => { if (e.some((x) => x.isIntersecting)) more(); }, { root: gridCol, rootMargin: '800px' });
        gridCol.replaceChildren(title, grid, sentinel);
        if (shown < list.length) io.observe(sentinel);
        if (!list.length) grid.append(h('div', { class: 'empty' }, loading ? 'Loading ' + label.toLowerCase() + '…' : sel === 'mylist' ? 'Hold Enter (or right-click) on a poster to add it to My List.' : 'Nothing here.'));
        gridCol.scrollTop = 0;
      };

      const select = (k) => {
        sel = k; W.save('vodSel.' + kind, k);
        W.$$('.item.active', menuCol).forEach((x) => x.classList.remove('active'));
        const el = menuCol.querySelector(`[data-k="${CSS.escape('m:' + k)}"]`);
        if (el) el.classList.add('active');
        renderGrid();
      };

      let loading = true;
      renderMenu(); renderGrid();
      D.loadAllVod(kind).then(() => {
        loading = false;
        if (!menuCol.isConnected) return;
        const had = document.activeElement && document.activeElement.dataset.k;
        renderMenu(); renderGrid();
        const f = had && document.querySelector(`[data-k="${CSS.escape(had)}"]`);
        W.focus(f || menuCol.querySelector('.item.active') || menuCol.querySelector('.f'));
      });
      entry.cleanup = () => { if (io) io.disconnect(); };
      return h('div', { class: 'screen vod' },
        W.header(label, [btn({ class: 'hbtn', onclick: () => W.go('search', { only: kind }) }, '⌕ Search'), btn({ class: 'hbtn', onclick: () => W.go('settings') }, '⚙')]),
        h('div', { class: 'vod-body' }, menuCol, gridCol));
    },
  };

  // ------------------------------------------------------------------ Details
  function resumeLabel(key) {
    const p = W.progress[key];
    return p && !p.done && p.pos > 30 ? p.pos : 0;
  }

  async function playVod(it, opts = {}) {
    let resume = opts.fromStart ? 0 : resumeLabel(it.key);
    if (resume && !opts.noAsk) {
      const i = await W.menu(it.name, [{ label: '▶ Resume from ' + W.fmtDur(resume) }, { label: '⟲ Start over' }]);
      if (i < 0) return;
      if (i === 1) resume = 0;
    }
    W.player.play({ kind: it.kind, key: it.key, name: it.name, url: it.url, sub: it.sub || '', series: it.series, poster: it.poster }, { full: true, resume, onEnded: opts.onEnded });
  }
  W.playVod = playVod;

  W.screens.details = {
    render(args, entry) {
      const it = D.byKey.get(args.key) || W.myList.find((m) => m.key === args.key);
      if (!it) {
        const msg = h('div', { class: 'empty' }, 'Loading…');
        Promise.all([D.loadAllVod('vod'), D.loadAllVod('series')]).then(() => {
          if (!msg.isConnected) return;
          if (D.byKey.get(args.key)) W.replace('details', args); else msg.textContent = 'This title is no longer in your playlists.';
        });
        return h('div', { class: 'screen' }, W.header('Details'), msg);
      }
      const bg = h('div', { class: 'details-bg' });
      const poster = W.img(it.poster, 'details-poster', it.name);
      const title = h('div', { class: 'details-title' }, W.meta.clean(it.name).title);
      const metaLine = h('div', { class: 'details-meta' });
      const ratings = h('div', { class: 'ratings' });
      const plot = h('div', { class: 'details-plot' }, it.plot || '');
      const credits = h('div', { class: 'details-credits' });
      const buttons = h('div', { class: 'details-buttons' });
      const seasonsRow = h('div', { class: 'seasons' });
      const episodes = h('div', { class: 'episodes', 'data-col': '' });
      let info = null, series = null, season = 0;

      const mylistBtn = () => btn({ class: 'button', onclick: (e) => { const on = W.toggleMyList(it); e.currentTarget.textContent = on ? '✓ In My List' : '＋ My List'; } }, W.inMyList(it.key) ? '✓ In My List' : '＋ My List');

      const renderButtons = () => {
        buttons.replaceChildren();
        if (it.kind === 'movie') {
          const r = resumeLabel(it.key);
          buttons.append(btn({ class: 'button primary', 'data-autofocus': '', onclick: () => playVod(it, { noAsk: true }) }, r ? '▶ Resume ' + W.fmtDur(r) : '▶ Play'));
          if (r) buttons.append(btn({ class: 'button', onclick: () => playVod(it, { fromStart: true }) }, '⟲ Start over'));
        } else if (series) {
          const p = W.progress[it.key];
          const all = series.seasons.flatMap((s) => s.episodes);
          let next = p && p.ep ? all.find((e) => e.key === p.ep) : null;
          if (next && W.progress[next.key] && W.progress[next.key].done) next = all[all.indexOf(next) + 1] || next;
          next = next || all[0];
          if (next) buttons.append(btn({ class: 'button primary', 'data-autofocus': '', onclick: () => playEpisode(next) }, (p && p.ep ? '▶ Continue ' : '▶ Play ') + `S${next.season}E${next.num}`));
        }
        buttons.append(mylistBtn());
        if (info && info.trailer) buttons.append(btn({ class: 'button', onclick: () => W.api.shell.open('https://www.youtube.com/watch?v=' + info.trailer) }, '▷ Trailer'));
      };

      const playEpisode = (ep) => {
        const all = series.seasons.flatMap((s) => s.episodes);
        const i = all.indexOf(ep);
        playVod(Object.assign({}, ep, { sub: `${it.name} · S${ep.season}E${ep.num}`, poster: it.poster }), {
          onEnded: () => { const nx = all[i + 1]; if (nx) { W.toast('Next: ' + nx.name); playEpisode(nx); } else W.player.stop(); },
        });
      };

      const renderEpisodes = () => {
        if (!series) return;
        seasonsRow.replaceChildren(...series.seasons.map((s, i) => btn({ class: 'pill' + (i === season ? ' active' : ''), onclick: () => { season = i; renderEpisodes(); W.focus(W.$('.episode', episodes)); } }, 'Season ' + s.season)));
        const s = series.seasons[season];
        episodes.replaceChildren(...(s ? s.episodes : []).map((ep) => {
          const p = W.progress[ep.key];
          return btn({ class: 'episode', 'data-k': 'e:' + ep.key, onclick: () => playEpisode(ep) },
            W.img(ep.image || it.poster, 'ep-img', ep.num),
            h('div', { class: 'ep-text' },
              h('div', { class: 'ep-title' }, `${ep.num}. ${ep.name}`, p && p.done ? h('span', { class: 'watched' }, '  ✓ watched') : ''),
              h('div', { class: 'ep-plot' }, ep.plot),
              h('div', { class: 'ep-meta' }, ep.dur ? W.fmtDur(ep.dur) : ''),
              p && !p.done && p.dur ? h('span', { class: 'poster-progress' }, h('span', { style: { width: (p.pos / p.dur * 100) + '%' } })) : null));
        }));
      };

      (async () => {
        let provider = {};
        try {
          if (it.kind === 'movie') provider = await D.vodInfo(it);
          else if (it.kind === 'series') { series = await D.seriesInfo(it); provider = series.info; const p = W.progress[it.key]; const all = series.seasons; const k = p && p.ep ? all.findIndex((s) => s.episodes.some((e) => e.key === p.ep)) : 0; season = Math.max(0, k); }
        } catch (e) { plot.textContent = plot.textContent || 'Could not load details: ' + e.message; }
        info = await W.meta.details(it, provider);
        if (!title.isConnected) return;
        title.textContent = info.title;
        metaLine.textContent = [info.year, info.runtime, info.genre, info.rated].filter(Boolean).join('  ·  ');
        ratings.replaceChildren(...info.ratings.map((r) => h('span', { class: 'rating' }, h('b', null, r.src), ' ' + r.val)));
        plot.textContent = info.plot || plot.textContent;
        credits.replaceChildren(info.director ? h('div', null, h('b', null, 'Director  '), info.director) : '', info.cast ? h('div', null, h('b', null, 'Cast  '), info.cast) : '');
        if (info.backdrop || info.poster) bg.style.backgroundImage = `url("${(info.backdrop || info.poster).replace(/"/g, '%22')}")`;
        if (!it.poster && info.poster) poster.replaceWith(W.img(info.poster, 'details-poster', it.name));
        const had = document.activeElement && buttons.contains(document.activeElement);
        renderButtons(); renderEpisodes();
        if (had || !document.activeElement || document.activeElement === document.body || !W.$('#screen').contains(document.activeElement)) W.focusFirst(buttons);
      })();

      renderButtons();
      return h('div', { class: 'screen details' }, bg, h('div', { class: 'details-shade' }),
        W.header(it.kind === 'series' ? 'Series' : 'Movie'),
        h('div', { class: 'details-body' },
          poster,
          h('div', { class: 'details-info' }, title, metaLine, ratings, plot, credits, buttons, it.kind === 'series' ? h('div', null, seasonsRow, episodes) : null)));
    },
  };

  // ------------------------------------------------------------------ Search
  W.screens.search = {
    render(args, entry) {
      const only = args.only || '';
      const input = h('input', { class: 'f input search-input', placeholder: only === 'live' ? 'Search channels…' : only === 'vod' ? 'Search movies…' : only === 'series' ? 'Search series…' : 'Search channels, movies and series…', spellcheck: 'false', 'data-autofocus': '' });
      input.value = args.q || W.load('lastSearch', '');
      const results = h('div', { class: 'search-results', 'data-col': '' });
      const run = W.debounce(async () => {
        const q = W.searchKey(input.value);
        W.save('lastSearch', input.value);
        entry.args = Object.assign({}, args, { q: input.value });
        results.replaceChildren();
        if (!q) { results.append(h('div', { class: 'empty' }, 'Type to search. Matches by any word, accents ignored.')); return; }
        const rank = (list) => {
          const a = [], b = [];
          for (const x of list) { const r = W.searchRank(W.searchKey(x.name), q); if (r === 0) a.push(x); else if (r > 0) b.push(x); }
          return a.concat(b);
        };
        if (!only || only === 'live') {
          const ch = rank(D.allChannels()).slice(0, 80);
          if (ch.length) results.append(h('div', { class: 'section-title' }, 'Channels · ' + ch.length),
            h('div', { class: 'chip-grid' }, ch.map((c) => {
              const el = btn({ class: 'chip', 'data-k': 's:' + c.key, onclick: () => W.player.playChannel(c, { full: true, list: ch }) },
                W.img(c.logo, 'chip-logo', (c.name || '?')[0]), h('span', null, (c.num ? c.num + ' ' : '') + c.name), W.isFav(c.key) ? h('span', { class: 'star' }, ' ★') : '');
              el._long = () => W.channelOptions(c, { list: ch });
              return el;
            })));
        }
        for (const kind of ['vod', 'series']) {
          if (only && only !== kind) continue;
          const list = rank(D.vodItems(kind)).slice(0, 120);
          if (list.length) results.append(h('div', { class: 'section-title' }, (kind === 'vod' ? 'Movies' : 'Series') + ' · ' + list.length), h('div', { class: 'poster-grid' }, list.map((it) => W.posterCard(it))));
        }
        if (!results.children.length) results.append(h('div', { class: 'empty' }, 'Nothing found for “' + input.value + '”.'));
      }, 250);
      input.addEventListener('input', run);
      input._submit = () => { const f = W.$('.f', results); if (f) W.focus(f); };
      run();
      if (!only || only !== 'live') Promise.all([D.loadAllVod('vod'), D.loadAllVod('series')]).then(() => { if (input.isConnected) run(); });
      return h('div', { class: 'screen search' }, W.header('Search'), h('div', { class: 'search-bar' }, input), results);
    },
  };
})();
