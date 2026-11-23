# WygTV-iOS

WygTV for iPhone and iPad. It is WygDesktop's screens (`../WygDesktop/src/renderer`) inside a
[Capacitor](https://capacitorjs.com) app, with `web/ios-bridge.js` standing in for the desktop app's
main process: web requests go through iOS (no CORS rules), guides are cached in the app, backups go to
**Files › On My iPad › WygTV-iOS**, and the external player is VLC (or Infuse, nPlayer, Outplayer).

## Install with AltStore

The app isn't signed by Apple, so it's installed with [AltStore](https://altstore.io), which signs it with
your own Apple ID and refreshes it every 7 days over Wi-Fi.

1. Install AltStore on the iPad (AltServer on a Windows PC or Mac does the first install).
2. In AltStore: **Browse → Sources → +** and add
   `https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygtv-ios-latest/altstore-source.json`
3. Install **WygTV-iOS** from that source. New builds then show up under **My Apps → Updates**.

Or download the app itself and open it in AltStore:
<https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygtv-ios-latest/WygTV-iOS.ipa>

## What's different from WygDesktop

- No FFmpeg on iOS. HLS (`.m3u8`) plays in Apple's own player, so Xtream live channels use HLS.
  Streams iOS can't play (MPEG-2 antenna channels, AC-3 sound, MKV/AVI files) offer **Open in VLC**.
- HDHomeRun tuners are found through SiliconDust's lookup and `hdhomerun.local` (iOS apps can't broadcast).
  Most antenna channels are MPEG-2, so they open in VLC.
- Touch: tap to open or play, touch and hold for options, swipe from the left edge or tap ‹ to go back.
- Landscape only; on iPhone the screen is laid out 1000 points wide and scaled to fit.

## Building

CI (`.github/workflows/ios.yml`, macOS) does everything: `npm ci`, `npm run build:web`, `npx cap sync ios`,
a self-test in an iPad Simulator (screenshots are kept as the `wygtv-ios-screenshots` artifact), then an
unsigned Release build zipped into `WygTV-iOS.ipa`. Pushes to `app` publish the pre-release `wygtv-ios-latest`
(`WygTV-iOS.ipa`, `altstore-source.json`, `ios-version.json`); other branches publish `wygtv-ios-test`.

On a Mac: `npm ci && npm run build:web && npx cap sync ios && npx cap open ios`.
