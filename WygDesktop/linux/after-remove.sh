#!/bin/bash
# WygDesktop .deb removal (replaces electron-builder's default).
if type update-alternatives >/dev/null 2>&1; then
    update-alternatives --remove wygdesktop /opt/WygDesktop/wygdesktop
else
    rm -f /usr/bin/wygdesktop
fi
if [ "$1" = remove ] || [ "$1" = purge ]; then
    if [ -e /etc/apparmor.d/wygdesktop ]; then
        if hash apparmor_parser 2>/dev/null; then apparmor_parser -R /etc/apparmor.d/wygdesktop 2>/dev/null || true; fi
        rm -f /etc/apparmor.d/wygdesktop
    fi
fi
