'use strict';
/**
 * FFmpeg helper for streams the built-in player can't decode on its own (MPEG-2 antenna channels,
 * AC-3/DTS audio, AVI/MKV files…). FFmpeg converts them on the fly to H.264 + AAC in MPEG-TS, served
 * from a local web address that the player opens like any other live stream.
 */
const http = require('http');
const { spawn, execFile } = require('child_process');
const net = require('./net');

function ffmpegPath() {
  const fs = require('fs');
  if (process.env.WYG_FFMPEG) return process.env.WYG_FFMPEG;
  if (process.platform === 'linux') {
    // An installed FFmpeg (the .deb depends on Ubuntu's) uses the system's libraries: preferred.
    if (!process.env.WYG_NO_SYSTEM_FFMPEG && fs.existsSync('/usr/bin/ffmpeg')) return '/usr/bin/ffmpeg';
    // Otherwise (AppImage) the up-to-date static build shipped in resources/ffmpeg. ffmpeg-static's
    // Linux build crashes on newer kernels such as Ubuntu 24.04's, so it is only the last resort.
    const own = process.resourcesPath ? require('path').join(process.resourcesPath, 'ffmpeg', 'ffmpeg') : '';
    if (own && fs.existsSync(own)) return own;
  }
  let p = '';
  try { p = require('ffmpeg-static'); } catch { }
  if (p) p = p.replace('app.asar' + require('path').sep, 'app.asar.unpacked' + require('path').sep);
  return p || 'ffmpeg';
}

const jobs = new Map(); // id → { url, mode, seek, proc }
let server = null, port = 0, nextId = 1;

function ensureServer() {
  if (server) return Promise.resolve(port);
  return new Promise((resolve, reject) => {
    server = http.createServer((req, res) => {
      const m = /^\/s\/(\d+)\.ts/.exec(req.url || '');
      const job = m && jobs.get(m[1]);
      if (!job) { res.writeHead(404); res.end(); return; }
      if (job.proc) { try { job.proc.kill('SIGKILL'); } catch { } }
      res.writeHead(200, { 'Content-Type': 'video/mp2t', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' });
      const proc = spawn(ffmpegPath(), args(job), { windowsHide: true });
      job.proc = proc;
      job.log = '';
      proc.stdout.pipe(res);
      proc.stderr.on('data', (d) => { job.log = (job.log + d).slice(-4000); });
      proc.on('error', (e) => { job.log += '\n' + e.message; res.end(); });
      proc.on('close', () => { if (job.proc === proc) job.proc = null; res.end(); });
      req.on('close', () => { try { proc.kill('SIGKILL'); } catch { } });
    });
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => { port = server.address().port; resolve(port); });
  });
}

function args(job) {
  const a = ['-hide_banner', '-loglevel', 'error', '-nostdin'];
  if (/^https?:/i.test(job.url)) {
    a.push('-user_agent', job.ua || net.getUserAgent(), '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '5');
  }
  a.push('-fflags', '+genpts+discardcorrupt');
  if (job.seek > 0) a.push('-ss', String(job.seek));
  a.push('-i', job.url, '-map', '0:v:0?', '-map', '0:a:' + (job.audio || 0) + '?', '-sn', '-dn');
  if (job.mode === 'copy') a.push('-c:v', 'copy');
  else a.push('-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency', '-crf', '21', '-pix_fmt', 'yuv420p',
    '-vf', 'yadif=0:-1:1', '-g', '60');
  a.push('-c:a', 'aac', '-b:a', '192k', '-ac', '2', '-f', 'mpegts', '-muxdelay', '0', 'pipe:1');
  return a;
}

/** Starts (or replaces) a converted stream. mode: 'copy' keeps the video as is, 'x264' re-encodes it. */
async function start(opts) {
  stopAll();
  await ensureServer();
  const id = String(nextId++);
  jobs.set(id, { url: opts.url, mode: opts.mode || 'x264', seek: opts.seek || 0, audio: opts.audio || 0, ua: opts.ua, proc: null });
  return { id, url: `http://127.0.0.1:${port}/s/${id}.ts` };
}

function stopAll() {
  for (const [id, job] of jobs) { if (job.proc) { try { job.proc.kill('SIGKILL'); } catch { } } jobs.delete(id); }
}

function lastLog(id) { const j = jobs.get(String(id)); return j ? j.log || '' : ''; }

/**
 * What's inside a stream: { duration (s), video: 'h264', audio: ['ac3', …], container, width, height }.
 * Reads FFmpeg's description of the input (it stops after a few seconds at most).
 */
function probe(url, ua) {
  return new Promise((resolve) => {
    const a = ['-hide_banner', '-nostdin'];
    if (/^https?:/i.test(url)) a.push('-user_agent', ua || net.getUserAgent(), '-rw_timeout', '10000000');
    a.push('-analyzeduration', '3000000', '-probesize', '3000000', '-i', url);
    execFile(ffmpegPath(), a, { timeout: 15000, windowsHide: true }, (err, stdout, stderr) => {
      resolve(parseProbe(String(stderr || '')));
    });
  });
}

function parseProbe(s) {
  const out = { duration: 0, video: '', audio: [], subs: [], container: '', width: 0, height: 0, ok: false };
  const c = /Input #0, ([^,]+)/.exec(s); if (c) { out.container = c[1]; out.ok = true; }
  const d = /Duration: (\d+):(\d+):(\d+(?:\.\d+)?)/.exec(s);
  if (d) out.duration = +d[1] * 3600 + +d[2] * 60 + parseFloat(d[3]);
  const re = /Stream #0:\d+(?:\[[^\]]*\])?(?:\(([a-z]+)\))?: (Video|Audio|Subtitle): (\w+)([^\n]*)/g;
  let m;
  while ((m = re.exec(s))) {
    if (m[2] === 'Video' && !out.video && !/attached pic/.test(m[4])) {
      out.video = m[3];
      const wh = /, (\d{2,5})x(\d{2,5})/.exec(m[4]); if (wh) { out.width = +wh[1]; out.height = +wh[2]; }
    } else if (m[2] === 'Audio') out.audio.push({ codec: m[3], lang: m[1] || '' });
    else if (m[2] === 'Subtitle') out.subs.push({ codec: m[3], lang: m[1] || '' });
  }
  return out;
}

module.exports = { start, stopAll, probe, parseProbe, ffmpegPath, lastLog, args };
