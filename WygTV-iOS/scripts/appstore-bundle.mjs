import fs from 'node:fs';
import path from 'node:path';

function replaceRequired(text, before, after, name) {
  if (!text.includes(before)) throw new Error('App Store transformation needs review: ' + name);
  return text.replace(before, after);
}

// Transform copied web assets only; sideload and desktop source behavior stays intact.
export function prepareAppStore(www, { smoke = false } = {}) {
  const edit = (file, transform) => {
    const p = path.join(www, file);
    fs.writeFileSync(p, transform(fs.readFileSync(p, 'utf8')));
  };
  edit('js/ios-bridge.js', source => {
    const start = source.indexOf('  // ------------------------------------------------------------------ updates: AltStore');
    const end = source.indexOf('  const ok = ', start);
    if (start < 0 || end < 0) throw new Error('Missing sideload updater boundary');
    source = source.slice(0, start) + source.slice(end);
    source = replaceRequired(source, "brand: 'WygTV-iOS', platform: 'ios', noFFmpeg: true,", "brand: 'WygTV', platform: 'ios', noFFmpeg: true, distribution: 'appstore',", 'distribution');
    const update = source.indexOf('    update: {');
    const apiEnd = source.indexOf('\n  };\n  window.wyg = api;', update);
    if (update < 0 || apiEnd < 0) throw new Error('Missing updater API boundary');
    source = source.slice(0, update) + `    update: {
      check: async () => ({ available: false, current: (await api.app.info()).version, kind: 'appstore' }),
      install: async () => { throw new Error('App updates are managed by the App Store.'); },
    },` + source.slice(apiEnd);
    if (smoke) {
      source = source.replace("name: 'Smoke test'", "name: 'Demo playlist'");
      source = replaceRequired(source,
        "const step = async (name, ms = 2500) => { await sleep(ms); console.log('WYG-STEP ' + name); await sleep(1500); };",
        "const step = async (name, ms = 1200) => { await sleep(ms); console.log('WYG-STEP ' + name); await Http.get({ url: base + '/__capture?name=' + encodeURIComponent(name), responseType: 'text', connectTimeout: 5000, readTimeout: 90000 }); };",
        'screenshot handshake');
    }
    if (!smoke) {
      const test = source.indexOf('  // ------------------------------------------------------------------ self-test used by CI');
      if (test < 0) throw new Error('Missing simulator test boundary');
      source = source.slice(0, test) + '})();\n';
    }
    return source;
  });
  edit('js/core.js', source => replaceRequired(source,
    "W.DEFAULT_OMDB_KEY = '5b5c4d64'; // Eddie's OMDb key, same default as the Android app.",
    "W.DEFAULT_OMDB_KEY = ''; // Optional metadata requests use a key entered by the user.", 'metadata key'));
  edit('js/meta.js', source => replaceRequired(source,
    '  M.omdb = async (title, year, type, imdbId) => {',
    '  M.omdb = async (title, year, type, imdbId) => {\n    if (!M.omdbKey()) return null;', 'optional metadata'));
  edit('js/screens-settings.js', source => {
    source = replaceRequired(source, "label: 'ℹ About & updates'", "label: 'ℹ About WygTV'", 'about title');
    source = replaceRequired(source,
      "W.isTV ? null : btn({ class: 'button primary', onclick: () => W.checkUpdates(true) }, '⟳ Check for updates'),",
      "btn({ class: 'button', onclick: () => W.api.shell.open('https://wyglaf01.github.io/WygTV-AndroidTV/privacy.html') }, 'Privacy Policy'),\n            btn({ class: 'button', onclick: () => W.api.shell.open('https://github.com/wyglaf01/WygTV-AndroidTV/issues') }, 'Support'),\n            h('div', { class: 'set-sub' }, 'Updates are managed by the App Store.'),", 'update controls');
    const start = source.indexOf('  W.checkUpdates = async (manual) => {');
    const end = source.indexOf('  W.screens.settings = ', start);
    if (start < 0 || end < 0) throw new Error('Missing update UI boundary');
    source = source.slice(0, start) + '  W.checkUpdates = async () => {};\n\n' + source.slice(end);
    source = source.replace('Your own OMDb key (empty = the built-in key)', 'Your own OMDb key (optional)')
      .replace("W.settings.omdbKey ? 'Your key' : 'Built-in key'", "W.settings.omdbKey ? 'Your key' : 'Not set (optional)'");
    source = source.replace('Your IPTV services and antenna tuners. Hold Enter (or right-click) on one for more.', 'Your IPTV services and antenna tuners. Touch and hold a playlist for more options.');
    source = source.replace('Player keys: Up/Down or Page Up/Down change channel, numbers jump to a channel, Left/Right skip in movies, Space or P pauses, I shows info, F toggles full screen, Esc goes back.', 'Tap a channel to play. Use the on-screen player controls to pause, seek, change channels or return. Touch and hold an item for more options.');
    return source;
  });
}
