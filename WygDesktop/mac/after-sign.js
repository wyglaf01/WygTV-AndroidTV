'use strict';
/*
 * macOS: WygTV-Mac isn't signed with an Apple Developer certificate (mac.identity is null), but a Mac with
 * Apple Silicon refuses to start any program without at least an "ad-hoc" signature. electron-builder
 * renames Electron's executable and edits its Info.plist, which breaks the signature Electron shipped with,
 * so the whole app (and the bundled FFmpeg) is ad-hoc signed again here, before the .dmg is made.
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isSymbolicLink()) continue;
    if (e.isDirectory()) walk(p, out);
    else if (e.isFile() && (fs.statSync(p).mode & 0o111)) out.push(p);
  }
  return out;
}

exports.default = async function afterSign(ctx) {
  if (ctx.electronPlatformName !== 'darwin') return;
  const app = path.join(ctx.appOutDir, ctx.packager.appInfo.productFilename + '.app');
  // Loose programs (FFmpeg) first: --deep only reaches code inside bundles and frameworks.
  const unpacked = path.join(app, 'Contents', 'Resources', 'app.asar.unpacked');
  if (fs.existsSync(unpacked)) {
    for (const f of walk(unpacked, [])) execFileSync('codesign', ['--force', '--sign', '-', f], { stdio: 'inherit' });
  }
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', app], { stdio: 'inherit' });
  execFileSync('codesign', ['--verify', '--deep', '--strict', '--verbose=2', app], { stdio: 'inherit' });
};
