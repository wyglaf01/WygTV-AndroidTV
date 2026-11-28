import fs from 'node:fs';
const output = process.argv[2];
if (!output) throw new Error('Provide the temporary mock-provider path');
const source = fs.readFileSync('../WygDesktop/test/mock-server.js', 'utf8')
  .replace('EN - The Matrix (1999)', 'WygTV Demo Movie (2026)')
  .replace('Breaking Bad (2008)', 'WygTV Demo Series (2026)')
  .replace('http.createServer((req, res) => {', 'const captureWaiters = new Map(); let captureResult = null;\nhttp.createServer((req, res) => {')
  .replace("  if (p === '/player_api.php') {", `  if (p === '/__capture/pending') return send(res, 200, [...captureWaiters.keys()].join('\\n'), 'text/plain');
  if (p === '/__capture/result') {
    if (req.method === 'POST') {
      let body = ''; req.on('data', data => { body += data; });
      req.on('end', () => { try { captureResult = JSON.parse(body); send(res, 200, { ok: true }); } catch { send(res, 400, { error: 'Bad result' }); } });
      return;
    }
    return send(res, 200, captureResult ? JSON.stringify(captureResult) : '', 'text/plain');
  }
  if (p === '/__capture') {
    const name = q.get('name');
    if (!/^[a-z0-9-]+$/.test(name || '')) return send(res, 400, { error: 'Invalid capture name' });
    captureWaiters.set(name, res);
    res.on('close', () => { if (captureWaiters.get(name) === res) captureWaiters.delete(name); });
    return;
  }
  if (p === '/__capture/ack') {
    const name = q.get('name');
    const waiter = captureWaiters.get(name);
    if (!waiter) return send(res, 409, { error: 'No screen waiting' });
    captureWaiters.delete(name); send(waiter, 200, { captured: name });
    return send(res, 200, { ok: true });
  }
  if (p === '/player_api.php') {`);
if (!source.includes('const captureWaiters')) throw new Error('Fixture handshake not installed');
fs.writeFileSync(output, source);
