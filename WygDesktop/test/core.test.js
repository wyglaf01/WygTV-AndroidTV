'use strict';
const test = require('node:test');
const assert = require('node:assert');
const xmltv = require('../src/main/xmltv');
const m3u = require('../src/main/m3u');
const hdhr = require('../src/main/hdhr');
const stream = require('../src/main/stream');
const { newer } = (() => { try { return require('../src/main/updater'); } catch { return {}; } })();

test('XMLTV times with and without offsets', () => {
  assert.strictEqual(xmltv.parseTime('20261122030000 +0000'), Date.UTC(2026, 10, 22, 3, 0, 0));
  assert.strictEqual(xmltv.parseTime('20261122030000 +0100'), Date.UTC(2026, 10, 22, 2, 0, 0));
  assert.strictEqual(xmltv.parseTime('20261122030000 -0500'), Date.UTC(2026, 10, 22, 8, 0, 0));
  assert.strictEqual(xmltv.parseTime('202611220300'), Date.UTC(2026, 10, 22, 3, 0, 0));
});

test('XMLTV parsing across chunk boundaries keeps programmes in the window', () => {
  const now = Date.UTC(2026, 10, 22, 12, 0, 0);
  const xml = `<?xml version="1.0"?><tv><channel id="bbc1.uk"><display-name>BBC One</display-name><display-name>BBC 1 HD</display-name></channel>
<programme start="20261122110000 +0000" stop="20261122123000 +0000" channel="bbc1.uk"><title lang="en">News &amp; Weather</title><sub-title>Part 1</sub-title><desc>Headlines</desc></programme>
<programme start="20261101110000 +0000" stop="20261101120000 +0000" channel="bbc1.uk"><title>Too old</title></programme>
<programme start="20261122123000 +0000" stop="20261122130000 +0000" channel="bbc1.uk"><title><![CDATA[Café Talk]]></title></programme></tv>`;
  const p = new xmltv.Parser({ now });
  for (let i = 0; i < xml.length; i += 7) p.push(xml.slice(i, i + 7));
  const g = p.finish();
  assert.deepStrictEqual(g.channels['bbc1.uk'].names, ['BBC One', 'BBC 1 HD']);
  assert.strictEqual(g.programmes['bbc1.uk'].length, 2);
  assert.strictEqual(g.programmes['bbc1.uk'][0][2], 'News & Weather');
  assert.strictEqual(g.programmes['bbc1.uk'][0][3], 'Part 1 — Headlines');
  assert.strictEqual(g.programmes['bbc1.uk'][1][2], 'Café Talk');
});

test('channel names match guide names loosely', () => {
  assert.strictEqual(xmltv.nameKey('US: CNN HD'), xmltv.nameKey('CNN'));
  assert.strictEqual(xmltv.nameKey('|UK| BBC One FHD'), xmltv.nameKey('BBC One'));
  assert.notStrictEqual(xmltv.nameKey('BBC One'), xmltv.nameKey('BBC Two'));
});

test('M3U playlists: attributes, groups, guide link, movies', () => {
  const r = m3u.parse(`#EXTM3U url-tvg="http://g/guide.xml.gz"
#EXTINF:-1 tvg-id="cnn.us" tvg-logo="http://l/cnn.png" group-title="News, USA",CNN, International
http://s/live/1.ts
#EXTINF:-1 group-title="Movies",The Matrix (1999)
http://s/movie/u/p/2.mkv
#EXTINF:-1,No Group
#EXTGRP:Misc
http://s/3.m3u8`);
  assert.strictEqual(r.guide, 'http://g/guide.xml.gz');
  assert.strictEqual(r.items.length, 3);
  assert.deepStrictEqual([r.items[0].name, r.items[0].group, r.items[0].epgId, r.items[0].kind], ['CNN, International', 'News, USA', 'cnn.us', 'live']);
  assert.strictEqual(r.items[1].kind, 'movie');
  assert.strictEqual(r.items[2].group, 'Misc');
});

test('HDHomeRun discovery packets round-trip with a valid CRC', () => {
  const req = hdhr.discoverRequest();
  assert.strictEqual(req.readUInt16BE(0), 2);
  const tag = (t, v) => Buffer.concat([Buffer.from([t, v.length]), v]);
  const be32 = (n) => { const b = Buffer.alloc(4); b.writeUInt32BE(n); return b; };
  const payload = Buffer.concat([tag(0x01, be32(1)), tag(0x02, be32(0x1234ABCD)), tag(0x10, Buffer.from([4])), tag(0x2A, Buffer.from('http://192.168.1.50:80'))]);
  const reply = hdhr.frame(3, payload);
  const d = hdhr.parseReply(reply, '192.168.1.50');
  assert.deepStrictEqual([d.isTuner, d.id, d.tuners, d.base], [true, '1234ABCD', 4, 'http://192.168.1.50:80']);
  reply[reply.length - 1] ^= 0xff;
  assert.strictEqual(hdhr.parseReply(reply, '1.2.3.4'), null);
  assert.strictEqual(hdhr.baseFromInput('192.168.1.50'), 'http://192.168.1.50');
  assert.strictEqual(hdhr.baseFromInput('http://hdhr.local/discover.json'), 'http://hdhr.local');
});

