// Checks the built TV app in www/ (run `node scripts/build.mjs` first; `npm test` does both).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as acorn from 'acorn';
import { legacyCss, pxFallback, splitSelectors } from '../scripts/legacy-css.mjs';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const www = path.join(root, 'www');
const read = (p) => fs.readFileSync(path.join(www, p), 'utf8');
const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]));

// ------------------------------------------------------------------ CSS fallbacks
test('flex gap becomes margins under html.no-flex-gap, skipping auto-margin children', () => {
  const { css } = legacyCss('.bar { display: flex; gap: 12px; }\n.end { margin-left: auto; }\n');
  assert.match(css, /html\.no-flex-gap \.bar \{ gap: 0; \}/);
  assert.match(css, /html\.no-flex-gap \.bar > :not\(\[hidden\]\) ~ :not\(\[hidden\]\):not\(\.end\) \{ margin-left: 12px; \}/);
});

test('column, wrapping and two-value gaps', () => {
  const col = legacyCss('.c { display: flex; flex-direction: column; gap: 4px; }').css;
  assert.match(col, /margin-top: 4px;/);
  const wrap = legacyCss('.w { display: flex; flex-wrap: wrap; gap: 6px 10px; }').css;
  assert.match(wrap, /html\.no-flex-gap \.w > \* \{ margin-right: 10px; margin-bottom: 6px; \}/);
  const grid = legacyCss('.g { display: grid; gap: 18px; }').css;
  assert.doesNotMatch(grid, /no-flex-gap/, 'grid gap works on Chromium 69');
});

