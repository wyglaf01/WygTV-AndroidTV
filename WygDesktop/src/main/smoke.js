'use strict';
/**
 * Self-test used by CI on real Ubuntu machines: `wygdesktop --smoke=http://127.0.0.1:8765` against
 * test/mock-server.js. Loads a playlist, plays an H.264 channel directly and an MPEG-2/AC-3 channel
 * through FFmpeg, loads movies and the guide, prints a JSON report and exits 0 (pass) or 1 (fail).
 */
const os = require('os');
const path = require('path');

function arg() {
  const a = process.argv.find((x) => x.startsWith('--smoke='));
  return a ? a.slice(8) : '';
}

/** Call before app is ready: a throw-away profile so the test never touches real settings. */
function prepare(app) {
  if (!arg()) return false;
  app.setPath('userData', path.join(os.tmpdir(), 'wygdesktop-smoke-' + process.pid));
  return true;
}

function script(base) {
  return `(async () => {
    const out = { ok: false };
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    W.settings.playlists = [{ id: 'smoke', type: 'xtream', name: 'Smoke test', host: ${JSON.stringify(base)}, user: 'demo', pass: 'demo' }];
    W.saveSettings();
    await W.data.loadAll();
    out.channels = W.data.allChannels().length;
    out.playlistError = (W.data.lists.get('smoke') || {}).error || '';
    const play = async (key, wait) => {
      W.player.playChannel(W.data.channel(key), { full: true });
      const t0 = Date.now();
      while (Date.now() - t0 < wait) {
        await sleep(500);
        const v = document.querySelector('#player video');
        if (v && v.currentTime > 3) return { played: v.currentTime, video: v.webkitVideoDecodedByteCount, audio: v.webkitAudioDecodedByteCount, w: v.videoWidth };
      }
      const v = document.querySelector('#player video');
      return { played: v ? v.currentTime : 0, msg: (document.querySelector('.player-msg') || {}).textContent || '' };
    };
    out.h264 = await play('smoke|100', 30000); W.player.stop(); await sleep(500);
    out.mpeg2ViaFfmpeg = await play('smoke|104', 45000); W.player.stop();
    await W.guide.ensureAll();
    const g = await W.guide.get(W.data.allChannels());
    out.guideChannels = Object.values(g).filter((l) => l.length).length;
    await W.data.loadAllVod('vod'); await W.data.loadAllVod('series');
    out.movies = W.data.vodItems('vod').length; out.series = W.data.vodItems('series').length;
    out.ok = out.channels > 0 && out.h264.played > 3 && out.mpeg2ViaFfmpeg.played > 3 && out.guideChannels > 0 && out.movies > 0;
    return out;
  })()`;
}

function run(app, win, stream) {
  const base = arg();
  win.webContents.once('did-finish-load', async () => {
    let report;
    try {
      await new Promise((r) => setTimeout(r, 1500));
      report = await win.webContents.executeJavaScript(script(base));
    } catch (e) { report = { ok: false, error: String(e && e.message || e) }; }
    report.version = app.getVersion();
    report.platform = process.platform + ' ' + os.release();
    report.ffmpeg = stream.ffmpegPath();
    report.sandbox = app.commandLine.hasSwitch('no-sandbox') ? 'off' : 'on';
    process.stdout.write('WYG-SMOKE ' + JSON.stringify(report) + '\n');
    app.exit(report.ok ? 0 : 1);
  });
  setTimeout(() => { process.stdout.write('WYG-SMOKE {"ok":false,"error":"timed out"}\n'); app.exit(1); }, 240000);
}

module.exports = { prepare, run, arg };