test('HDHomeRun guide.php becomes guide programmes by channel number', () => {
  const out = { channels: {}, programmes: {} };
  const latest = hdhr.parseGuide([{ GuideNumber: '4.1', GuideName: 'WNBC', Guide: [{ StartTime: 100, EndTime: 200, Title: 'News', EpisodeTitle: 'Ep', Synopsis: 'Story' }] }], out);
  assert.strictEqual(latest, 200);
  assert.deepStrictEqual(out.programmes['4.1'], [[100000, 200000, 'News', 'Ep — Story']]);
});

test('FFmpeg description of a stream is read', () => {
  const p = stream.parseProbe(`Input #0, matroska,webm, from 'x.mkv':
  Duration: 01:02:03.50, start: 0.000000, bitrate: 2000 kb/s
  Stream #0:0(eng): Video: h264 (High), yuv420p(progressive), 1920x1080, 23.98 fps
  Stream #0:1(eng): Audio: ac3, 48000 Hz, 5.1(side), fltp, 384 kb/s
  Stream #0:2(spa): Audio: aac (LC), 48000 Hz, stereo
  Stream #0:3(eng): Subtitle: subrip`);
  assert.strictEqual(p.duration, 3723.5);
  assert.strictEqual(p.video, 'h264');
  assert.deepStrictEqual(p.audio.map((a) => a.codec + '/' + a.lang), ['ac3/eng', 'aac/spa']);
  assert.strictEqual(p.width, 1920);
  assert.strictEqual(p.subs.length, 1);
});

// ------------------------------------------------------------------ Tablo
const tablo = require('../src/main/tablo');

test('Tablo addresses get the port', () => {
  assert.strictEqual(tablo.baseFromInput('192.168.1.60'), 'http://192.168.1.60:8885');
  assert.strictEqual(tablo.baseFromInput('http://192.168.1.60/'), 'http://192.168.1.60:8885');
  assert.strictEqual(tablo.baseFromInput('192.168.1.60:9000'), 'http://192.168.1.60:9000');
  assert.strictEqual(tablo.baseFromInput(''), '');
});

test('Tablo cloud lookup, server info and a locked Tablo', () => {
  const d = tablo.parseCloud({ cpes: [{ serverid: 'SID_A', name: 'Den', private_ip: '192.168.1.60' }, { serverid: 'X' }] });
  assert.deepStrictEqual(d, [{ id: 'SID_A', name: 'Den', base: 'http://192.168.1.60:8885' }]);
  const s = tablo.parseServerInfo({ server_id: 'SID_A', name: 'Tablo', model: { name: 'Tablo QUAD', tuners: 4 } }, 'http://x:8885');
  assert.strictEqual(s.model, 'Tablo QUAD'); assert.strictEqual(s.tuners, 4);
  assert.throws(() => tablo.parseServerInfo({ error: { code: 'unauthorized', description: 'Authentication failure' } }, 'b'), /refuses other apps/);
});

test('Tablo channels sorted by number', () => {
  const c = tablo.parseChannels({
    '/guide/channels/2': { object_id: 2, channel: { call_sign: 'COZI', major: 4, minor: 2, network: null, resolution: 'sd' } },
    '/guide/channels/1': { object_id: 1, channel: { call_sign: 'WNBC', major: 4, minor: 1, network: 'NBC', resolution: 'hd_1080' } },
    '/guide/channels/3': { object_id: 3, channel: { call_sign: 'WCBS', major: 2, minor: 1, network: 'CBS', resolution: 'hd_1080' } },
  });
  assert.deepStrictEqual(c.map((x) => x.num), ['2.1', '4.1', '4.2']);
  assert.deepStrictEqual(c[1], { id: '1', num: '4.1', name: 'WNBC', network: 'NBC', hd: true });
});

test('Tablo watch answer and guide airings', () => {
  assert.strictEqual(tablo.parseWatch({ playlist_url: 'http://192.168.1.60/stream/pl.m3u8?a=1' }, 'http://192.168.1.60:8885'), 'http://192.168.1.60/stream/pl.m3u8?a=1');
  assert.strictEqual(tablo.parseWatch({ playlist_url: '/stream/pl.m3u8' }, 'http://192.168.1.60:8885'), 'http://192.168.1.60/stream/pl.m3u8');
  assert.throws(() => tablo.parseWatch({ error: { description: 'All tuners are in use' } }, 'http://x:8885'), /tuners/);
  assert.strictEqual(tablo.parseTime('2023-01-03T01:00Z'), Date.UTC(2023, 0, 3, 1, 0, 0));
  const g = { channels: {}, programmes: {} };
  assert.ok(tablo.addAiring({ airing_details: { datetime: '2023-01-03T01:00Z', duration: 1800, show_title: 'Jeopardy!', channel: { channel: { major: 4, minor: 1 } } },
    episode: { title: 'Finals', description: 'The big one.' } }, {}, g));
  assert.ok(tablo.addAiring({ airing_details: { datetime: '2023-01-03T02:00Z', duration: 7200, show_title: 'Heat', channel_path: '/guide/channels/9' },
    movie_airing: { description: 'Cops and robbers.' } }, { '/guide/channels/9': '7.1' }, g));
  assert.ok(!tablo.addAiring({ airing_details: { datetime: 'x', duration: 60 } }, {}, g));
  assert.deepStrictEqual(g.programmes['4.1'][0], [Date.UTC(2023, 0, 3, 1, 0, 0), Date.UTC(2023, 0, 3, 1, 30, 0), 'Jeopardy!', 'Finals — The big one.']);
  assert.strictEqual(g.programmes['7.1'][0][3], 'Cops and robbers.');
});
