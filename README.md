# WygTv v2.0 — Four-option opening screen

## How WygTV started
WygTV began with a simple goal: to bring free over-the-air channels from an HDHomeRun network tuner to the TV, complete with a proper channel list and TV guide. From there it grew into a full IPTV player, so your Xtream Codes and M3U playlists sit right alongside your antenna channels in one place. It was first built for Android TVs and tablets, and it has since come to Windows, Mac, Ubuntu, iPhone and iPad as well.

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

## HDHomeRun antenna TV
WygTV can watch free over-the-air channels from a SiliconDust HDHomeRun network tuner, on Android TV (remote / D-pad) and on tablets (touch).

- **Add it:** first-time setup → *HDHomeRun*, or Playlists → *Add an HDHomeRun antenna tuner*. The address starts as `hdhomerun.local`; *Find tuners on my network* searches by UDP broadcast (port 65001) and SiliconDust's cloud lookup, and any IP address can be typed instead.
- **Channels** come from the tuner's lineup (`/lineup.json`, the same list as `/lineup.html`), grouped by channel number (4.1, 4.2 and 4.3 sit under "Channel 4 · WNBC"). Copy-protected cable channels are left out. Playlists → Refresh re-reads the lineup after a channel scan.
- **TV guide** comes from SiliconDust's free HDHomeRun guide (`api.hdhomerun.com/api/guide.php`, authorised with the tuner's own DeviceAuth), about 12 hours ahead, refreshed every 30 minutes. An XMLTV link can be set instead.
- **Playback** uses Media3 with the Jellyfin FFmpeg audio extension, so Dolby AC-3 / E-AC-3 sound plays even on devices without a Dolby decoder (the device's own decoder and HDMI passthrough are still preferred). MPEG-2 video needs a hardware decoder: most Android TV boxes have one, many tablets don't (those get sound only and a message saying why).
- Each playing channel uses one of the tuner's tuners; the TV guide's preview window uses one too. When every tuner is busy the app says so.
- The tuner is saved by its IP address, so give it a fixed address (DHCP reservation) in your router.
- **Where it lives:** add, edit, refresh or remove tuners in **Settings → HDHomeRun antenna TV**. Each tuner shows in Live TV as its own category, **📡 Antenna TV** (new tuners get that name automatically; rename it in Settings), next to Favorites and your own categories. Playing an antenna channel uses the tuner; Home, Movies and Series switch back to your main playlist automatically.

## WygOTA — antenna TV only (Android TV, tablets and Ubuntu)
**WygOTA** is a second app built from this repository that does only live over-the-air TV from an HDHomeRun tuner: no IPTV playlists, movies or series. It installs next to WygTV (its own app id `com.wygtv.ota`, name and antenna icon).

- **Android TV and tablets:** `app/` built with `WYGOTA=1` (the same way `WYGTV_BETA=1` makes the beta). It shares all of WygTV's antenna code: first run goes straight to adding the tuner (`hdhomerun.local`, *Find tuners*, or an IP address), it opens on the TV guide of the tuner's channels, and Settings has no playlist, app-mode, TMDb or OMDb rows. Updates come from WygOTA's own release.
- **Ubuntu (desktop Linux):** `desktop/` is a small Java (Swing) app that compiles the same `Hdhr.java` from the Android app, so tuners, lineups, sub-channel groups and the 12-hour HDHomeRun guide work the same way. Video plays through VLC (libVLC), which handles MPEG-2, H.264 and AC-3 natively, with deinterlacing for 1080i/480i. Keys: ▲▼ pick, Enter play, Page Up/Down change channel, type a number like `4.1` to jump, F full screen, M mute.
- **Downloads** (rebuilt on every push to `app`):
  - Android: https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygota-latest/wygota.apk
  - Ubuntu 22.04 or newer: https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygota-latest/wygota_amd64.deb — install with `sudo apt install ./wygota_amd64.deb` (this also installs VLC), then open **WygOTA** from the app menu.
- Build locally: `WYGOTA=1 gradle :app:assembleDebug` for Android, `gradle -p desktop installDist` then `desktop/build/install/wygota/bin/wygota` for Linux.
