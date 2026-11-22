'use strict';
const { contextBridge, ipcRenderer } = require('electron');

async function call(name, ...args) {
  const r = await ipcRenderer.invoke(name, ...args);
  if (!r.ok) throw new Error(r.error);
  return r.value;
}

const api = {};
for (const name of ['app:info', 'net:setUserAgent', 'net:text', 'net:json', 'm3u:load', 'hdhr:discover', 'hdhr:describe',
  'hdhr:lineup', 'epg:ensure', 'epg:status', 'epg:query', 'stream:probe', 'stream:start', 'stream:stop', 'stream:log',
  'shell:open', 'win:fullscreen', 'win:quit', 'ext:play', 'ext:pick', 'file:save', 'file:open', 'update:check', 'update:install']) {
  const [a, b] = name.split(':');
  (api[a] ||= {})[b] = (...args) => call(name, ...args);
}
api.onUpdateProgress = (fn) => ipcRenderer.on('update:progress', (_e, p) => fn(p));

contextBridge.exposeInMainWorld('wyg', api);
