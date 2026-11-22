'use strict';
/*
 * Linux: put a small launcher script in front of the Electron binary. When started from the AppImage it
 * adds --no-sandbox, which has to be given at launch: AppImages can't use the setuid sandbox helper and
 * Ubuntu 24.04 blocks the namespace sandbox for them, so Chromium would abort before any app code runs.
 * The .deb keeps the sandbox (its post-install sets up the helper and an AppArmor profile for -bin).
 */
const fs = require('fs');
const path = require('path');

exports.default = async function afterPack(ctx) {
  if (ctx.electronPlatformName !== 'linux') return;
  const exe = path.join(ctx.appOutDir, ctx.packager.executableName);
  fs.renameSync(exe, exe + '-bin');
  const name = path.basename(exe) + '-bin';
  fs.writeFileSync(exe, `#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
if [ -n "$APPIMAGE" ]; then
  exec "$HERE/${name}" --no-sandbox "$@"
fi
exec "$HERE/${name}" "$@"
`, { mode: 0o755 });
};
