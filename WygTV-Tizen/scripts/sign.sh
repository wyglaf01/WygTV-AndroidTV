#!/usr/bin/env bash
# Signs www/ (from `npm run build`) into dist/WygTV.wgt with Tizen Studio's `tizen` command line.
#
# With your Samsung certificate (Tizen Studio → Certificate Manager; the files are in ~/SamsungCertificate/<profile>/):
#   TIZEN_AUTHOR_P12=author.p12 TIZEN_AUTHOR_PASSWORD=… \
#   TIZEN_DISTRIBUTOR_P12=distributor.p12 TIZEN_DISTRIBUTOR_PASSWORD=… scripts/sign.sh
# The .wgt then installs on the TVs whose DUIDs are in that certificate (Developer mode).
#
# Without them it signs with a throwaway test author certificate and Tizen's own distributor certificate:
# fine for checking the package, but Samsung TVs refuse it. It prints which one it used as the last line:
#   signed-with=samsung | signed-with=test
#
# TIZEN points at the `tizen` command (default ~/tizen-studio/tools/ide/bin/tizen).
set -euo pipefail
cd "$(dirname "$0")/.."
TIZEN="${TIZEN:-$HOME/tizen-studio/tools/ide/bin/tizen}"
DATA="${TIZEN_DATA:-$HOME/tizen-studio-data}"
[ -x "$TIZEN" ] || { echo "No tizen command at $TIZEN (install Tizen Studio, or set TIZEN)" >&2; exit 1; }
[ -f www/config.xml ] || { echo "Build www/ first: npm run build" >&2; exit 1; }

PROFILE=wygtv-sign
PROFILES="$DATA/profile/profiles.xml"
# The profile ends up holding plain passwords (below), so it never outlives this script.
trap '"$TIZEN" security-profiles remove -n "$PROFILE" >/dev/null 2>&1 || true' EXIT
# Where `tizen package` looks for signing profiles (Tizen Studio's GUI sets this; the command line doesn't).
"$TIZEN" cli-config "profiles.path=$PROFILES" >/dev/null
"$TIZEN" security-profiles remove -n "$PROFILE" >/dev/null 2>&1 || true
if [ -n "${TIZEN_AUTHOR_P12:-}" ] && [ -n "${TIZEN_DISTRIBUTOR_P12:-}" ]; then
  KIND=samsung
  AUTHOR_PW="${TIZEN_AUTHOR_PASSWORD:-}"
  DIST_PW="${TIZEN_DISTRIBUTOR_PASSWORD:-}"
  "$TIZEN" security-profiles add -n "$PROFILE" \
    -a "$TIZEN_AUTHOR_P12" -p "${TIZEN_AUTHOR_PASSWORD:-}" \
    -d "$TIZEN_DISTRIBUTOR_P12" -dp "${TIZEN_DISTRIBUTOR_PASSWORD:-}"
else
  KIND=test
  PW="wygtv-test-$RANDOM$RANDOM"
  AUTHOR_PW="$PW"
  DIST_PW=tizenpkcs12passfordsigner # Tizen's own test distributor certificate (published with Tizen Studio)
  rm -f "$DATA/keystore/author/wygtv-test.p12"
  "$TIZEN" certificate -a wygtv-test -p "$PW" -c US -n "WygTV test" -f wygtv-test
  "$TIZEN" security-profiles add -n "$PROFILE" -a "$DATA/keystore/author/wygtv-test.p12" -p "$PW"
fi

# Tizen's command line keeps the passwords in files encrypted with the desktop keyring, which a server (or a
# GitHub runner) doesn't have, so `tizen package` then fails with "Invaild password". Put them in the profile
# instead: distributor="0" is the author certificate, distributor="1" the distributor certificate.
AUTHOR_PW="$AUTHOR_PW" DIST_PW="$DIST_PW" python3 - "$PROFILES" "$PROFILE" <<'PY'
import os, sys, xml.etree.ElementTree as ET
path, name = sys.argv[1], sys.argv[2]
tree = ET.parse(path)
prof = next(p for p in tree.getroot().iter('profile') if p.get('name') == name)
pw = {'0': os.environ['AUTHOR_PW'], '1': os.environ['DIST_PW']}
for item in prof.iter('profileitem'):
    if item.get('distributor') in pw and item.get('key'):
        item.set('password', pw[item.get('distributor')])
tree.write(path, encoding='UTF-8', xml_declaration=True)
PY

# Package from a clean copy, so nothing but the app goes in.
rm -rf dist && mkdir -p dist/app
cp -r www/. dist/app/
if ! "$TIZEN" package -t wgt -s "$PROFILE" -- "$PWD/dist/app"; then
  echo "--- end of $DATA/cli/logs/cli.log ---" >&2
  tail -40 "$DATA/cli/logs/cli.log" >&2 || true
  exit 1
fi
WGT=$(ls dist/app/*.wgt 2>/dev/null | head -1)
[ -n "$WGT" ] || { echo "tizen package made no .wgt" >&2; exit 1; }
mv "$WGT" dist/WygTV.wgt
rm -rf dist/app

# A signed widget carries both signatures, and the manifest and app pages.
LIST=$(unzip -Z1 dist/WygTV.wgt)
for f in author-signature.xml signature1.xml config.xml index.html js/tizen-bridge.js; do
  grep -qx "$f" <<<"$LIST" || { echo "dist/WygTV.wgt has no $f" >&2; exit 1; }
done
# …and never a certificate or a password.
if grep -qiE '\.(p12|pfx|pwd|jks)$|profiles\.xml$' <<<"$LIST"; then echo "dist/WygTV.wgt contains certificate files" >&2; exit 1; fi
VERSION=$(grep -o 'version="1\.0\.[0-9]*"' www/config.xml | head -1 | cut -d'"' -f2)
echo "{\"version\": \"$VERSION\", \"signedWith\": \"$KIND\"}" > dist/tizen-version.json
echo "dist/WygTV.wgt: WygTV $VERSION, $(du -h dist/WygTV.wgt | cut -f1), signed with the $KIND certificate"
echo "signed-with=$KIND"
