#!/bin/bash
# WygDesktop .deb post-install (replaces electron-builder's default).

if type update-alternatives >/dev/null 2>&1; then
    if [ -L /usr/bin/wygdesktop -a -e /usr/bin/wygdesktop -a "$(readlink /usr/bin/wygdesktop)" != /etc/alternatives/wygdesktop ]; then
        rm -f /usr/bin/wygdesktop
    fi
    update-alternatives --install /usr/bin/wygdesktop wygdesktop /opt/WygDesktop/wygdesktop 100 || ln -sf /opt/WygDesktop/wygdesktop /usr/bin/wygdesktop
else
    ln -sf /opt/WygDesktop/wygdesktop /usr/bin/wygdesktop
fi

# Chromium's sandbox. Ubuntu 24.04 and later stop apps from using user namespaces unless an AppArmor
# profile allows it (electron-builder's own check passes as root, so the app then failed to start).
# Give WygDesktop a profile like Chrome's where AppArmor supports it, and keep the setuid helper as the
# fallback everywhere else.
chmod 4755 /opt/WygDesktop/chrome-sandbox || true
if [ -e /etc/apparmor.d/abi/4.0 ]; then
    cat > /etc/apparmor.d/wygdesktop <<'PROFILE'
abi <abi/4.0>,
include <tunables/global>

profile wygdesktop /opt/WygDesktop/wygdesktop flags=(unconfined) {
  userns,

  include if exists <local/wygdesktop>
}
PROFILE
    if hash apparmor_parser 2>/dev/null; then
        apparmor_parser -r /etc/apparmor.d/wygdesktop 2>/dev/null || true
    fi
fi

if hash update-mime-database 2>/dev/null; then
    update-mime-database /usr/share/mime || true
fi
if hash update-desktop-database 2>/dev/null; then
    update-desktop-database /usr/share/applications || true
fi
