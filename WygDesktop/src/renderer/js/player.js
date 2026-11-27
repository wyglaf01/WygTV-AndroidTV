'use strict';
/*
 * The video player. Plays HLS (hls.js), MPEG-TS (mpegts.js) and ordinary files directly; anything the
 * built-in decoders can't handle (MPEG-2 antenna channels, AC-3/DTS sound, AVI…) is converted on the fly
 * by FFmpeg. Live streams reconnect by themselves when they stall.
 *
 * It shows either as a small preview inside the Live TV screen or full screen with an on-screen bar.
 *
 * On Samsung TVs (WygTV-Tizen) the bridge's makeVideo() gives an element played by the TV's own AVPlay instead
 * of a <video>: it plays every format itself, MPEG-2 antenna channels and Dolby sound included.
 */
(function () {
  const { h } = W;
  const P = W.player = {};
  let root, video, overlay, osdTitle, osdSub, osdNow, osdBar, osdFill, osdTimes, osdButtons, msgEl, spinner, numEl;
  let engine = null; // { kind, obj }
  let item = null, opts = {};
  let full = false, host = null, hostObs = null;
  let mini = false, miniBar = null; // the mini player: the picture in a corner, still playing, while the rest of the app is used
  let offset = 0, duration = 0; // for FFmpeg-converted files (seek = restart at offset)
  let attempt = 0, lastProgress = 0, stallTimer = null, startTimer = null, saveTimer = null, hideTimer = null, audioCheck = null;
  let probeInfo = null, audioIndex = 0, numBuf = '', numTimer = null, subsEl = null, subsTimer = null;

  function build() {
    video = (W.api && W.api.makeVideo && W.api.makeVideo()) || h('video', { class: 'video', playsinline: true });
    spinner = h('div', { class: 'player-spinner spinner', hidden: true });
    msgEl = h('div', { class: 'player-msg', hidden: true });
    numEl = h('div', { class: 'player-num', hidden: true });
    // Subtitles the TV's player hands over as text (Samsung TVs: AVPlay); other players draw their own.
    subsEl = h('div', { class: 'player-subs', hidden: true });
    osdTitle = h('div', { class: 'osd-title' });
    osdSub = h('div', { class: 'osd-sub' });
    osdNow = h('div', { class: 'osd-now' });
    osdFill = h('div', { class: 'osd-fill' });
    osdBar = h('div', { class: 'osd-bar', onclick: (e) => {
      if (isLiveItem()) return;
      const r = osdBar.getBoundingClientRect();
      seekTo(((e.clientX - r.left) / r.width) * totalDuration());
    } }, osdFill);
    osdTimes = h('div', { class: 'osd-times' });
    osdButtons = h('div', { class: 'osd-buttons', 'data-col': '' });
    overlay = h('div', { class: 'osd', hidden: true },
      h('div', { class: 'osd-top' }, h('img', { class: 'osd-logo', src: 'assets/icon.png', alt: '' }), h('div', { class: 'osd-titles' }, osdTitle, osdSub), h('div', { class: 'osd-clock' })),
      h('div', { class: 'osd-bottom' }, osdNow, osdBar, osdTimes, osdButtons));
    miniBar = h('div', { class: 'mini-bar' });
    root = h('div', { id: 'player', hidden: true }, video, spinner, msgEl, numEl, overlay, subsEl, miniBar);
    if (video.wygTracks) video.onsubtitle = (text, ms) => {
      clearTimeout(subsTimer);
      subsEl.textContent = text || '';
      subsEl.hidden = !text;
      if (text) subsTimer = setTimeout(() => { subsEl.hidden = true; }, Math.max(500, ms || 3000));
    };
    // Picture-in-picture: the browser's own floating window. Going into it from full screen also shrinks the
    // in-app picture to the mini player, so the app can be used while it plays.
    video.addEventListener('enterpictureinpicture', () => { if (full) P.mini(); renderMiniBar(); });
    video.addEventListener('leavepictureinpicture', () => renderMiniBar());
    if (W.settings.autoPip !== false) video.setAttribute('autopictureinpicture', ''); // iOS / Safari: keep playing in a window when the app goes to the background
    document.addEventListener('keydown', (e) => {
      if (!mini || !item || full) return;
      if (e.key === 'MediaPlayPause') { togglePause(); renderMiniBar(); e.preventDefault(); }
      else if (e.key === 'MediaStop') { P.stop(); e.preventDefault(); }
    }, true);
    W.$('#app').append(root);

    video.addEventListener('playing', () => { spinner.hidden = true; msgEl.hidden = true; attempt = Math.min(attempt, 1); clearTimeout(startTimer); });
    video.addEventListener('waiting', () => { spinner.hidden = false; armStall(); });
    video.addEventListener('timeupdate', () => { lastProgress = Date.now(); spinner.hidden = true; updateBar(); });
    video.addEventListener('ended', onEnded);
    video.addEventListener('play', () => renderMiniBar());
    video.addEventListener('pause', () => renderMiniBar());
    video.addEventListener('error', () => fail('video', video.error && video.error.message));
    video.addEventListener('dblclick', () => { if (full) W.api.win.fullscreen(); else P.enterFull(); });
    // Touch screens: a double-tap on the picture does the same (not every system turns two taps into a double-click).
    let lastTap = 0;
    video.addEventListener('touchend', (e) => {
      const now = Date.now();
      if (now - lastTap < 320) { e.preventDefault(); lastTap = 0; if (full) { if (W.api.win && W.api.win.fullscreen && !W.isIOS) W.api.win.fullscreen(); } else P.enterFull(); return; }
      lastTap = now;
    }, { passive: false });
    video.addEventListener('click', () => { if (mini) { P.enterFull(); return; } if (full) { if (overlay.hidden) showOsd(); else if (!isLiveItem()) togglePause(); } });
    root.addEventListener('mousemove', () => { if (full) showOsd(true); });
    setInterval(() => { const c = W.$('.osd-clock', root); if (c) c.textContent = W.fmtTime(Date.now()); }, 1000 * 15);
  }

  const isLiveItem = () => item && item.kind === 'live';
  const totalDuration = () => duration || (isFinite(video.duration) ? video.duration : 0);
  const position = () => offset + (video.currentTime || 0);
  P.isFull = () => full;
  P.isMini = () => mini;
  P.item = () => item;
  P.playing = () => !!item;

  // ------------------------------------------------------------------ engines
  function destroyEngine() {
    clearTimeout(startTimer); clearTimeout(stallTimer); clearTimeout(audioCheck);
    if (engine) {
      try {
        if (engine.kind === 'hls') engine.obj.destroy();
        if (engine.kind === 'mpegts') { engine.obj.pause(); engine.obj.unload(); engine.obj.detachMediaElement(); engine.obj.destroy(); }
      } catch { }
      engine = null;
    }
    try { video.pause(); } catch { }
    video.removeAttribute('src');
    try { video.load(); } catch { }
  }

  function startEngine(url, kindHint) {
    destroyEngine();
    const isHls = kindHint === 'hls' || (!kindHint && /\.m3u8(\?|$)|\/hls\//i.test(url));
    const isTs = kindHint === 'ts' || (!kindHint && (/\.ts(\?|$)/i.test(url) || (isLiveItem() && !/\.(mp4|mkv|webm|mov|m4v)(\?|$)/i.test(url))));
    if (video.wygAvPlay) {
      // Samsung TVs: AVPlay plays HLS, MPEG-TS and files itself.
      video.src = url;
      engine = { kind: 'native', obj: null };
    } else if (isHls && W.isIOS && video.canPlayType('application/vnd.apple.mpegurl')) {
      // iPhone/iPad: Apple's own player plays HLS best (and needs no CORS from the provider).
      video.src = url;
      engine = { kind: 'native', obj: null };
    } else if (isHls && window.Hls && Hls.isSupported()) {
      const hls = new Hls({ maxBufferLength: 30, backBufferLength: 30, liveSyncDurationCount: 3, enableWorker: true, manifestLoadingMaxRetry: 3, levelLoadingMaxRetry: 4 });
      hls.on(Hls.Events.ERROR, (_e, d) => {
        if (!d.fatal) return;
        if (d.type === Hls.ErrorTypes.MEDIA_ERROR && !engine._recovered) { engine._recovered = true; hls.recoverMediaError(); return; }
        fail('hls', d.details);
      });
      hls.loadSource(url);
      hls.attachMedia(video);
      engine = { kind: 'hls', obj: hls };
    } else if (isTs && window.mpegts && mpegts.isSupported()) {
      const live = isLiveItem() || /127\.0\.0\.1/.test(url);
      const p = mpegts.createPlayer({ type: 'mpegts', isLive: live, url }, {
        enableWorker: true, enableStashBuffer: true, stashInitialSize: 512 * 1024, lazyLoad: false,
        liveBufferLatencyChasing: false, autoCleanupSourceBuffer: true, autoCleanupMaxBackwardDuration: 60, autoCleanupMinBackwardDuration: 30,
      });
      p.on(mpegts.Events.ERROR, (type, detail) => fail('mpegts', type + ' ' + detail));
      p.attachMediaElement(video);
      p.load();
      engine = { kind: 'mpegts', obj: p };
    } else {
      video.src = url;
      engine = { kind: 'native', obj: null };
    }
    video.volume = W.load('volume', 1);
    const pr = video.play(); if (pr && pr.catch) pr.catch(() => { });
    spinner.hidden = false;
    lastProgress = Date.now();
    clearTimeout(startTimer);
    startTimer = setTimeout(() => { if (video.readyState < 3 && item) fail('timeout', 'No picture after 15 seconds'); }, 15000);
    if (engine.kind !== 'native' || isLiveItem()) {
      clearTimeout(audioCheck);
      audioCheck = setTimeout(checkAudio, 7000);
    }
  }

  /** The built-in decoder plays the picture but not the sound (AC-3 etc.): switch to FFmpeg, video copied. */
  function checkAudio() {
    if (!item || opts.convert) return;
    // iPhone/iPad: an MPEG-2 channel can play its AC-3 sound with no picture; treat that as not playing.
    if (W.api.noFFmpeg) {
      if (engine && engine.kind === 'mpegts' && !video.paused && video.currentTime > 1 && !video.videoWidth) fail('video', 'sound but no picture');
      return;
    }
    if (video.webkitVideoDecodedByteCount > 0 && video.webkitAudioDecodedByteCount === 0 && !video.paused) {
      convert('copy', 'no sound');
    }
  }

  function armStall() {
    clearTimeout(stallTimer);
    if (!isLiveItem()) return;
    stallTimer = setTimeout(() => {
      if (item && isLiveItem() && Date.now() - lastProgress > 9000) reconnect('stalled');
    }, 10000);
  }

  // ------------------------------------------------------------------ errors, fall-backs and recovery
  async function fail(where, detail) {
    if (!item) return;
    console.warn('player', where, detail);
    const cur = item;
    if (attempt === 0 && !opts.convert) {
      // 1. The other live format (HLS ↔ TS) when the provider offers both.
      if (isLiveItem() && item.alt && !opts.triedAlt) {
        opts.triedAlt = true; attempt = 0;
        showMsg('Trying the other stream format…');
        startEngine(item.alt);
        return;
      }
      // iPhone/iPad have no FFmpeg: reconnect a live channel that was playing, else offer VLC.
      if (W.api.noFFmpeg) {
        if (isLiveItem() && attempt < 6 && opts.wasPlaying) { reconnect(where); return; }
        destroyEngine(); spinner.hidden = true;
        showError(W.isTV ? 'This stream will not play on this TV (' + String(detail || where).slice(0, 60) + ').'
          : 'This stream will not play in ' + W.brand + ' (' + String(detail || where).slice(0, 60) + '). VLC for iPhone and iPad plays more kinds of video.');
        return;
      }
      attempt = 1;
      // 2. FFmpeg conversion.
      showMsg('Converting the stream with FFmpeg…');
      const info = await W.api.stream.probe(item.url).catch(() => null);
      if (cur !== item) return;
      probeInfo = info;
      if (info && info.duration && !isLiveItem()) duration = info.duration;
      convert(info && /h264/.test(info.video) ? 'copy' : 'x264', where);
      return;
    }
    if (opts.convert === 'copy' && attempt < 2) { attempt = 2; convert('x264', where); return; }
    if (isLiveItem() && attempt < 6 && opts.wasPlaying) { reconnect(where); return; }
    destroyEngine();
    spinner.hidden = true;
    showError('This stream will not play' + (detail ? ' (' + String(detail).slice(0, 80) + ')' : '') + '.');
  }

  function reconnect(why) {
    attempt++;
    showMsg('Reconnecting…');
    console.info('reconnect', why);
    const c = opts.convert;
    if (c) convert(c, why, true); else startEngine(item.url);
  }

  async function convert(mode, why, keepAttempt) {
    if (!item) return;
    opts.convert = mode;
    const r = await W.api.stream.start({ url: item.url, mode, seek: isLiveItem() ? 0 : offset, audio: audioIndex });
    startEngine(r.url, 'ts');
  }

  function showMsg(t) { msgEl.textContent = t; msgEl.hidden = false; clearTimeout(showMsg.t); showMsg.t = setTimeout(() => { msgEl.hidden = true; }, 4000); }
  function showError(t) {
    msgEl.replaceChildren(h('div', null, t), h('div', { class: 'player-msg-actions' },
      W.btn({ class: 'button', onclick: () => P.retry() }, 'Try again'),
      W.isTV ? null : W.btn({ class: 'button', onclick: () => P.external() }, 'Open in ' + W.extLabel)));
    msgEl.hidden = false;
    if (full) { clearTimeout(showMsg.t); W.focusFirst(msgEl); }
  }

  // ------------------------------------------------------------------ public
  /**
   * it: { kind: 'live'|'movie'|'episode', key, name, url, alt?, logo?, num?, convert?, ch? }
   * o: { list: [channels] for channel up/down, full: bool, host: element for the preview, resume: seconds, onEnded }
   */
  P.play = async function play(it, o = {}) {
    if (!root) build();
    saveProgress();
    item = it; opts = Object.assign({}, o); attempt = 0; offset = 0; duration = 0; probeInfo = null; audioIndex = 0;
    root.hidden = false;
    msgEl.hidden = true;
    if (o.full) P.enterFull(); else if (o.host) P.attach(o.host);
    if (it.kind === 'live') { W.addRecent(it.key); W.api.net.setUserAgent(W.settings.userAgent || ''); }
    renderOsd();
    if (it.kind === 'live' && it.ch && it.ch.tablo && !it.url) {
      // A Tablo channel: ask the Tablo to tune it, which gives this play's HLS link.
      spinner.hidden = false;
      try { it.url = await W.api.tablo.watch(it.ch.tablo.base, it.ch.tablo.id); }
      catch (e) { if (item === it) { spinner.hidden = true; showError(e.message || String(e)); } return; }
      if (item !== it) return;
    }
    if (it.kind === 'live') {
      if (W.api.noFFmpeg && it.convert && !W.api.playsAntenna) { spinner.hidden = true; showError('Antenna channels need converting, which ' + W.brand + ' can’t do. Open them in VLC instead.'); }
      else if (!W.api.noFFmpeg && (it.convert || W.settings.alwaysConvert)) { attempt = 1; convert(it.convert || 'copy'); }
      else startEngine(it.url);
    } else {
      // Files: look inside first, so unsupported sound or containers go straight to FFmpeg.
      spinner.hidden = false;
      const info = /\.m3u8(\?|$)/i.test(it.url) ? null : await W.api.stream.probe(it.url).catch(() => null);
      if (item !== it) return;
      probeInfo = info;
      if (info && info.duration) duration = info.duration;
      offset = o.resume || 0;
      if (info && info.ok && !directOk(info)) { attempt = 1; convert(/h264/.test(info.video) ? 'copy' : 'x264'); }
      else {
        duration = 0; // the player knows the real duration
        startEngine(it.url, /\.m3u8/i.test(it.url) ? 'hls' : 'native');
        if (offset) { const o2 = offset; offset = 0; video.addEventListener('loadedmetadata', () => { video.currentTime = o2; }, { once: true }); }
      }
      clearInterval(saveTimer);
      saveTimer = setInterval(saveProgress, 10000);
    }
    video.addEventListener('playing', () => { opts.wasPlaying = true; }, { once: true });
  };

  function directOk(info) {
    const container = info.container || '';
    if (/avi|flv|asf|mpeg$|mpegts|vob|wtv/.test(container)) return false;
    if (info.video && !/h264|hevc|vp8|vp9|av1|mpeg4/.test(info.video)) return false;
    if (info.video === 'mpeg4') return false;
    const a = info.audio[0];
    if (a && !/aac|mp3|opus|vorbis|flac/.test(a.codec)) return false;
    return true;
  }

  P.retry = () => { if (item) { const it = item, o = opts; o.triedAlt = false; delete o.convert; if (it.ch && it.ch.tablo) it.url = ''; /* tune the Tablo again */ P.play(it, o); } };

  P.external = async () => {
    if (!item) return;
    try { const exe = await W.api.ext.play(W.settings.extPlayer, item.url, item.name); W.toast('Opened in ' + exe.split(/[\\/]/).pop()); P.stop(); }
    catch (e) { W.alert('External player', e.message); }
  };

  P.stop = function stop() {
    saveProgress();
    clearInterval(saveTimer);
    destroyEngine();
    W.api && W.api.stream.stop().catch(() => { });
    item = null;
    if (mini) leaveMini();
    if (document.pictureInPictureElement === video) document.exitPictureInPicture().catch(() => { });
    if (root) { root.hidden = true; overlay.hidden = true; msgEl.hidden = true; }
    if (full) P.exitFull(true);
    P.detach();
  };

  function saveProgress() {
    if (!item || item.kind === 'live' || !video) return;
    const pos = position(), dur = totalDuration();
    if (dur > 0) W.setProgress(item.key, pos, dur, item.kind === 'episode' ? { series: item.series, name: item.name } : { name: item.name });
    if (item.series && dur > 0) W.setProgress(item.series, pos, dur, { ep: item.key, epName: item.name, poster: item.poster });
  }

  function onEnded() {
    if (!item) return;
    if (isLiveItem()) { reconnect('ended'); return; }
    saveProgress();
    if (opts.onEnded) { const fn = opts.onEnded; fn(); return; }
    P.stop();
  }

  // ------------------------------------------------------------------ preview / full screen
  P.attach = function attach(el) {
    if (mini) leaveMini();
    host = el;
    full = false;
    root.classList.remove('full');
    root.classList.add('preview');
    overlay.hidden = true;
    place();
    if (hostObs) hostObs.disconnect();
    hostObs = new ResizeObserver(place);
    hostObs.observe(el);
    window.addEventListener('resize', place);
  };

  P.detach = function detach() {
    if (hostObs) hostObs.disconnect();
    hostObs = null; host = null;
    window.removeEventListener('resize', place);
    if (root) root.classList.remove('preview');
  };

  function place() {
    if (!host || full || !root) return;
    if (!host.isConnected) { P.stop(); return; }
    // The box is measured on screen; the player is placed inside #app. When #app is magnified (Samsung TVs' text
    // size), screen pixels are turned back into #app's own: k is how much bigger #app is drawn than laid out.
    const r = host.getBoundingClientRect(), app = W.$('#app'), a = app.getBoundingClientRect();
    const k = app.offsetWidth ? a.width / app.offsetWidth : 1;
    Object.assign(root.style, { left: (r.left - a.left) / k + 'px', top: (r.top - a.top) / k + 'px', width: r.width / k + 'px', height: r.height / k + 'px' });
  }

  P.enterFull = function enterFull() {
    if (!root) build();
    if (mini) leaveMini();
    full = true;
    root.classList.add('full');
    root.classList.remove('preview');
    Object.assign(root.style, { left: '', top: '', width: '', height: '' });
    W.keyHook = keys;
    showOsd();
  };

  P.exitFull = function exitFull(stopping) {
    full = false;
    root.classList.remove('full');
    overlay.hidden = true;
    W.keyHook = null;
    if (!stopping && host && host.isConnected && item) { root.classList.add('preview'); place(); }
    else if (!stopping) { P.stop(); }
    if (P.onExitFull) P.onExitFull();
  };

  // ------------------------------------------------------------------ mini player and picture-in-picture
  /** Full screen (or a preview) → a window in the corner that keeps playing while the rest of the app is used. */
  P.mini = function toMini() {
    if (!item || !root) return;
    const wasFull = full;
    full = false;
    W.keyHook = null;
    if (hostObs) hostObs.disconnect();
    hostObs = null; host = null;
    window.removeEventListener('resize', place);
    root.classList.remove('full', 'preview');
    root.classList.add('mini');
    Object.assign(root.style, { left: '', top: '', width: '', height: '' });
    overlay.hidden = true;
    mini = true;
    renderMiniBar();
    if (wasFull && P.onExitFull) P.onExitFull();
  };

  function leaveMini() {
    mini = false;
    if (root) root.classList.remove('mini');
    if (miniBar) miniBar.replaceChildren();
  }

  function renderMiniBar() {
    if (!miniBar) return;
    miniBar.replaceChildren();
    if (!mini || !item) return;
    const b = (label, title, fn) => W.btn({ class: 'mini-btn', title, onclick: (e) => { e.stopPropagation(); fn(); } }, label);
    // append() and replaceChildren() write a null as the text "null": leave the missing buttons out.
    miniBar.append(...[
      h('div', { class: 'mini-title' }, (item.num ? item.num + '  ' : '') + item.name),
      b('⛶', 'Full screen', () => P.enterFull()),
      b(video.paused ? '▶' : '❚❚', video.paused ? 'Play' : 'Pause', () => { togglePause(); renderMiniBar(); }),
      P.pipSupported() ? b('⧉', 'Picture-in-picture', () => P.pip()) : null,
      b('✕', 'Close', () => P.stop())].filter(Boolean));
  }

  P.pipSupported = () => !!(document.pictureInPictureEnabled && video && !video.disablePictureInPicture);

  /** The system's floating picture-in-picture window (on or off). */
  P.pip = async function pip() {
    if (!item || !video) return;
    try {
      if (document.pictureInPictureElement === video) await document.exitPictureInPicture();
      else await video.requestPictureInPicture();
    } catch (e) { W.toast('Picture-in-picture isn’t available for this video'); }
  };

  /** The window was minimized while something plays (desktop): keep it playing in picture-in-picture. */
  P.autoPip = function autoPip() {
    if (W.settings.autoPip === false || !item || video.paused || document.pictureInPictureElement) return;
    P.pip();
  };

  // ------------------------------------------------------------------ on-screen bar
  function renderOsd() {
    if (!item) return;
    osdTitle.textContent = (item.num ? item.num + '  ' : '') + item.name;
    osdSub.textContent = item.kind === 'live' ? (W.data.selName && opts.listName || '') : item.sub || '';
    osdButtons.replaceChildren();
    const b = (label, fn, title) => W.btn({ class: 'osd-btn', onclick: () => { fn(); showOsd(); }, title }, label);
    if (item.kind === 'live') {
      osdButtons.append(
        b('▲ Ch +', () => P.zap(-1), 'Previous channel (Up / Page Up)'),
        b('▼ Ch −', () => P.zap(1), 'Next channel (Down / Page Down)'),
        b(W.isFav(item.key) ? '★ Favorite' : '☆ Favorite', () => { const on = W.toggleFav(item.key); W.toast(on ? 'Added to Favorites' : 'Removed from Favorites'); renderOsd(); }),
        b('▦ Guide', () => { const ch = item; P.exitFull(); W.go('guide', { sel: opts.sel, focus: ch.key }); }),
      );
    } else {
      osdButtons.append(
        b('⏪ 10 s', () => seekBy(-10)), b('⏯ Pause', togglePause), b('⏩ 30 s', () => seekBy(30)),
      );
    }
    if (probeInfo && probeInfo.audio.length > 1) osdButtons.append(b('🔊 Sound', pickAudio));
    else if (video.wygTracks && video.wygTracks('AUDIO').length > 1) osdButtons.append(b('🔊 Sound', () => pickTrack('AUDIO')));
    if (video.wygTracks && video.wygTracks('TEXT').length) osdButtons.append(b('💬 Subtitles', () => pickTrack('TEXT')));
    osdButtons.append(...[W.isTV ? null : b('🔉 Volume', pickVolume), b('▣ Mini player', () => { P.mini(); }, 'Keep watching in a corner while you browse'),
      P.pipSupported() ? b('⧉ Picture-in-picture', () => P.pip(), 'Keep watching in a floating window') : null, W.isIOS || W.isTV ? null : b('⛶ Window', () => W.api.win.fullscreen()), W.isTV ? null : b('↗ ' + W.extLabel, P.external), b('✕ Close', () => W.back())].filter(Boolean));
    updateNow();
  }

  async function updateNow() {
    if (!item || item.kind !== 'live' || !item.ch) { osdNow.textContent = ''; return; }
    const it = item;
    const g = await W.guide.get([it.ch]);
    if (it !== item) return;
    const nn = W.guide.nowNext(g[it.key] || []);
    osdNow.replaceChildren(...[
      nn.now ? h('div', { class: 'osd-prog' }, h('b', null, nn.now[2]), h('span', null, '  ' + W.fmtTime(nn.now[0]) + ' – ' + W.fmtTime(nn.now[1]))) : h('div', { class: 'osd-prog muted' }, 'No guide information'),
      nn.now && nn.now[3] ? h('div', { class: 'osd-desc' }, nn.now[3]) : null,
      nn.next ? h('div', { class: 'osd-nextp' }, 'Next: ' + W.fmtTime(nn.next[0]) + '  ' + nn.next[2]) : null].filter(Boolean));
    if (nn.now) { osdFill.style.width = Math.min(100, (Date.now() - nn.now[0]) / (nn.now[1] - nn.now[0]) * 100) + '%'; osdTimes.textContent = ''; }
    else osdFill.style.width = '0';
  }

  function updateBar() {
    if (!item || isLiveItem() || overlay.hidden) return;
    const d = totalDuration(), p = position();
    osdFill.style.width = d ? Math.min(100, p / d * 100) + '%' : '0';
    osdTimes.textContent = W.fmtDur(p) + ' / ' + (d ? W.fmtDur(d) : '--:--');
  }

  function showOsd(noFocus) {
    if (mini) return;
    if (!full) return;
    const was = overlay.hidden;
    overlay.hidden = false;
    W.$('.osd-clock', root).textContent = W.fmtTime(Date.now());
    if (was) { updateNow(); updateBar(); }
    if (was && !noFocus && msgEl.hidden) W.focusFirst(osdButtons);
    clearTimeout(hideTimer);
    hideTimer = setTimeout(hideOsd, 5000);
  }
  function hideOsd() {
    if (!overlay.hidden && overlay.contains(document.activeElement) && document.activeElement.matches(':hover')) { hideTimer = setTimeout(hideOsd, 3000); return; }
    overlay.hidden = true;
    if (overlay.contains(document.activeElement)) document.activeElement.blur();
  }

  function togglePause() { if (video.paused) video.play(); else video.pause(); showMsg(video.paused ? '⏸ Paused' : '▶ Playing'); }

  function seekTo(t) {
    const d = totalDuration();
    t = Math.max(0, d ? Math.min(d - 2, t) : t);
    if (opts.convert) { offset = t; convert(opts.convert); }
    else video.currentTime = t;
    updateBar();
  }
  function seekBy(s) { if (!isLiveItem()) { seekTo(position() + s); showMsg((s > 0 ? '⏩ ' : '⏪ ') + W.fmtDur(position()) + ' / ' + W.fmtDur(totalDuration())); } }

  async function pickAudio() {
    const list = probeInfo.audio.map((a, i) => ({ label: `Track ${i + 1}${a.lang ? ' · ' + a.lang : ''} · ${a.codec}`, checked: i === audioIndex }));
    const i = await W.menu('Sound track', list);
    if (i < 0 || i === audioIndex) return;
    audioIndex = i;
    if (!isLiveItem()) offset = position();
    convert(opts.convert || 'copy');
  }

  /** Sound tracks and subtitles the TV's own player found in the stream (Samsung TVs). */
  async function pickTrack(type) {
    const list = video.wygTracks(type);
    const cur = video.wygTrack(type);
    const items = list.map((t, i) => ({ label: (type === 'AUDIO' ? 'Track ' : 'Subtitles ') + (i + 1) + (t.lang ? ' · ' + t.lang : '') + (t.codec ? ' · ' + t.codec : ''), checked: t.index === cur }));
    if (type === 'TEXT') items.unshift({ label: 'Off', checked: cur < 0 });
    const i = await W.menu(type === 'AUDIO' ? 'Sound track' : 'Subtitles', items);
    if (i < 0) return;
    const pick = type === 'TEXT' ? (i === 0 ? -1 : list[i - 1].index) : list[i].index;
    video.wygSelectTrack(type, pick);
    if (type === 'TEXT' && pick < 0) subsEl.hidden = true;
    W.toast(type === 'AUDIO' ? 'Sound: ' + items[i].label : pick < 0 ? 'Subtitles off' : 'Subtitles on');
  }

  /** The CC button: subtitles on (the first track) or off. */
  function toggleSubs() {
    if (!video.wygTracks) return;
    const list = video.wygTracks('TEXT');
    if (!list.length) { W.toast('No subtitles in this stream'); return; }
    const on = video.wygTrack('TEXT') < 0;
    video.wygSelectTrack('TEXT', on ? list[0].index : -1);
    if (!on) subsEl.hidden = true;
    W.toast(on ? 'Subtitles on' : 'Subtitles off');
  }

  async function pickVolume() {
    const levels = [100, 80, 60, 40, 20, 0];
    const cur = Math.round(video.volume * 100);
    const i = await W.menu('Volume', levels.map((v) => ({ label: v ? v + '%' : 'Mute', checked: Math.abs(v - cur) < 10 })));
    if (i < 0) return;
    video.volume = levels[i] / 100; W.save('volume', video.volume);
  }

  /** Channel up/down through the list the channel was started from. */
  P.zap = function zap(dir) {
    if (!item || !isLiveItem() || !opts.list || !opts.list.length) return;
    const i = opts.list.findIndex((c) => c.key === item.key);
    const next = opts.list[(i + dir + opts.list.length) % opts.list.length];
    P.playChannel(next, Object.assign({}, opts, { full, host: host }));
  };

  P.playChannel = (ch, o = {}) => P.play({ kind: 'live', key: ch.key, name: ch.name, url: ch.url, alt: ch.alt, logo: ch.logo, num: ch.num, convert: ch.convert, ch }, o);

  function keys(e) {
    if (!full || W.$('#layers > .layer')) return false;
    const k = e.key;
    if (k === 'Escape' || k === 'Backspace' || k === 'BrowserBack' || k === 'GoBack') {
      if (!overlay.hidden && !isLiveItem()) { hideOsd(); return true; }
      return false;
    }
    if ((/^[0-9]$/.test(k) || ((k === '-' || k === '.') && numBuf)) && isLiveItem()) { typeNumber(k); return true; }
    if (k === 'MediaPlayPause' || k === 'p' || k === 'P') { togglePause(); return true; }
    // Remotes with separate play and pause keys (Samsung TVs), and rewind / fast-forward.
    if (k === 'MediaPlay') { if (video.paused) togglePause(); return true; }
    if (k === 'MediaPause') { if (!video.paused) togglePause(); return true; }
    if (k === 'MediaRewind') { seekBy(-10); return true; }
    if (k === 'MediaFastForward') { seekBy(30); return true; }
    if (k === 'Subtitle') { toggleSubs(); return true; }
    if (k === 'PageUp' || k === 'MediaTrackPrevious' || k === 'ChannelUp') { isLiveItem() ? P.zap(-1) : seekBy(-60); return true; }
    if (k === 'PageDown' || k === 'MediaTrackNext' || k === 'ChannelDown') { isLiveItem() ? P.zap(1) : seekBy(60); return true; }
    if (k === 'MediaStop') { W.back(); return true; }
    if (k === 'f' || k === 'F') { W.api.win.fullscreen(); return true; }
    if (k === 'i' || k === 'I') { overlay.hidden ? showOsd() : hideOsd(); return true; }
    if (overlay.hidden || !msgEl.hidden && msgEl.contains(document.activeElement)) {
      if (!msgEl.hidden && msgEl.contains(document.activeElement)) return false;
      if (k === 'ArrowUp' && isLiveItem()) { P.zap(-1); return true; }
      if (k === 'ArrowDown' && isLiveItem()) { P.zap(1); return true; }
      if (k === 'ArrowLeft' && !isLiveItem()) { seekBy(-10); return true; }
      if (k === 'ArrowRight' && !isLiveItem()) { seekBy(30); return true; }
      if (k === ' ' && !isLiveItem()) { if (!e.repeat) togglePause(); return true; }
      if (k === 'Enter' || k === ' ' || k.startsWith('Arrow')) { if (!e.repeat) showOsd(); return true; }
      return false;
    }
    clearTimeout(hideTimer);
    hideTimer = setTimeout(hideOsd, 5000);
    return false;
  }

  /**
   * The channel with this number: in the list first, then anywhere. "4-1", "4.1" and "41" all find 4.1
   * (antenna sub-channels), when no channel is numbered 41 itself.
   */
  P.channelByNumber = function channelByNumber(n, list) {
    const want = String(n).replace(/-/g, '.');
    const all = W.data.allChannels();
    const lists = list && list.length ? [list, all] : [all];
    for (const l of lists) { const c = l.find((x) => x.num === want); if (c) return c; }
    const digits = want.replace(/\D/g, '');
    for (const l of lists) { const c = l.find((x) => x.num && x.num.replace(/\D/g, '') === digits); if (c) return c; }
    return null;
  };

  function typeNumber(d) {
    numBuf = (numBuf + (d === '-' ? '.' : d)).slice(-5);
    numEl.textContent = numBuf; numEl.hidden = false;
    clearTimeout(numTimer);
    numTimer = setTimeout(() => {
      numEl.hidden = true;
      const ch = P.channelByNumber(numBuf, opts.list);
      numBuf = '';
      if (ch) P.playChannel(ch, Object.assign({}, opts, { full: true, host }));
      else W.toast('No channel with that number');
    }, 1500);
  }
})();
