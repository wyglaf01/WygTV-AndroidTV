'use strict';
/**
 * "Check for updates": every build of the app branch is published to the GitHub release
 * wygdesktop-latest together with desktop-version.json. A newer build is downloaded and installed.
 */
const { app, shell } = require('electron');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');
const net = require('./net');

const BASE = 'https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygdesktop-latest/';
const FILES = { win32: 'WygDesktop-Setup.exe', appimage: 'WygDesktop-linux.AppImage', deb: 'WygDesktop-linux.deb' };

function newer(a, b) {
  const pa = String(a).split('.').map(Number), pb = String(b).split('.').map(Number);
  for (let i = 0; i < 3; i++) { if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) > (pb[i] || 0); }
  return false;
}

async function check(current) {
  const info = await net.json(BASE + 'desktop-version.json?t=' + Date.now(), { timeout: 15000 });
  const kind = process.platform === 'win32' ? 'win32' : process.env.APPIMAGE ? 'appimage' : 'deb';
  return { current, latest: info.version, available: newer(info.version, current), kind, url: BASE + FILES[kind], notes: info.notes || '' };
}

async function download(url, file, progress) {
  const res = await net.get(url, { timeout: 600000 });
  const total = +res.headers.get('content-length') || 0;
  const out = fs.createWriteStream(file);
  let got = 0;
  for await (const chunk of res.body) {
    got += chunk.length;
    out.write(chunk);
    if (total) progress(Math.round(got * 100 / total));
  }
  await new Promise((r) => out.end(r));
}

async function install(info, progress) {
  if (info.kind === 'deb') { await shell.openExternal(info.url); return 'opened'; }
  if (info.kind === 'win32') {
    const file = path.join(os.tmpdir(), 'WygDesktop-Setup.exe');
    await download(info.url, file, progress);
    spawn(file, ['--updated'], { detached: true, stdio: 'ignore' }).unref();
    setTimeout(() => app.quit(), 800);
    return 'installing';
  }
  const target = process.env.APPIMAGE;
  const tmp = target + '.new';
  await download(info.url, tmp, progress);
  fs.chmodSync(tmp, 0o755);
  fs.renameSync(tmp, target);
  app.relaunch({ execPath: target });
  app.quit();
  return 'restarting';
}

module.exports = { check, install, newer };
