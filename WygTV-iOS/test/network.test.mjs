import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
function bridge(get) {
  const window = { capacitorExports: { CapacitorHttp: { get }, registerPlugin: () => ({}) }, __wygRequire: () => ({}), addEventListener() {} };
  vm.runInNewContext(fs.readFileSync('web/ios-bridge.js', 'utf8'), { window, screen: { width: 1024, height: 768 }, document: { querySelector: () => null, documentElement: { classList: { add() {} } }, addEventListener() {} }, URL, TextEncoder, TextDecoderStream, Blob, Uint8Array, fetch });
  return window.wyg.net;
}
test('HTTP requests preserve scheme and explicit port', async () => {
  const calls = [];
  const api = bridge(async options => { calls.push(options); return { status: 200, data: Buffer.from('{"user_info":{"auth":1}}').toString('base64') }; });
  const url = 'http://provider.example:8080/player_api.php?username=demo&password=secret';
  assert.equal((await api.json(url)).user_info.auth, 1);
  assert.equal(calls.length, 1); assert.equal(calls[0].url, url);
});
test('ATS failures explain the update without exposing credentials', async () => {
  const api = bridge(async () => { throw { code: -1022, message: 'App Transport Security policy requires a secure connection' }; });
  await assert.rejects(api.json('http://provider.example:8080/player_api.php?password=secret'), error => {
    assert.match(error.message, /Install the latest WygTV-iOS update/);
    assert.match(error.message, /http:\/\/provider.example:8080/);
    assert.doesNotMatch(error.message, /secret|player_api/); return true;
  });
});
test('TLS failures do not retry with plaintext HTTP', async () => {
  let calls = 0;
  const api = bridge(async () => { calls++; throw { code: -1200, message: 'A TLS error caused the secure connection to fail.' }; });
  await assert.rejects(api.json('https://demo:secret@provider.example:8443/player_api.php?password=secret'), error => {
    assert.match(error.message, /https:\/\/provider.example:8443/);
    assert.match(error.message, /TLS\/certificate/);
    assert.doesNotMatch(error.message, /demo|secret|player_api/); return true;
  }); assert.equal(calls, 1);
});
test('certificate errors retain an actionable message', async () => {
  const api = bridge(async () => { throw { code: '-1202' }; });
  await assert.rejects(api.text('https://provider.example/list.m3u'), /TLS\/certificate/);
});
test('generic errors omit credential-bearing server text', async () => {
  const api = bridge(async () => { throw new Error('https://provider.example/?password=secret'); });
  await assert.rejects(api.text('https://provider.example/list'), error => {
    assert.match(error.message, /Check the server address/); assert.doesNotMatch(error.message, /secret/); return true;
  });
});
