'use strict';
/* Settings: playlists, TV guides, antenna tuners, look, player, movie info, backup and updates. */
(function () {
  const { h, btn } = W;
  const D = W.data;

  const FREE_GUIDES = [['USA', 'US'], ['Canada', 'CA'], ['United Kingdom', 'GB'], ['Ireland', 'IE'], ['Australia', 'AU'], ['Mexico', 'MX'], ['Spain', 'ES'], ['Germany', 'DE'], ['France', 'FR'], ['Italy', 'IT'], ['Portugal', 'PT'], ['Brazil', 'BR'], ['India', 'IN']];
  const freeGuideUrl = (code) => `https://epg.pw/xmltv/epg_${code}.xml.gz`;

  async function savePlaylists(changed) {
    W.saveSettings();
    const b = W.busy('Loading ' + (changed ? D.playlistName(changed) : 'playlists') + '…');
    try {
      if (changed) { D.lists.delete(changed.id); await D.loadLive(changed, true); D.reindex(); W.guide.ensureAll(); D.emit('loaded'); }
      else await D.loadAll();
    } finally { b.close(); }
    const st = changed && D.lists.get(changed.id);
    if (st && st.error) W.alert(D.playlistName(changed), 'Saved, but it could not be loaded: ' + st.error);
    else if (st) W.toast(D.playlistName(changed) + ': ' + (st.live ? st.live.chans.length : 0) + ' channels');
  }

  /** Add (or edit) an Xtream login, M3U playlist or HDHomeRun tuner. */
  W.addPlaylist = async function addPlaylist(type, existing) {
    const pl = existing ? Object.assign({}, existing) : { id: W.uid(), type };
    if (type === 'xtream') {
      const r = await W.form(existing ? 'Edit Xtream login' : 'Add an Xtream login', [
        { key: 'name', label: 'Name (optional)', value: pl.name, placeholder: 'My TV service' },
        { key: 'host', label: 'Server address', value: pl.host, placeholder: 'http://example.com:8080', type: 'url' },
        { key: 'user', label: 'Username', value: pl.user },
        { key: 'pass', label: 'Password', value: pl.pass, type: 'password' },
        { key: 'epgUrl', label: 'TV guide link (optional, the provider’s own is used otherwise)', value: pl.epgUrl, type: 'url' },
      ], { validate: (v) => {
        try { const u = new URL(v.host.trim()); if (!/^https?:$/.test(u.protocol)) return 'The server address must start with http:// or https://'; } catch { return 'Enter the server address, like http://example.com:8080'; }
        if (!v.user.trim() || !v.pass) return 'Enter the username and password';
      } });
      if (!r) return;
      Object.assign(pl, { name: r.name.trim(), host: r.host.trim().replace(/\/+$/, '').replace(/\/(player_api\.php|get\.php).*$/, ''), user: r.user.trim(), pass: r.pass, epgUrl: r.epgUrl.trim() });
    } else if (type === 'm3u') {
      const r = await W.form(existing ? 'Edit M3U playlist' : 'Add an M3U playlist', [
        { key: 'name', label: 'Name (optional)', value: pl.name },
        { key: 'url', label: 'Playlist link or file', value: pl.url, placeholder: 'http://…/playlist.m3u', type: 'url' },
        { key: 'epgUrl', label: 'TV guide link (optional)', value: pl.epgUrl, type: 'url' },
      ], {
        validate: (v) => (!v.url.trim() ? 'Enter a link or pick a file' : ''),
        extra: [{ label: '📂 Pick a file…', action: async (inputs) => { const f = await W.api.file.open(); if (f) inputs.url.value = f.path; } }],
      });
      if (!r) return;
      Object.assign(pl, { name: r.name.trim(), url: r.url.trim(), epgUrl: r.epgUrl.trim() });
      // An Xtream get.php link: use the login instead (it also gives movies and series).
      try {
        const u = new URL(pl.url);
        if (/get\.php$/.test(u.pathname) && u.searchParams.get('username')) {
          const yes = await W.confirm('Xtream login found', 'This link contains an Xtream login. Use it as an Xtream login so Movies and Series work too?', 'Yes, use the login', 'No, keep it as M3U');
          if (yes) Object.assign(pl, { type: 'xtream', host: u.origin, user: u.searchParams.get('username'), pass: u.searchParams.get('password') || '' });
        }
      } catch { }
    } else if (type === 'tablo') {
      // A black Tablo (2013–2023). Saved as an antenna tuner (type 'hdhr', tablo: true).
      const b = W.busy('Looking for your Tablo…');
      let found = [];
      try { found = await W.api.tablo.discover(); } catch { }
      b.close();
      const have = (W.settings.playlists || []).filter((p) => p.type === 'hdhr' && p.tablo);
      const fresh = found.filter((d) => !have.some((p) => p.base === d.base || (p.deviceId && p.deviceId === d.id)));
      const i = await W.menu(fresh.length ? 'Tablos found' : 'No new Tablo found', [
        ...fresh.map((d) => ({ label: '📡 ' + (d.model || d.name) + (d.locked ? '  (won’t share its channels)' : ''), sub: d.base.replace(/^https?:\/\//, '') + (d.tuners ? ' · ' + d.tuners + ' tuners' : '') })),
        { label: '⌨ Type the Tablo’s address', sub: 'For example 192.168.1.60 (shown in your router or the Tablo app)' },
      ], { text: 'For the black Tablo models (DUAL, QUAD, LITE). Channels and the TV guide come from the Tablo; each channel uses one of its tuners while it plays. '
        + 'The white 4th Gen Tablo only works in Tablo’s own app, so it can’t be added here.' });
      if (i < 0) return;
      let dev = fresh[i];
      if (!dev) {
        const r = await W.form('Tablo address', [{ key: 'base', label: 'IP address', placeholder: '192.168.1.60' }], { okLabel: 'Add' });
        if (!r) return;
        try { dev = await W.api.tablo.describe(r.base); } catch (e) {
          W.alert(/refuses other apps/.test(e.message) ? 'This Tablo won’t share its channels' : 'Tablo not found',
            /refuses other apps/.test(e.message) ? e.message : 'No black Tablo answered at ' + r.base + ' (' + e.message + '). A white 4th Gen Tablo can’t be added: it only works in the Tablo app.');
          return;
        }
      }
      if (dev.locked) { W.alert('This Tablo won’t share its channels', 'This Tablo answered but refuses other apps (its firmware locked the API this app uses). Its channels can still be watched in the Tablo app.'); return; }
      const n = have.length;
      Object.assign(pl, { type: 'hdhr', tablo: true, name: n ? 'Tablo ' + (n + 1) : 'Tablo', base: dev.base, deviceId: dev.id });
    } else if (type === 'hdhr') {
      if (existing) {
        const r = await W.form(pl.tablo ? 'Tablo' : 'Antenna TV', [{ key: 'name', label: 'Name', value: pl.name }, { key: 'base', label: pl.tablo ? 'Tablo address' : 'Tuner address', value: pl.base }]);
        if (!r) return;
        Object.assign(pl, { name: r.name.trim(), base: r.base.trim() });
      } else {
        const b = W.busy('Looking for HDHomeRun tuners on your network…');
        let found = [];
        try { found = await W.api.hdhr.discover(); } catch { }
        b.close();
        const have = (W.settings.playlists || []).filter((p) => p.type === 'hdhr');
        const fresh = found.filter((d) => !have.some((p) => p.base === d.base || (p.deviceId && p.deviceId === d.id)));
        const i = await W.menu(fresh.length ? 'Tuners found' : 'No new tuners found', [
          ...fresh.map((d) => ({ label: '📡 ' + d.name + (d.model ? ' (' + d.model + ')' : ''), sub: d.base.replace(/^https?:\/\//, '') + ' · ' + d.tuners + ' tuners' })),
          { label: '⌨ Type the tuner’s address', sub: 'For example 192.168.1.50' },
        ], { text: fresh.length ? '' : 'Make sure the tuner is on and on the same network as this computer.' });
        if (i < 0) return;
        let dev = fresh[i];
        if (!dev) {
          const r = await W.form('Tuner address', [{ key: 'base', label: 'IP address or name', placeholder: '192.168.1.50' }], { okLabel: 'Add' });
          if (!r) return;
          try { dev = await W.api.hdhr.describe(r.base); } catch (e) { W.alert('Tuner not found', 'No HDHomeRun answered at ' + r.base + ' (' + e.message + ').'); return; }
        }
        const n = have.length;
        Object.assign(pl, { name: n ? 'Antenna TV ' + (n + 1) : 'Antenna TV', base: dev.base, deviceId: dev.id });
      }
    }
    const list = W.settings.playlists || (W.settings.playlists = []);
    const at = list.findIndex((p) => p.id === pl.id);
    if (at >= 0) list[at] = pl; else list.push(pl);
    await savePlaylists(pl);
    if (W.current() && (W.current().name === 'home' || W.current().name === 'settings')) W.refresh();
  };

  W.backupText = () => JSON.stringify({
    app: 'WygDesktop', brand: W.brand, version: 1, saved: new Date().toISOString(), settings: W.settings, favs: W.favs, recent: W.recent,
    mylist: W.myList, progress: W.progress, hidden: W.hidden,
  }, null, 2);

  W.restoreBackup = async () => {
    const f = await W.api.file.open();
    if (!f) return;
    try {
      const b = JSON.parse(f.text);
      if (!b.settings) throw new Error('not a WygDesktop backup');
      W.save('settings', Object.assign({}, W.settings, b.settings));
      for (const [k, key] of [['favs', 'favs'], ['recent', 'recent'], ['mylist', 'mylist'], ['progress', 'progress'], ['hidden', 'hidden']]) if (b[k]) W.save(key, b[k]);
      location.reload();
    } catch (e) { W.alert('Restore', 'That file could not be restored: ' + e.message); }
  };

  const sections = {
    playlists: {
      label: '📺 Playlists',
      render() {
        const list = W.settings.playlists || [];
        return [
          h('div', { class: 'set-text' }, 'Your IPTV services and antenna tuners. Hold Enter (or right-click) on one for more.'),
          ...list.map((pl, i) => {
            const st = D.lists.get(pl.id);
            const sub = (pl.type === 'xtream' ? 'Xtream · ' + pl.host : pl.type === 'm3u' ? 'M3U · ' + pl.url : (pl.tablo ? 'Tablo · ' : 'HDHomeRun · ') + pl.base) +
              (st ? st.loading ? ' · loading…' : st.error ? ' · ⚠ ' + st.error : st.live ? ' · ' + st.live.chans.length + ' channels' : '' : '');
            const el = btn({ class: 'set-row', 'data-k': 'pl:' + pl.id, onclick: () => plMenu(pl, i) }, h('div', { class: 'set-label' }, (pl.type === 'hdhr' ? '📡 ' : '') + D.playlistName(pl)), h('div', { class: 'set-sub' }, sub));
            el._long = () => plMenu(pl, i);
            return el;
          }),
          h('div', { class: 'set-buttons' },
            btn({ class: 'button', onclick: () => W.addPlaylist('xtream') }, '＋ Xtream login'),
            btn({ class: 'button', onclick: () => W.addPlaylist('m3u') }, '＋ M3U playlist'),
            btn({ class: 'button', onclick: () => W.addPlaylist('hdhr') }, '＋ Antenna TV'),
            btn({ class: 'button', onclick: () => W.addPlaylist('tablo') }, '＋ Tablo'),
            btn({ class: 'button', onclick: async () => { await savePlaylists(); W.refresh(); } }, '⟳ Reload all')),
        ];
      },
    },
    guides: {
      label: '▦ TV guides',
      render() {
        const urls = W.settings.extraGuides || [];
        const status = W.guide.status || {};
        const st = (u) => { const s = status[u]; return !s ? '' : s.loading ? 'loading…' : s.error ? '⚠ ' + s.error : s.channels + ' channels · updated ' + new Date(s.loadedAt).toLocaleString(); };
        const own = [...D.lists.values()].map((s) => [D.playlistName(s.pl), D.providerGuide(s.pl, s)]).filter((x) => x[1]);
        return [
          h('div', { class: 'set-text' }, 'Each playlist uses its own guide. Extra guides (XMLTV links) fill in channels the playlist’s guide leaves empty. Guides refresh every 12 hours.'),
          h('div', { class: 'set-group' }, 'Playlist guides'),
          ...own.map(([n, u]) => h('div', { class: 'set-row static' }, h('div', { class: 'set-label' }, n), h('div', { class: 'set-sub' }, st(u) || (u.startsWith('hdhr:') ? 'HDHomeRun guide' : u.replace(/password=[^&]*/, 'password=…'))))),
          h('div', { class: 'set-group' }, 'Extra guides'),
          ...urls.map((u, i) => btn({ class: 'set-row', onclick: async () => {
            const j = await W.menu(u, [{ label: '▲ Try earlier', action: () => { if (i > 0) { urls.splice(i - 1, 0, urls.splice(i, 1)[0]); W.saveSettings(); W.refresh(); } } }, { label: '✕ Remove', danger: true, action: () => { urls.splice(i, 1); W.saveSettings(); W.refresh(); } }]);
          } }, h('div', { class: 'set-label' }, u), h('div', { class: 'set-sub' }, st(u)))),
          h('div', { class: 'set-buttons' },
            btn({ class: 'button', onclick: async () => {
              const r = await W.form('Add an extra TV guide', [{ key: 'url', label: 'XMLTV link (.xml or .xml.gz)', type: 'url', placeholder: 'https://…/guide.xml.gz' }]);
              if (r && r.url.trim()) { urls.push(r.url.trim()); W.settings.extraGuides = [...new Set(urls)]; W.saveSettings(); W.guide.ensureAll(); W.refresh(); }
            } }, '＋ Add a guide link'),
            btn({ class: 'button', onclick: async () => {
              const i = await W.menu('Free guides by country (epg.pw)', FREE_GUIDES.map(([n]) => ({ label: n })));
              if (i >= 0) { const u = freeGuideUrl(FREE_GUIDES[i][1]); if (!urls.includes(u)) urls.push(u); W.settings.extraGuides = urls; W.saveSettings(); W.guide.ensureAll(); W.toast('Downloading the ' + FREE_GUIDES[i][0] + ' guide…'); W.refresh(); }
            } }, '＋ Free guide by country'),
            btn({ class: 'button', onclick: () => { W.settings.extraFirst = !W.settings.extraFirst; W.saveSettings(); W.guide.ensureAll(); W.refresh(); } }, W.settings.extraFirst ? '✓ Extra guides first' : 'Playlist guide first'),
            btn({ class: 'button', onclick: async () => { const b = W.busy('Downloading the TV guides…'); await W.guide.ensureAll(true); b.close(); W.refresh(); } }, '⟳ Refresh guides now')),
        ];
      },
    },
    look: {
      label: '🎨 Appearance',
      render() {
        return [
          h('div', { class: 'set-group' }, 'Theme'),
          ...Object.entries(W.THEMES).map(([k, t]) => btn({ class: 'set-row' + (W.settings.theme === k ? ' checked' : ''), onclick: () => { W.settings.theme = k; W.saveSettings(); W.applyTheme(k); W.refresh(); } },
            h('div', { class: 'set-label' }, (W.settings.theme === k ? '● ' : '○ ') + t.label), h('div', { class: 'swatches' }, [0, 1, 3, 6].map((i) => h('span', { style: { background: `#${(t.p[i] & 0xffffff).toString(16).padStart(6, '0')}` } }))))),
          h('div', { class: 'set-group' }, 'Live TV'),
          btn({ class: 'set-row', onclick: async () => {
            const opts = [3, 4, 5, 6];
            const i = await W.menu('Upcoming shows under the preview', opts.map((n) => ({ label: n + ' rows', checked: W.settings.guideRows === n })));
            if (i >= 0) { W.settings.guideRows = opts[i]; W.saveSettings(); W.refresh(); }
          } }, h('div', { class: 'set-label' }, 'Upcoming shows under the preview'), h('div', { class: 'set-sub' }, (W.settings.guideRows || 4) + ' rows')),
          btn({ class: 'set-row', onclick: () => { W.settings.startup = W.settings.startup === 'last' ? 'home' : 'last'; W.saveSettings(); W.refresh(); } },
            h('div', { class: 'set-label' }, 'When ' + W.brand + ' starts'), h('div', { class: 'set-sub' }, W.settings.startup === 'last' ? 'Play the last channel' : 'Show the home screen')),
        ];
      },
    },
    player: {
      label: '▶ Player',
      render() {
        return [
          btn({ class: 'set-row', onclick: () => { W.settings.autoPip = W.settings.autoPip === false; W.saveSettings(); W.refresh(); } },
            h('div', { class: 'set-label' }, 'Picture-in-picture'), h('div', { class: 'set-sub' }, (W.settings.autoPip === false ? 'Off' : 'On') + ': ' + (W.isIOS ? 'leaving the app while something plays keeps it playing in a small window' : 'minimizing the window while something plays keeps it playing in a small window') + '. The player’s ▣ Mini player and ⧉ buttons work either way')),
          W.isIOS ? null : btn({ class: 'set-row', onclick: () => { W.settings.alwaysConvert = !W.settings.alwaysConvert; W.saveSettings(); W.refresh(); } },
            h('div', { class: 'set-label' }, 'Convert every live channel with FFmpeg'), h('div', { class: 'set-sub' }, W.settings.alwaysConvert ? 'On: steadier sound on some services, uses more CPU' : 'Off: only when a channel won’t play directly (recommended)')),
          btn({ class: 'set-row', onclick: async () => {
            const opts = [['auto', 'Automatic'], ['hls', 'HLS (.m3u8)'], ['ts', 'MPEG-TS (.ts)']];
            const i = await W.menu('Xtream live stream format', opts.map(([k, l]) => ({ label: l, checked: (W.settings.liveFormat || 'auto') === k })));
            if (i >= 0) { W.settings.liveFormat = opts[i][0]; W.saveSettings(); savePlaylists().then(() => W.refresh()); }
          } }, h('div', { class: 'set-label' }, 'Xtream live stream format'), h('div', { class: 'set-sub' }, { auto: 'Automatic', hls: 'HLS (.m3u8)', ts: 'MPEG-TS (.ts)' }[W.settings.liveFormat || 'auto'])),
          btn({ class: 'set-row', onclick: async () => {
            const p = await W.api.ext.pick();
            if (p) { W.settings.extPlayer = p; W.saveSettings(); W.refresh(); }
          } }, h('div', { class: 'set-label' }, W.isIOS ? 'External player' : 'External player (VLC or mpv)'), h('div', { class: 'set-sub' }, W.settings.extPlayer || (W.isIOS ? 'VLC' : W.isMac ? 'Not set: VLC (in Applications) is used when installed' : 'Not set: VLC is found automatically when installed'))),
          btn({ class: 'set-row', onclick: async () => {
            const r = await W.form('User agent', [{ key: 'ua', label: 'Sent to your provider (leave empty for the default)', value: W.settings.userAgent, placeholder: W.brand + '/1.0' }]);
            if (r) { W.settings.userAgent = r.ua.trim(); W.saveSettings(); W.api.net.setUserAgent(W.settings.userAgent || W.brand + '/1.0'); W.refresh(); }
          } }, h('div', { class: 'set-label' }, 'User agent'), h('div', { class: 'set-sub' }, W.settings.userAgent || W.brand + '/1.0 (default)')),
          h('div', { class: 'set-text' }, 'Player keys: Up/Down or Page Up/Down change channel, numbers jump to a channel, Left/Right skip in movies, Space or P pauses, I shows info, F toggles full screen, Esc goes back.'),
        ];
      },
    },
    info: {
      label: '🎬 Movie info',
      render() {
        return [
          h('div', { class: 'set-text' }, 'Ratings from IMDb, Rotten Tomatoes and Metacritic come from OMDb. A TMDb key adds backdrops, cast and trailers.'),
          btn({ class: 'set-row', onclick: async () => {
            const r = await W.form('OMDb key', [{ key: 'k', label: 'Your own OMDb key (empty = the built-in key)', value: W.settings.omdbKey }]);
            if (r) { W.settings.omdbKey = r.k.trim(); W.saveSettings(); W.refresh(); }
          } }, h('div', { class: 'set-label' }, 'OMDb key'), h('div', { class: 'set-sub' }, W.settings.omdbKey ? 'Your key' : 'Built-in key')),
          btn({ class: 'set-row', onclick: async () => {
            const r = await W.form('TMDb key', [{ key: 'k', label: 'TMDb API key (v3)', value: W.settings.tmdbKey }], { text: 'Free at themoviedb.org → Settings → API.' });
            if (r) { W.settings.tmdbKey = r.k.trim(); W.saveSettings(); W.refresh(); }
          } }, h('div', { class: 'set-label' }, 'TMDb key'), h('div', { class: 'set-sub' }, W.settings.tmdbKey ? 'Set' : 'Not set (optional)')),
          btn({ class: 'set-row', onclick: () => { W.save('meta', {}); W.toast('Movie info will be looked up again'); } }, h('div', { class: 'set-label' }, 'Forget saved movie info')),
        ];
      },
    },
    backup: {
      label: '⤓ Backup',
      render() {
        return [
          h('div', { class: 'set-text' }, 'Save playlists, guides, favorites, My List, watch progress and settings to a file, or bring them back.'),
          h('div', { class: 'set-buttons' },
            btn({ class: 'button', onclick: async () => { const p = await W.api.file.save(W.isIOS ? 'wygtv-ios-backup.json' : 'wygdesktop-backup.json', W.backupText()); if (p) W.toast('Saved to ' + p); } }, '⤒ Save a backup'),
            btn({ class: 'button', onclick: () => W.restoreBackup() }, '⤓ Restore a backup')),
          h('div', { class: 'set-group' }, 'Hidden categories'),
          btn({ class: 'set-row', onclick: () => { W.hidden = {}; W.save('hidden', W.hidden); W.toast('All categories are showing again'); W.refresh(); } },
            h('div', { class: 'set-label' }, 'Show every hidden category'), h('div', { class: 'set-sub' }, Object.keys(W.hidden).length + ' hidden')),
          btn({ class: 'set-row', onclick: async () => { if (await W.confirm('Clear history', 'Forget Recent channels and watch progress?', 'Clear')) { W.recent = []; W.progress = {}; W.save('recent', []); W.save('progress', {}); W.refresh(); } } },
            h('div', { class: 'set-label' }, 'Clear Recent and watch progress')),
        ];
      },
    },
    about: {
      label: 'ℹ About & updates',
      render() {
        const ver = h('div', { class: 'set-sub' }, '…');
        W.api.app.info().then((i) => { ver.textContent = 'Version ' + i.version + ' · ' + (i.platform === 'win32' ? 'Windows' : i.platform === 'linux' ? 'Linux' : i.platform === 'darwin' ? 'macOS' : i.platform === 'ios' || i.platform === 'tizen' ? i.device : i.platform); });
        return [
          h('div', { class: 'about' }, h('img', { src: 'assets/icon.png', alt: '' }), h('div', null, h('div', { class: 'brand' }, W.brand), ver, h('div', { class: 'set-sub' }, W.isIOS ? 'WygTV for iPhone and iPad.' : W.isTV ? 'WygTV for Samsung Smart TVs.' : 'The desktop copy of WygTV.'))),
          h('div', { class: 'about maker' }, h('img', { src: 'assets/wyglaf.png', alt: 'Wyglaf' }), h('div', null, h('div', { class: 'brand' }, 'Made by Wyglaf'), h('div', { class: 'set-sub' }, 'The person behind WygTV.'))),
          h('div', { class: 'set-buttons' },
            W.isTV ? null : btn({ class: 'button primary', onclick: () => W.checkUpdates(true) }, '⟳ Check for updates'),
            W.isIOS ? null : btn({ class: 'button', onclick: () => W.api.win.quit() }, '⏻ Quit')),
        ];
      },
    },
  };

  async function plMenu(pl, i) {
    const list = W.settings.playlists;
    await W.menu(D.playlistName(pl), [
      { label: '✎ Edit', action: () => W.addPlaylist(pl.type, pl) },
      { label: '⟳ Reload', action: () => savePlaylists(pl).then(() => W.refresh()) },
      i > 0 ? { label: '▲ Move up', action: () => { list.splice(i - 1, 0, list.splice(i, 1)[0]); W.saveSettings(); D.loadAll().then(() => W.refresh()); } } : null,
      { label: '✕ Remove', danger: true, action: async () => {
        if (!(await W.confirm('Remove ' + D.playlistName(pl) + '?', 'Favorites from it are kept in case you add it again.', 'Remove'))) return;
        list.splice(i, 1); W.saveSettings(); D.lists.delete(pl.id); D.reindex(); D.emit('loaded'); W.refresh();
      } },
    ]);
  }

  W.checkUpdates = async (manual) => {
    try {
      const r = await W.api.update.check();
      if (!r.available) { if (manual) W.alert('Up to date', 'You have the newest ' + W.brand + ' (' + r.current + ').'); return; }
      const go = await W.confirm('Update available', r.kind === 'ios' ? `${W.brand} ${r.latest} is ready (you have ${r.current}). Open AltStore to update?` : r.kind === 'mac' ? `${W.brand} ${r.latest} is ready (you have ${r.current}). Download it now? Open the downloaded file and drag ${W.brand} into Applications to replace this one.` : `${W.brand} ${r.latest} is ready (you have ${r.current}). Install it now?`, r.kind === 'ios' ? 'Open AltStore' : r.kind === 'deb' || r.kind === 'mac' ? 'Download' : 'Install', 'Later');
      if (!go) return;
      const b = W.busy('Downloading the update…');
      W.api.onUpdateProgress((p) => b.set('Downloading the update… ' + p + '%'));
      try { await W.api.update.install(r); } finally { b.close(); }
    } catch (e) { if (manual) W.alert('Updates', 'Could not check for updates: ' + e.message); }
  };

  W.screens.settings = {
    render(args, entry) {
      let sec = args.section || W.load('setSec', 'playlists');
      const menuCol = h('div', { class: 'col menu-col', 'data-col': '', 'data-wrap': '' });
      const body = h('div', { class: 'col set-body', 'data-col': '' });
      const show = (k) => {
        sec = k; W.save('setSec', k); entry.args = { section: k };
        W.$$('.item', menuCol).forEach((x) => x.classList.toggle('active', x.dataset.k === 's:' + k));
        body.replaceChildren(h('div', { class: 'col-title' }, sections[k].label), ...sections[k].render().filter(Boolean));
      };
      menuCol.append(...Object.entries(sections).map(([k, s]) => {
        const el = btn({ class: 'item' + (k === sec ? ' active' : ''), 'data-k': 's:' + k, 'data-autofocus': k === sec ? '' : null, onclick: () => { show(k); W.focusFirst(body); } }, h('span', { class: 'item-label' }, s.label));
        el._onfocus = W.keyOnly(() => { if (sec !== k) show(k); });
        return el;
      }));
      show(sec);
      const off = D.on('status', () => { if (sec === 'playlists' && body.isConnected && !body.contains(document.activeElement)) show(sec); });
      const off2 = D.on('guides', () => { if (sec === 'guides' && body.isConnected) W.refresh(); });
      entry.cleanup = () => { off(); off2(); };
      return h('div', { class: 'screen settings' }, W.header('Settings'), h('div', { class: 'settings-body' }, menuCol, body));
    },
  };
})();
