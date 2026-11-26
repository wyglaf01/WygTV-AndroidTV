# WygTV for Samsung Smart TVs (Tizen)

WygTV on Samsung TVs from 2020 on (Tizen 5.5 and newer). Like WygTV-iOS, it is WygDesktop's screens
(`../WygDesktop/src/renderer`) in another shell: here a Tizen TV web app, with `web/tizen-bridge.js` standing in
for the desktop app's main process. Changes to the desktop screens reach the TV app too.

**Status: phase 1 of 7.** It builds, opens to Home, loads playlists and channels, and the remote's arrows, OK
and Back work. Video, gzip guides and backups are not done yet (see the phases below). It hasn't been tried on a
real TV yet.

## How it is built

`npm install`, then `npm run build` makes `www/`, the app as the TV runs it:

- the desktop screens, hls.js and mpegts.js, and the desktop's `m3u.js`, `xmltv.js`, `hdhr.js` and `tablo.js`
  (wrapped by the build so they run in the page, as for iOS)
- `web/tizen-bridge.js`: web requests (a TV app may call any server, so there are no CORS rules), guides,
  HDHomeRun (cloud lookup and `hdhomerun.local`: TV apps can't broadcast to find tuners), Tablo, and the Back
  key, which goes back and on Home asks whether to exit, as Samsung requires
- `config.xml`: the app's Samsung manifest (`WygTVtizen.WygTV`, version `1.0.<build number>`)

**2020 TVs** run Chromium 69, which is older than the desktop app's Electron. The build lowers all JavaScript to
Chrome 69 with esbuild, and `scripts/legacy-css.mjs` adds fallbacks for CSS that Chromium 69 lacks: flex `gap`,
`aspect-ratio`, `min()` and `:has()`. `web/tizen-legacy.js` switches them on only when the TV needs them, so
newer TVs use the real CSS. Add `?legacy=1` to the page address to try the fallbacks in a current browser.

## Checks

- `npm test` builds `www/` and checks it: every script parses as JavaScript Chromium 69 understands, WygTV's own
  code uses no newer browser features, the CSS fallbacks are in place, and `config.xml` is a valid TV manifest.
- `npm run smoke` opens `www/` in Chromium at 1920×1080 with a stand-in for the TV's `tizen` object, normally and
  with the fallbacks on. It checks Home, Live TV and the Back key, compares the two layouts, and saves screenshots
  in `smoke/`. It needs Playwright with Chromium.

GitHub Actions (`.github/workflows/tizen.yml`) runs both on every change and keeps `www/` and the screenshots.

## Trying it on a TV (once phase 3 makes video play)

1. Install [Tizen Studio](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/installing-tv-sdk.html)
   with the **TV Extensions** and **Samsung Certificate Extension** packages.
2. On the TV: **Apps**, then press **1 2 3 4 5** on the remote, switch **Developer mode** on, enter the
   computer's IP address, and restart the TV.
3. In Tizen Studio: **Device Manager** → add the TV by its IP address. **Certificate Manager** → make a
   **Samsung** certificate for TV with your Samsung account (the TV's DUID is added from the Device Manager).
4. Package and install: `tizen package -t wgt -s <certificate profile> -- www`, then
   `tizen install -n WygTV.wgt -t <TV name>`.

## Phases

1. **Skeleton** (this): build, Chromium 69 fallbacks, `config.xml`, bridge for playlists and tuners, Back key. ✔
2. **Bridge**: gzip guides, guides kept between starts (IndexedDB), backups.
3. **Player**: Samsung's AVPlay for live TV, movies and antenna channels (MPEG-2 and Dolby AC-3 play in the TV's
   hardware).
4. **Remote**: channel up/down, number keys for channels like `4.1`, play/pause and colour keys, TV wording.
5. **Builds**: signed `.wgt` from GitHub Actions (Samsung certificate as secrets), `wygtv-tizen-latest` release.
6. **Testing** on a real TV in Developer mode.
7. **Store**: Samsung TV Seller Office application.
