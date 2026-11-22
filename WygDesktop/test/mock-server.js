'use strict';
/**
 * A pretend Xtream Codes provider for trying WygDesktop without a real service:
 *   node test/mock-server.js [port] [mediaDir]
 * Log in with server http://127.0.0.1:<port>, username demo, password demo.
 * mediaDir may hold h264.ts, mpeg2.ts, hls/index.m3u8, movie.mp4 and movie_ac3.mkv (see README).
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const port = +(process.argv[2] || 8765);
const media = process.argv[3] || process.env.MOCK_MEDIA || path.join(__dirname, 'media');
const base = `http://127.0.0.1:${port}`;

const liveCats = [['1', 'News'], ['2', 'Sports'], ['3', 'Entertainment'], ['4', 'Kids'], ['5', 'Antenna-style (MPEG-2)']];
const names = ['WYG News', 'Bronze Sports', 'Royal Movies', 'Gearworks Kids', 'Steam Channel', 'Copper Cinema', 'Brass Band TV', 'Airship Weather',
  'Clockwork Comedy', 'Victorian Drama', 'Cog Science', 'Telegraph 24', 'Zeppelin Travel', 'Boiler Room Music', 'Gaslight Docs', 'Valve Racing',
  'Rivet Cooking', 'Pocketwatch History', 'Monocle Mysteries', 'Top Hat Talk', 'Lantern Lifestyle', 'Piston Football', 'Dynamo Shopping', 'Aether Nature'];
const streams = names.map((n, i) => ({
  num: i + 1, name: (i % 3 === 0 ? 'US: ' : '') + n + (i % 4 === 1 ? ' HD' : ''), stream_id: 100 + i, stream_icon: `${base}/logo/${i}.svg`,
  epg_channel_id: i < 20 ? `ch${i}.us` : '', category_id: String(i === 4 ? 5 : (i % 4) + 1),
}));
const shows = ['Morning Report', 'The Big Match', 'Feature Film', 'Cartoon Hour', 'Engine Talk', 'Evening News', 'Late Show', 'Documentary', 'Quiz Night', 'Weather Watch'];

function svg(text, w, h, hue) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="hsl(${hue},55%,40%)"/><stop offset="1" stop-color="hsl(${hue},60%,15%)"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/><text x="50%" y="50%" font-family="sans-serif" font-size="${Math.round(w / 9)}" fill="#f0c080" text-anchor="middle" dominant-baseline="middle">${text}</text></svg>`;
}

function xmltv() {
  const now = Date.now();
  const start = Math.floor(now / 1800e3) * 1800e3 - 3 * 3600e3;
  const fmt = (t) => new Date(t).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';
  let x = '<?xml version="1.0" encoding="UTF-8"?>\n<tv>\n';
  streams.forEach((s, i) => { if (s.epg_channel_id) x += `<channel id="${s.epg_channel_id}"><display-name>${names[i]}</display-name></channel>\n`; });
  streams.forEach((s, i) => {
    if (!s.epg_channel_id) return;
    let t = start, k = i;
    while (t < now + 30 * 3600e3) {
      const len = [30, 60, 90, 45][k % 4] * 60e3;
      x += `<programme start="${fmt(t)}" stop="${fmt(t + len)}" channel="${s.epg_channel_id}"><title>${shows[k % shows.length]} &amp; more</title><desc>Episode ${k} of ${shows[k % shows.length]} on ${names[i]}.</desc></programme>\n`;
      t += len; k++;
    }
  });
  return x + '</tv>\n';
}

const movies = Array.from({ length: 40 }, (_, i) => ({
  stream_id: 500 + i, name: i === 0 ? 'EN - The Matrix (1999)' : i === 1 ? 'Sound Test AC3 (2020)' : `Movie Number ${i + 1} (${1990 + (i % 30)})`,
  stream_icon: `${base}/poster/m${i}.svg`, rating: (5 + (i % 5)).toFixed(1), category_id: String((i % 3) + 10), added: String(1700000000 + i * 1000),
  container_extension: i === 1 ? 'mkv' : 'mp4',
}));
const series = Array.from({ length: 12 }, (_, i) => ({
  series_id: 900 + i, name: i === 0 ? 'Breaking Bad (2008)' : `Series ${i + 1}`, cover: `${base}/poster/s${i}.svg`, rating: '8.1', category_id: String((i % 2) + 20),
  plot: 'A pretend series for testing.', releaseDate: '2008-01-20', genre: 'Drama',
}));

function send(res, code, body, type) {
  res.writeHead(code, { 'Content-Type': type || 'application/json', 'Access-Control-Allow-Origin': '*' });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

function file(req, res, p, type) {
  if (!fs.existsSync(p)) return send(res, 404, 'missing ' + path.basename(p), 'text/plain');
  const size = fs.statSync(p).size;
  const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
  if (m) {
    const s = +m[1], e = m[2] ? +m[2] : size - 1;
    res.writeHead(206, { 'Content-Type': type, 'Content-Range': `bytes ${s}-${e}/${size}`, 'Content-Length': e - s + 1, 'Accept-Ranges': 'bytes' });
    fs.createReadStream(p, { start: s, end: e }).pipe(res);
  } else {
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': size, 'Accept-Ranges': 'bytes', 'Access-Control-Allow-Origin': '*' });
    fs.createReadStream(p).pipe(res);
  }
}

http.createServer((req, res) => {
  const u = new URL(req.url, base);
  const q = u.searchParams;
  const p = u.pathname;
  if (p === '/player_api.php') {
    if (q.get('username') !== 'demo' || q.get('password') !== 'demo') return send(res, 200, { user_info: { auth: 0 } });
    switch (q.get('action')) {
      case null: return send(res, 200, { user_info: { auth: 1, status: 'Active', allowed_output_formats: ['m3u8', 'ts'] }, server_info: {} });
      case 'get_live_categories': return send(res, 200, liveCats.map(([id, n]) => ({ category_id: id, category_name: n })));
      case 'get_live_streams': return send(res, 200, streams);
      case 'get_vod_categories': return send(res, 200, [['10', 'Action'], ['11', 'Comedy'], ['12', 'Drama']].map(([id, n]) => ({ category_id: id, category_name: n })));
      case 'get_vod_streams': return send(res, 200, movies);
      case 'get_vod_info': return send(res, 200, { info: { plot: 'A pretend movie served by the WygDesktop test server.', genre: 'Action', releasedate: '1999-03-31', cast: 'Keanu Reeves', director: 'The Wachowskis' } });
      case 'get_series_categories': return send(res, 200, [['20', 'Drama'], ['21', 'Comedy']].map(([id, n]) => ({ category_id: id, category_name: n })));
      case 'get_series': return send(res, 200, series);
      case 'get_series_info': return send(res, 200, {
        info: { plot: 'A chemistry teacher turns to crime.' },
        episodes: { 1: [1, 2, 3].map((e) => ({ id: `${q.get('series_id')}0${e}`, episode_num: e, title: `Pilot part ${e}`, container_extension: 'mp4', info: { plot: 'Episode plot.', duration_secs: 120 } })),
          2: [1, 2].map((e) => ({ id: `${q.get('series_id')}1${e}`, episode_num: e, title: `Season two ${e}`, container_extension: 'mp4', info: { duration_secs: 120 } })) },
      });
      default: return send(res, 200, []);
    }
  }
  if (p === '/xmltv.php') return send(res, 200, zlib.gzipSync(xmltv()), 'application/gzip');
  if (p.startsWith('/logo/')) { const i = parseInt(p.slice(6), 10); return send(res, 200, svg(names[i].split(' ').map((w) => w[0]).join(''), 160, 100, 220 + i * 7), 'image/svg+xml'); }
  if (p.startsWith('/poster/')) { const id = p.slice(8, -4); return send(res, 200, svg(id.toUpperCase(), 300, 450, id.startsWith('s') ? 30 : 215), 'image/svg+xml'); }
  let m = /^\/live\/demo\/demo\/(\d+)\.(ts|m3u8)$/.exec(p);
  if (m) {
    if (m[1] === '104') return file(req, res, path.join(media, 'mpeg2.ts'), 'video/mp2t');
    if (m[2] === 'm3u8') { res.writeHead(302, { Location: `${base}/hls/index.m3u8` }); return res.end(); }
    return file(req, res, path.join(media, 'h264.ts'), 'video/mp2t');
  }
  if (p.startsWith('/hls/')) return file(req, res, path.join(media, 'hls', path.basename(p)), p.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t');
  m = /^\/(movie|series)\/demo\/demo\/(\d+)\.(\w+)$/.exec(p);
  if (m) return m[2] === '501' ? file(req, res, path.join(media, 'movie_ac3.mkv'), 'video/x-matroska') : file(req, res, path.join(media, 'movie.mp4'), 'video/mp4');
  send(res, 404, 'not found', 'text/plain');
}).listen(port, '127.0.0.1', () => console.log(`Mock Xtream provider on ${base} (demo / demo), media from ${media}`));
