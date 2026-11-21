# WygTv v2.0 — Four-option opening screen

Opens directly to a glass-themed Home dashboard with Manage / Login to Playlist, Live TV, Movies and Series. Live TV, Movies and Series use the selected saved playlist or offer a playlist picker; credentials are not requested on every startup. Existing cascading menus, themes and launcher icon remain.

Build: `export ANDROID_HOME="$HOME/Library/Android/sdk" && bash build-on-mac.sh`. Install: `adb install -r output/WygTv.apk`.

This source has not been compiled or device-tested here. Keep a backup of your working APK and saved playlists.

## Home screen visual update
- Locally drawn starfield and satellite illustration on the opening dashboard (no network image dependency).
- Existing app launcher icon displayed at top right as WygTV branding.
- Four consistently aligned, equal-height glass buttons.
- Saved playlist name and Xtream `user_info.exp_date` shown on Home; date is refreshed asynchronously and cached per playlist. Missing expiry is labeled as unavailable. M3U has no Xtream expiration API.
- Other screens and existing navigation remain unchanged.

## Old West home background
The start screen now draws a sunset desert with mesas, a ranch silhouette, fence and cacti. The WygTV top-right logo, glass buttons, playlist expiration and split Live TV layout are retained.


## Steampunk Main Home theme
The main landing page uses a locally bundled steampunk backdrop (`res/drawable-nodpi/steampunk_home.jpg`), brass-rimmed smoked-glass Live TV / Movies / Series buttons, and a matching Settings button and playlist-account panel. Existing menu behavior, split Live TV guide, and Xtream expiration lookup are unchanged.
