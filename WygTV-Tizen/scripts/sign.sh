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
"$TIZEN" security-profiles remove -n "$PROFILE" >/dev/null 2>&1 || true
if [ -n "${TIZEN_AUTHOR_P12:-}" ] && [ -n "${TIZEN_DISTRIBUTOR_P12:-}" ]; then
  KIND=samsung
  "$TIZEN" security-profiles add -n "$PROFILE" \
    -a "$TIZEN_AUTHOR_P12" -p "${TIZEN_AUTHOR_PASSWORD:-}" \
    -d "$TIZEN_DISTRIBUTOR_P12" -dp "${TIZEN_DISTRIBUTOR_PASSWORD:-}"
else
  KIND=test
  PW="wygtv-test-$RANDOM$RANDOM"
  rm -f "$DATA/keystore/author/wygtv-test.p12"
  "$TIZEN" certificate -a wygtv-test -p "$PW" -c US -n "WygTV test" -f wygtv-test
  "$TIZEN" security-profiles add -n "$PROFILE" -a "$DATA/keystore/author/wygtv-test.p12" -p "$PW"
fi

# Package from a clean copy, so nothing but the app goes in.
rm -rf dist && mkdir -p dist/app
cp -r www/. dist/app/
"$TIZEN" package -t wgt -s "$PROFILE" -- "$PWD/dist/app"
WGT=$(ls dist/app/*.wgt | head -1)
mv "$WGT" dist/WygTV.wgt
rm -rf dist/app

# A signed widget carries both signatures, and the manifest and app pages.
LIST=$(unzip -Z1 dist/WygTV.wgt)
for f in author-signature.xml signature1.xml config.xml index.html js/tizen-bridge.js; do
  grep -qx "$f" <<<"$LIST" || { echo "dist/WygTV.wgt has no $f" >&2; exit 1; }
done
VERSION=$(grep -o 'version="1\.0\.[0-9]*"' www/config.xml | head -1 | cut -d'"' -f2)
echo "{\"version\": \"$VERSION\", \"signedWith\": \"$KIND\"}" > dist/tizen-version.json
echo "dist/WygTV.wgt: WygTV $VERSION, $(du -h dist/WygTV.wgt | cut -f1), signed with the $KIND certificate"
echo "signed-with=$KIND"
