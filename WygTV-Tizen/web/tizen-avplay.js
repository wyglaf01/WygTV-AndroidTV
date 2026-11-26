'use strict';
/*
 * WygTV for Samsung TVs: a stand-in for the <video> element, played by Samsung's AVPlay.
 *
 * AVPlay plays in the TV's own hardware: HLS, MPEG-TS, MP4 and MKV, H.264, HEVC and MPEG-2 video (antenna TV),
 * and AAC, Dolby AC-3 / E-AC-3 sound. It draws on a video plane underneath the web page, in the rectangle given to
 * setDisplayRect(); the page shows it through an <object type="application/avplayer"> element.
 *
 * window.wygAvVideo() returns that <object>, given the parts of HTMLVideoElement that WygDesktop's player.js uses
 * (src, play(), pause(), paused, currentTime, duration, readyState, volume, error) and firing the same events
 * (loadedmetadata, play, playing, pause, waiting, timeupdate, ended, error). So the shared player keeps doing
 * reconnects, channel up/down, resume and its on-screen bar as it does everywhere else.
 *
 * There is one AVPlay per app: setting a new src stops and closes the old stream first.
 */
(function () {
  const av = () => window.webapis && window.webapis.avplay;
  if (!av()) return; // not on a TV (e.g. a browser preview): player.js uses a normal <video>

  /** Display coordinates: AVPlay works in a 1920×1080 space, whatever the page's own width is. */
  const scale = () => 1920 / (window.innerWidth || 1920);

  // While a video is full screen, everything behind the player is hidden and the page made see-through, so the
  // picture on the video plane underneath shows even where the <object> doesn't make a hole (tizen.css).
  function watchFull() {
    const root = document.documentElement;
    const check = () => {
      const p = document.getElementById('player');
      root.classList.toggle('av-full', !!(p && !p.hidden && p.classList.contains('full')));
    };
    new MutationObserver(check).observe(root, { subtree: true, attributes: true, attributeFilter: ['class', 'hidden'] });
    check();
  }

  window.wygAvVideo = function wygAvVideo() {
    document.documentElement.classList.add('avplay');
    watchFull();
    const el = document.createElement('object');
    el.setAttribute('type', 'application/avplayer');
    el.className = 'video';
    let src = '', state = 'NONE', wantPlay = false, ready = 0, paused = true, err = null, volume = 1, token = 0;
    let lastRect = '', rectTimer = 0, lastTime = 0, durationMs = 0, buffering = false, suspended = false, textOn = false, chosen = {};

    const fire = (type) => el.dispatchEvent(new Event(type));
    const call = (fn) => { try { return fn(); } catch (e) { return undefined; } };
    const stateNow = () => call(() => av().getState()) || 'NONE';

    function place() {
      if (!src) return;
      const r = el.getBoundingClientRect(), k = scale();
      const rect = [r.left, r.top, r.width, r.height].map((v) => Math.round(v * k));
      if (rect[2] < 2 || rect[3] < 2) return; // hidden: keep the last place
      const key = rect.join(',');
      if (key === lastRect) return;
      lastRect = key;
      call(() => av().setDisplayRect(rect[0], rect[1], rect[2], rect[3]));
    }

    function close() {
      token++;
      clearInterval(rectTimer); rectTimer = 0;
      // Stopping costs the TV time: only when a stream is open (player.js clears the old one in several ways).
      if (state !== 'NONE') { call(() => av().stop()); call(() => av().close()); }
      state = 'NONE'; ready = 0; paused = true; wantPlay = false; lastRect = ''; lastTime = 0; durationMs = 0; buffering = false;
      textOn = false; chosen = {};
    }

    function failWith(message) {
      err = { code: 4, message: String(message || 'AVPlay error') };
      fire('error');
    }

    function open(url) {
      close();
      err = null;
      src = url;
      if (!url) return;
      const mine = token;
      try {
        av().open(url);
        state = 'OPEN';
        av().setListener({
          onbufferingstart() { if (mine !== token) return; buffering = true; fire('waiting'); },
          onbufferingcomplete() {
            if (mine !== token) return;
            buffering = false;
            if (!paused) fire('playing');
          },
          oncurrentplaytime(ms) {
            if (mine !== token) return;
            lastTime = ms;
            if (buffering) { buffering = false; fire('playing'); }
            fire('timeupdate');
          },
          onstreamcompleted() { if (mine !== token) return; paused = true; fire('ended'); },
          onerror(type) { if (mine !== token) return; failWith(type); },
          onevent() { },
          onsubtitlechange(ms, text) { if (mine === token && textOn && el.onsubtitle) el.onsubtitle(String(text || ''), Number(ms) || 0); },
          ondrmevent() { },
        });
        call(() => av().setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX'));
        // Live streams can take a while to start; player.js gives up after 15 s and retries by itself.
        call(() => av().setTimeoutForBuffering(10));
        lastRect = '';
        place();
        rectTimer = setInterval(place, 300);
        state = 'PREPARING';
        av().prepareAsync(() => {
          if (mine !== token) return;
          state = 'READY'; ready = 4;
          durationMs = call(() => av().getDuration()) || 0;
          fire('loadedmetadata');
          if (wantPlay) start();
        }, (e) => { if (mine === token) failWith(e && (e.name || e.message) || e); });
      } catch (e) {
        failWith(e && (e.name || e.message) || e);
      }
    }

    function start() {
      if (stateNow() === 'PLAYING') return;
      try { av().play(); } catch (e) { failWith(e && (e.name || e.message) || e); return; }
      paused = false;
      fire('play');
      fire('playing');
    }

    Object.defineProperties(el, {
      src: { get: () => src, set: (v) => open(String(v || '')), configurable: true },
      currentSrc: { get: () => src },
      paused: { get: () => paused },
      readyState: { get: () => ready },
      error: { get: () => err },
      duration: { get: () => (durationMs > 0 ? durationMs / 1000 : NaN) },
      currentTime: {
        get: () => lastTime / 1000,
        set: (s) => {
          if (!ready) return;
          const ms = Math.max(0, Math.round(s * 1000));
          lastTime = ms;
          call(() => av().seekTo(ms, () => fire('timeupdate'), () => { }));
        },
      },
      // The TV remote sets the volume; this is only remembered for player.js.
      volume: { get: () => volume, set: (v) => { volume = v; } },
      videoWidth: { get: () => (ready && !paused ? 1920 : 0) },
      videoHeight: { get: () => (ready && !paused ? 1080 : 0) },
      disablePictureInPicture: { value: true },
      // Lets player.js know this element plays every format itself (no hls.js or mpegts.js).
      wygAvPlay: { value: true },
    });
    el.play = function () {
      wantPlay = true;
      if (ready) start();
      return Promise.resolve();
    };
    el.pause = function () {
      wantPlay = false;
      if (!ready || paused) return;
      call(() => av().pause());
      paused = true;
      fire('pause');
    };
    el.load = function () { if (!src) close(); };

    // ---------------------------------------------------------------- sound tracks and subtitles
    const extra = (t) => { try { return JSON.parse(t.extra_info || '{}'); } catch (e) { return {}; } };
    /** The stream's tracks of a type ('AUDIO' or 'TEXT'): [{ index, lang, codec }]. */
    el.wygTracks = function (type) {
      if (!ready) return [];
      const all = call(() => av().getTotalTrackInfo()) || [];
      return Array.prototype.filter.call(all, (t) => t.type === type).map((t) => {
        const x = extra(t);
        return { index: Number(t.index), lang: x.language || x.track_lang || '', codec: x.fourCC || '' };
      });
    };
    /** The track playing now (−1: subtitles off). */
    el.wygTrack = function (type) {
      if (type === 'TEXT' && !textOn) return -1;
      if (chosen[type] != null) return chosen[type];
      const cur = call(() => av().getCurrentStreamInfo()) || [];
      const t = Array.prototype.find.call(cur, (c) => c.type === type);
      return t ? Number(t.index) : (type === 'TEXT' ? -1 : 0);
    };
    el.wygSelectTrack = function (type, index) {
      if (type === 'TEXT' && index < 0) { textOn = false; call(() => av().setSilentSubtitle(true)); return; }
      call(() => av().setSelectTrack(type, index));
      chosen[type] = index;
      if (type === 'TEXT') { textOn = true; call(() => av().setSilentSubtitle(false)); }
    };
    el.canPlayType = () => '';
    const removeAttribute = el.removeAttribute.bind(el);
    el.removeAttribute = (name) => { if (name === 'src') { src = ''; close(); } else removeAttribute(name); };

    // The TV's Home button sends WygTV to the background: AVPlay must let go of the decoder, and pick up again
    // when WygTV comes back (live streams restart at the live point).
    document.addEventListener('visibilitychange', () => {
      if (!src) return;
      if (document.hidden) { if (!suspended) { suspended = true; call(() => av().suspend()); } }
      else if (suspended) { suspended = false; call(() => av().restore()); lastRect = ''; place(); }
    });
    window.addEventListener('pagehide', close);
    return el;
  };
})();