test('a variant that turns a column into a row gets its gap sideways', () => {
  const { css } = legacyCss('.tile { display: flex; flex-direction: column; gap: 10px; }\n.tile.small { flex-direction: row; }');
  assert.match(css, /html\.no-flex-gap \.tile\.small > [^{]+\{ margin-left: 10px; margin-top: 0; \}/);
});

test(':has() selectors get a rule of their own', () => {
  const { css } = legacyCss('.a ~ .b, body:has(.c) .b { color: red; }');
  assert.match(css, /^\.a ~ \.b \{ color: red; \}\nbody:has\(\.c\) \.b \{ color: red; \}/);
});

test('min()/max()/clamp() get a px fallback first', () => {
  assert.equal(pxFallback(' min(720px, 90vw)'), ' 720px');
  assert.equal(pxFallback(' min(30vw, 420px)'), ' 420px');
  assert.equal(pxFallback(' repeat(auto-fill, minmax(160px, 1fr))'), null);
  const { css } = legacyCss('.d { max-width: min(720px, 90vw); }');
  assert.match(css, /max-width: 720px;\s*max-width: min\(720px, 90vw\)/);
});

test('aspect-ratio rules are collected for tizen-legacy.js', () => {
  const { aspect } = legacyCss('.p { aspect-ratio: 16 / 9; }\n.q, .r { aspect-ratio: 1.35; }\n.q.s { aspect-ratio: auto; }');
  assert.deepEqual(aspect, [['.p', 1.7778], ['.q', 1.35], ['.r', 1.35], ['.q.s', 0]]);
});

test('selector lists split on top-level commas only', () => {
  assert.deepEqual(splitSelectors('.a, .b:not(.c, .d), [x=","]'), ['.a', '.b:not(.c, .d)', '[x=","]']);
});

// ------------------------------------------------------------------ the built app
const jsFiles = walk(www).filter((f) => f.endsWith('.js'));

test('every script parses as JavaScript Chromium 69 understands (ES2018 + optional catch binding)', () => {
  assert.ok(jsFiles.length >= 12);
  for (const f of jsFiles) {
    try { acorn.parse(fs.readFileSync(f, 'utf8'), { ecmaVersion: 2019, sourceType: 'script' }); } catch (e) { assert.fail(`${path.relative(www, f)}: ${e.message}`); }
  }
});

test('WygTV code uses no browser features newer than Chrome 69', () => {
  // Library code (vendor/) checks for what it uses; ours must not need these.
  const newer = {
    'Object.fromEntries': /Object\.fromEntries/, globalThis: /\bglobalThis\b/, 'String.matchAll': /\.matchAll\(/, 'Promise.allSettled': /Promise\.allSettled/,
    'Promise.any': /Promise\.any\(/, queueMicrotask: /\bqueueMicrotask\(/, replaceAll: /\.replaceAll\(/, 'Array.at': /\.at\(-?\d/, structuredClone: /structuredClone\(/,
    TextDecoderStream: /TextDecoderStream/, DecompressionStream: /DecompressionStream/, 'Blob.stream': /\.stream\(\)/,
  };
  for (const f of jsFiles.filter((p) => !p.includes(`${path.sep}vendor${path.sep}`))) {
    const src = fs.readFileSync(f, 'utf8');
    for (const [name, re] of Object.entries(newer)) assert.doesNotMatch(src, re, `${path.relative(www, f)} uses ${name}`);
  }
});

test('the built CSS keeps nothing Chromium 69 would throw away whole', () => {
  const css = read('styles.css');
  for (const m of css.matchAll(/([^{}]+)\{/g)) {
    const sels = splitSelectors(m[1].trim());
    if (sels.some((s) => s.includes(':has('))) assert.ok(sels.every((s) => s.includes(':has(')), 'mixed :has() list: ' + m[1].trim());
  }
  assert.doesNotMatch(css, /\binset\s*:/, 'inset is lowered to top/right/bottom/left');
  // Every min()/max()/clamp() value has a plain value for the same property just before it.
  const lines = css.split('\n');
  lines.forEach((l, i) => {
    const m = l.match(/^\s*([a-z-]+):.*(^|[^a-z-])(min|max|clamp)\(/);
    if (m) assert.match(lines[i - 1], new RegExp('^\\s*' + m[1] + ':'), `no fallback before line ${i + 1}: ${l.trim()}`);
  });
});

test('index.html loads the TV files, and every script it names exists', () => {
  const html = read('index.html');
  for (const src of [...html.matchAll(/<script src="([^"]+)"/g)].map((m) => m[1])) {
    if (src.startsWith('$WEBAPIS')) continue; // provided by the TV
    assert.ok(fs.existsSync(path.join(www, src)), 'missing ' + src);
  }
  const order = ['js/tizen-legacy.js', 'js/desktop-modules.js', 'js/tizen-avplay.js', 'js/tizen-bridge.js', 'js/core.js', 'js/app.js'].map((s) => html.indexOf(s));
  assert.deepEqual([...order].sort((a, b) => a - b), order, 'scripts in the order the bridge needs');
  assert.ok(order.every((i) => i > 0));
  assert.match(html, /<link rel="stylesheet" href="tizen.css">/);
  assert.match(html, /width=1920/);
});

test('config.xml is a valid Samsung TV app manifest', () => {
  const xml = read('config.xml');
  const pkg = xml.match(/package="([^"]+)"/)[1];
  assert.match(pkg, /^[A-Za-z0-9]{10}$/, 'Tizen package ids are 10 letters or digits');
  assert.match(xml, new RegExp(`<tizen:application id="${pkg}\\.[A-Za-z0-9]+"`));
  assert.match(xml, /required_version="5\.5"/);
  assert.match(xml, /<tizen:profile name="tv-samsung"\/>/);
  assert.match(xml, /version="1\.0\.\d+"/);
  assert.doesNotMatch(xml, /@VERSION@/);
  for (const p of ['internet', 'tv.inputdevice']) assert.match(xml, new RegExp(`privilege/${p.replace('.', '\\.')}"`));
  assert.match(xml, /<access origin="\*" subdomains="true"\/>/);
  assert.ok(fs.existsSync(path.join(www, 'icon.png')));
});

test("the demo playlist for Samsung's testers parses: 5 numbered channels and 2 movies, all https", async () => {
  const { createRequire } = await import('node:module');
  const m3u = createRequire(import.meta.url)('../../WygDesktop/src/main/m3u.js');
  const r = m3u.parse(fs.readFileSync(path.join(root, '..', 'docs', 'demo.m3u'), 'utf8'));
  const live = r.items.filter((i) => i.kind === 'live'), movies = r.items.filter((i) => i.kind === 'movie');
  assert.deepEqual(live.map((i) => i.num), ['1', '2', '3', '4', '5']);
  assert.equal(movies.length, 2);
  assert.ok(movies.every((i) => i.logo), 'movies have posters');
  assert.ok(r.items.every((i) => /^https:\/\//.test(i.url)), 'all https');
});
