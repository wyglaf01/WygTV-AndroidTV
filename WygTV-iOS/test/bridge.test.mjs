// Checks that the page build puts everything in place (the iPad Simulator test in CI tries the app itself).
import { test } from 'node:test';
import assert from 'node:assert';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';

test('www/ gets the desktop screens, the reused modules and the iOS bridge', () => {
  execFileSync(process.execPath, ['scripts/build-web.mjs']);
  const html = fs.readFileSync('www/index.html', 'utf8');
  const order = ['vendor/capacitor.js', 'js/desktop-modules.js', 'js/ios-bridge.js', 'js/core.js', 'js/app.js'].map((s) => html.indexOf(s));
  assert.ok(order.every((i) => i > 0), 'all scripts present');
  assert.deepStrictEqual([...order].sort((a, b) => a - b), order, 'bridge loads before the screens');
  globalThis.window = { __wygNet: {} };
  new Function(fs.readFileSync('www/js/desktop-modules.js', 'utf8'))();
  const r = window.__wygRequire;
  const pl = r('./m3u').parse('#EXTM3U\n#EXTINF:-1 tvg-id="a" group-title="News",A\nhttp://x/a.m3u8\n');
  assert.strictEqual(pl.items[0].group, 'News');
  assert.strictEqual(typeof r('./xmltv').Parser, 'function');
  assert.strictEqual(typeof r('./hdhr').describe, 'function');
});
