import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { prepareAppStore } from '../scripts/appstore-bundle.mjs';
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wygtv-store-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.mkdirSync(path.join(root, 'js'));
  fs.copyFileSync('web/ios-bridge.js', path.join(root, 'js/ios-bridge.js'));
  for (const name of ['core', 'meta', 'screens-settings']) fs.copyFileSync('../WygDesktop/src/renderer/js/' + name + '.js', path.join(root, 'js/' + name + '.js'));
  return root;
}
test('App Store updater performs no network request or external installation', async t => {
  const root = fixture(t); prepareAppStore(root);
  const source = fs.readFileSync(path.join(root, 'js/ios-bridge.js'), 'utf8');
  let requests = 0, launches = 0, listeners = 0;
  const window = { capacitorExports: { CapacitorHttp: { get: () => { requests++; throw Error('Unexpected HTTP call'); } }, registerPlugin: () => ({ getInfo: async () => ({ version: '1.2.0' }), openUrl: async () => { launches++; } }) }, __wygRequire: () => ({}), addEventListener: () => listeners++ };
  vm.runInNewContext(source, { window, navigator: { userAgent: 'iPhone', maxTouchPoints: 1 }, screen: { width: 1024, height: 768 }, document: { querySelector: () => null, documentElement: { classList: { add() {} } } } });
  assert.equal(window.wyg.distribution, 'appstore');
  assert.equal((await window.wyg.update.check()).available, false);
  await assert.rejects(window.wyg.update.install({ url: 'https://example.com/app.ipa' }), /App Store/);
  assert.equal(requests, 0); assert.equal(launches, 0); assert.equal(listeners, 0);
  assert.doesNotMatch(source, /altstore:\/\/|wygtv-ios-latest|WYG-SMOKE/);
  const settings = fs.readFileSync(path.join(root, 'js/screens-settings.js'), 'utf8');
  assert.match(settings, /Privacy Policy/); assert.match(settings, /Support/);
  assert.doesNotMatch(settings, /Open AltStore|Check for updates/); new vm.Script(settings);
});
test('App Store metadata sends nothing without a user key', async t => {
  const root = fixture(t); prepareAppStore(root);
  const W = { settings: { omdbKey: '' }, DEFAULT_OMDB_KEY: '', load: () => ({}), save() {}, api: { net: { json: () => { throw Error('Unexpected metadata request'); } } } };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'js/meta.js'), 'utf8'), { W });
  assert.equal(await W.meta.omdb('Test Movie', '2026', 'movie', ''), null);
});
test('simulator smoke code is opt-in; sideload source stays intact', t => {
  const original = fs.readFileSync('web/ios-bridge.js', 'utf8');
  const root = fixture(t); prepareAppStore(root, { smoke: true });
  assert.match(fs.readFileSync(path.join(root, 'js/ios-bridge.js'), 'utf8'), /WYG-SMOKE/);
  assert.equal(fs.readFileSync('web/ios-bridge.js', 'utf8'), original);
});

test('simulator runner reports all screens and sends its result as native JSON', async t => {
  const root = fixture(t); prepareAppStore(root, { smoke: true });
  const source = fs.readFileSync(path.join(root, 'js/ios-bridge.js'), 'utf8');
  let load, channel = '', result;
  const captures = [];
  const Http = {
    get: async options => { captures.push(new URL(options.url).searchParams.get('name')); return { status: 200, data: 'OK' }; },
    post: async options => {
      assert.equal(options.headers?.['Content-Type'], 'application/json', 'native iOS HTTP needs a JSON content type to serialize this object');
      result = options.data; return { status: 200 };
    },
  };
  const window = { capacitorExports: { CapacitorHttp: Http, registerPlugin: () => ({ get: async () => ({ value: 'http://provider.example' }) }) }, __wygRequire: () => ({}), addEventListener: (name, callback) => { if (name === 'load') load = callback; } };
  const W = {
    settings: {}, saveSettings() {}, home() {}, go() {},
    data: { loadAll: async () => {}, allChannels: () => [{}], lists: new Map([['smoke', { error: '' }]]), channel: key => ({ key }), loadAllVod: async () => {}, vodItems: () => [{}] },
    player: { playChannel: item => { channel = item.key; }, stop() {} },
    guide: { ensureAll: async () => {}, get: async () => ({ demo: [[1, 2, 'Test']] }) },
  };
  const document = { documentElement: { classList: { add() {} } }, querySelector: selector => {
    if (selector === '#player video') return { currentTime: 4, videoWidth: 1280, currentSrc: 'http://provider.example/test.m3u8' };
    if (selector === '.player-msg:not([hidden])' && channel.endsWith('|104')) return { textContent: 'Open in VLC' };
    return null;
  } };
  vm.runInNewContext(source, { window, W, document, navigator: { userAgent: 'iPhone' }, screen: { width: 1024, height: 768 }, URL, console: { log() {} }, setTimeout: callback => queueMicrotask(callback) });
  await load();
  assert.deepEqual(captures, ['home', 'player', 'mpeg2-message', 'live', 'guide', 'movies', 'settings']);
  assert.equal(result.ok, true);
});
