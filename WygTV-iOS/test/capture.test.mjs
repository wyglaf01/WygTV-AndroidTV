import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import http from 'node:http';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
const require = createRequire(import.meta.url);

test('capture fixture holds the screen until the host acknowledges its screenshot', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wygtv-capture-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'mock.cjs');
  execFileSync(process.execPath, ['scripts/make-store-fixture.mjs', file]);
  let server;
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    require: name => name === 'http' ? { createServer: handler => (server = http.createServer(handler)) } : require(name),
    process: { argv: ['node', file, '0', dir], env: { MOCK_HOST: '127.0.0.1' } },
    __dirname: dir, console: { log() {} }, Buffer, URL, setTimeout,
  });
  t.after(() => server.close());
  await once(server, 'listening');
  const base = 'http://127.0.0.1:' + server.address().port;
  let completed = false;
  const screen = fetch(base + '/__capture?name=home').then(r => r.json()).then(r => { completed = true; return r; });
  await new Promise(resolve => setTimeout(resolve, 40));
  assert.equal(completed, false, 'app must not advance before capture');
  assert.equal((await fetch(base + '/__capture/ack?name=home')).status, 200);
  assert.equal((await screen).captured, 'home');
  assert.equal((await fetch(base + '/__capture/ack?name=unknown')).status, 409);
});
