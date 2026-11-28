"""Verify the built distribution, not only its source settings."""
import pathlib, plistlib, sys
app = pathlib.Path(sys.argv[1])
with (app / 'Info.plist').open('rb') as f: info = plistlib.load(f)
assert info['CFBundleIdentifier'] == 'com.wygtv.ios'
assert info['CFBundleDisplayName'] == 'WygTV'
assert 'altstore' not in info.get('LSApplicationQueriesSchemes', [])
with (app / 'PrivacyInfo.xcprivacy').open('rb') as f: privacy = plistlib.load(f)
assert privacy['NSPrivacyTracking'] is False
reasons = {e['NSPrivacyAccessedAPIType']: e['NSPrivacyAccessedAPITypeReasons'] for e in privacy['NSPrivacyAccessedAPITypes']}
assert 'CA92.1' in reasons['NSPrivacyAccessedAPICategoryUserDefaults']
assert 'C617.1' in reasons['NSPrivacyAccessedAPICategoryFileTimestamp']
bridge = (app / 'public/js/ios-bridge.js').read_text()
assert "distribution: 'appstore'" in bridge
for marker in ['altstore://', 'wygtv-ios-latest', 'WYG-SMOKE', 'WYG-STEP', "Prefs.get({ key: 'smoke' })"]:
    assert marker not in bridge, 'Distribution contains test or sideload code: ' + marker
settings = (app / 'public/js/screens-settings.js').read_text()
assert 'Privacy Policy' in settings and 'Support' in settings
assert 'Open AltStore' not in settings and 'Check for updates' not in settings
assert "W.DEFAULT_OMDB_KEY = '';" in (app / 'public/js/core.js').read_text()
print('App Store bundle preflight passed:', app)
