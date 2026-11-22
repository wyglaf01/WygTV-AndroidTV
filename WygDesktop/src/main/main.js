'use strict';
const { app, BrowserWindow, ipcMain, shell, session, dialog, Menu } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const net = require('./net');
const m3u = require('./m3u');
const hdhr = require('./hdhr');
const stream = require('./stream');
const updater = require('./updater');
const { EpgStore } = require('./epg');
const smoke = require('./smoke');

// AppImages can't use Chromium's setuid sandbox helper, and Ubuntu 24.04 blocks the namespace sandbox
// for apps without an AppArmor profile, so an AppImage won't start with the sandbox on. The switch only
// works when given at launch (adding it here is too late for Chromium's helper processes), so the
// AppImage starts itself again with it and passes back its exit code.
if (process.platform === 'linux' && process.env.APPIMAGE && !process.argv.includes('--no-sandbox')) {
  const r = require('child_process').spawnSync(process.env.APPIMAGE, [...process.argv.slice(1), '--no-sandbox'], { stdio: 'inherit' });
  process.exit(r.status == null ? 1 : r.status);
}
const smokeTest = smoke.prepare(app);

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
// Hardware HEVC decoding where the graphics card supports it (Windows).
app.commandLine.appendSwitch('enable-features', 'PlatformHEVCDecoderSupport');

let win = null;
let epg = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1600, height: 900, minWidth: 960, minHeight: 600,
    backgroundColor: '#060C24', title: 'WygDesktop', show: false,
    icon: path.join(__dirname, '..', 'renderer', 'assets', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // IPTV servers rarely send CORS headers; the player fetches the video directly.
      webSecurity: false,
      backgroundThrottling: false,
    },
  });
  Menu.setApplicationMenu(null);
  win.once('ready-to-show', () => win.show());
  if (smokeTest) smoke.run(app, win, stream);
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) e.preventDefault(); });
  win.on('closed', () => { win = null; stream.stopAll(); });
}

app.whenReady().then(() => {
  epg = new EpgStore(path.join(app.getPath('userData'), 'guides'));
  // Some providers refuse Chromium's default user agent for video segments.
  session.defaultSession.webRequest.onBeforeSendHeaders({ urls: ['http://*/*', 'https://*/*'] }, (d, cb) => {
    const h = d.requestHeaders;
    if (!/^http:\/\/127\.0\.0\.1/.test(d.url)) h['User-Agent'] = net.getUserAgent();
    cb({ requestHeaders: h });
  });
  createWindow();
  app.on('activate', () => { if (!BrowserWindow.getAllWindows().length) createWindow(); });
});

app.on('window-all-closed', () => { stream.stopAll(); app.quit(); });

const handle = (name, fn) => ipcMain.handle(name, async (_e, ...a) => {
  try { return { ok: true, value: await fn(...a) }; } catch (e) { return { ok: false, error: String(e && e.message || e) }; }
});

handle('app:info', () => ({ version: app.getVersion(), platform: process.platform, userData: app.getPath('userData') }));
handle('net:setUserAgent', (ua) => net.setUserAgent(ua));
handle('net:text', (url, timeout) => net.text(url, { timeout: timeout || 60000 }));
handle('net:json', (url, timeout) => net.json(url, { timeout: timeout || 60000 }));
handle('m3u:load', async (url) => m3u.parse(/^https?:/i.test(url) ? await net.text(url, { timeout: 120000 }) : fs.readFileSync(url, 'utf8')));
handle('hdhr:discover', () => hdhr.discoverAll());
handle('hdhr:describe', (base) => hdhr.describe(base));
handle('hdhr:lineup', (base) => hdhr.lineup(base));
handle('epg:ensure', (urls, force) => epg.ensure(urls, force));
handle('epg:status', () => epg.status());
handle('epg:query', (items, from, to) => epg.query(items, from, to));
handle('stream:probe', (url, ua) => stream.probe(url, ua));
handle('stream:start', (opts) => stream.start(opts));
handle('stream:stop', () => stream.stopAll());
handle('stream:log', (id) => stream.lastLog(id));
handle('shell:open', (url) => { if (/^https?:/.test(url)) return shell.openExternal(url); });
handle('win:fullscreen', (on) => { if (win) win.setFullScreen(on === undefined ? !win.isFullScreen() : !!on); return win && win.isFullScreen(); });
handle('win:quit', () => app.quit());
handle('ext:play', (playerPath, url, title) => {
  const exe = playerPath || (process.platform === 'win32' ? findVlc() : 'vlc');
  if (!exe) throw new Error('No external player set. Pick VLC or mpv in Settings → Player.');
  const args = /mpv/i.test(exe) ? [url, '--force-media-title=' + (title || '')] : [url, '--meta-title=' + (title || '')];
  spawn(exe, args, { detached: true, stdio: 'ignore', windowsHide: false }).on('error', () => { }).unref();
  return exe;
});
handle('ext:pick', async () => {
  const r = await dialog.showOpenDialog(win, { title: 'Pick VLC or mpv', properties: ['openFile'],
    filters: process.platform === 'win32' ? [{ name: 'Programs', extensions: ['exe'] }] : [] });
  return r.canceled ? '' : r.filePaths[0];
});
handle('file:save', async (name, text) => {
  const r = await dialog.showSaveDialog(win, { defaultPath: name, filters: [{ name: 'WygDesktop backup', extensions: ['json'] }] });
  if (r.canceled || !r.filePath) return '';
  fs.writeFileSync(r.filePath, text);
  return r.filePath;
});
handle('file:open', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters: [{ name: 'Backups and playlists', extensions: ['json', 'm3u', 'm3u8', 'txt'] }] });
  if (r.canceled || !r.filePaths.length) return null;
  return { path: r.filePaths[0], text: fs.readFileSync(r.filePaths[0], 'utf8') };
});
handle('update:check', () => updater.check(app.getVersion()));
handle('update:install', (info) => updater.install(info, (p) => win && win.webContents.send('update:progress', p)));

function findVlc() {
  const dirs = [process.env.ProgramFiles, process.env['ProgramFiles(x86)']].filter(Boolean);
  for (const d of dirs) {
    for (const rel of ['VideoLAN\\VLC\\vlc.exe', 'mpv\\mpv.exe']) {
      const p = path.join(d, rel);
      if (fs.existsSync(p)) return p;
    }
  }
  return '';
}
