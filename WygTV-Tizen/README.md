# WygTV for Samsung Smart TVs (Tizen)

WygTV on Samsung TVs from 2020 on (Tizen 5.5 and newer). Like WygTV-iOS, it is WygDesktop's screens
(`../WygDesktop/src/renderer`) in another shell: here a Tizen TV web app, with `web/tizen-bridge.js` standing in
for the desktop app's main process. Changes to the desktop screens reach the TV app too.

**Status: phase 7 of 7.** Ready for testing on a real TV ([TESTING.md](TESTING.md)) and for Samsung's TV store
([STORE.md](STORE.md)). It builds, opens to Home, loads playlists, channels and guides (gzip too, kept between
starts), saves and restores backups, plays video in Samsung's AVPlay (antenna channels included) with its sound tracks and
subtitles, and works with the whole remote: arrows, OK, Back, CH ▲▼, number keys (4 - 1 for 4.1), play, pause,
rewind, fast-forward, INFO, CC, GUIDE, CH LIST and the colour keys (red Live TV, green TV Guide, yellow Search,
blue Settings). It has been tested in a browser with a stand-in AVPlay, not yet on a real TV.

## How it is built

`npm install`, then `npm run build` makes `www/`, the app as the TV runs it:

- the desktop screens, hls.js and mpegts.js, and the desktop's `m3u.js`, `xmltv.js`, `hdhr.js` and `tablo.js`
  (wrapped by the build so they run in the page, as for iOS)
- `web/tizen-bridge.js`: web requests (a TV app may call any server, so there are no CORS rules); guides, which
  stream in and un-gzip as they download (fflate) and are kept in IndexedDB for 12 hours; backups, kept on the TV
  (the newest 5) or restored from a web link, e.g. a desktop or iPhone backup put online; HDHomeRun (cloud lookup
  and `hdhomerun.local`: TV apps can't broadcast to find tuners); Tablo; and the Back key, which goes back and on
  Home asks whether to exit, as Samsung requires
- `web/tizen-avplay.js`: video in Samsung's AVPlay, which plays HLS, MPEG-TS, MP4 and MKV with H.264, HEVC or
  MPEG-2 video and AAC or Dolby sound in the TV's hardware. It gives the shared player an `<object
  type="application/avplayer">` that behaves like `<video>` (same properties and events), tells AVPlay where to
  draw (the preview box, the mini player or the whole screen), and lets go of the decoder when the TV's Home button
  sends WygTV to the background. Antenna channels, which the desktop app converts with FFmpeg, play as they are.
- `config.xml`: the app's Samsung manifest (`WygTVtizen.WygTV`, version `1.0.<build number>`)

**Text size.** On the TV everything is drawn larger for viewing from the sofa: `web/tizen-legacy.js` sets the
page's viewport width as it loads (Extra large 1097: 1.75×; Large 1280, the default: 1.5×; Medium 1536; Small
1920) and the TV scales the page up to the screen. AVPlay's position is scaled the same way. **Settings →
Appearance → Size of text and menus** is a toggle: each OK goes to the next size.

**Typing on a phone.** Every form on the TV has **📱 Type on your phone** (`web/tizen-phone.js`): it shows a QR code
for `docs/send.html` (GitHub Pages), which shows the same fields on the phone. What's typed there is encrypted on
the phone (tweetnacl secretbox) with a one-time key that only the QR code carries, after "#" in the address so it
never reaches a server, and sent through the free ntfy.sh relay to that QR code's own random channel; the TV
decrypts it and fills the form. Passwords are never put in the QR code. Samsung's SmartThings phone app (remote →
keyboard) also types into WygTV's text boxes.

**2020 TVs** run Chromium 69, which is older than the desktop app's Electron. The build lowers all JavaScript to
Chrome 69 with esbuild, and `scripts/legacy-css.mjs` adds fallbacks for CSS that Chromium 69 lacks: flex `gap`,
`aspect-ratio`, `min()` and `:has()`. `web/tizen-legacy.js` switches them on only when the TV needs them, so
newer TVs use the real CSS. Add `?legacy=1` to the page address to try the fallbacks in a current browser.

## Checks

- `npm test` builds `www/` and checks it: every script parses as JavaScript Chromium 69 understands, WygTV's own
  code uses no newer browser features, the CSS fallbacks are in place, and `config.xml` is a valid TV manifest.
- `npm run smoke` opens `www/` in Chromium at 1920×1080 with a stand-in for the TV's `tizen` object, normally and
  with the fallbacks on. It checks Home, Live TV and the Back key, compares the two layouts, loads a gzip guide
  and checks it comes from storage after a restart, saves and restores backups (from the TV and from a link), and
  plays video through a stand-in AVPlay (preview, full screen, channel change, antenna channel, a broken stream,
  movie resume, Home button, Back), presses remote keys as the TV sends them (colour keys, channel numbers, CH ▲,
  pause and play, INFO, CC) and picks sound tracks and subtitles, and saves screenshots in `smoke/`. It needs Playwright with Chromium.

GitHub Actions (`.github/workflows/tizen.yml`) runs both on every change and keeps `www/` and the screenshots.

## Signed builds (WygTV.wgt)

`scripts/sign.sh` signs `www/` into `dist/WygTV.wgt` with Tizen Studio's `tizen` command line. GitHub Actions
(`.github/workflows/tizen.yml`) runs it on every build, and builds on `app` signed with your Samsung certificate are
published at
**https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygtv-tizen-latest/WygTV.wgt**.

To give GitHub your certificate (once, after making it in Tizen Studio's Certificate Manager):

1. Find the two files Tizen Studio made: `~/SamsungCertificate/<profile name>/author.p12` and `distributor.p12`
   (on Windows, `C:\Users\<you>\SamsungCertificate\<profile name>\`).
2. Turn each into text: `base64 -w0 author.p12` on Linux, `base64 -i author.p12` on a Mac, or in Windows PowerShell
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("author.p12"))`.
3. On GitHub: the repository → **Settings → Secrets and variables → Actions → New repository secret**, four times:
   - `TIZEN_AUTHOR_P12_B64`: the text from author.p12
   - `TIZEN_AUTHOR_PASSWORD`: the author certificate's password
   - `TIZEN_DISTRIBUTOR_P12_B64`: the text from distributor.p12
   - `TIZEN_DISTRIBUTOR_PASSWORD`: the distributor certificate's password (Tizen Studio often uses the author's)
4. Run **Actions → Build WygTV for Samsung TVs (Tizen) → Run workflow** on `app`.

Until the secrets are there, the build signs with a throwaway test certificate: it checks the packaging works,
but Samsung TVs refuse that file, so it is only kept as a workflow artifact.

A Samsung certificate made for Developer mode lists the TVs it works on (their DUIDs). To add a TV later, add it
in Certificate Manager, make the distributor certificate again, and update the two distributor secrets.

## Trying it on a TV

1. Install [Tizen Studio](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/installing-tv-sdk.html)
   with the **TV Extensions** and **Samsung Certificate Extension** packages.
2. On the TV: **Apps**, then press **1 2 3 4 5** on the remote, switch **Developer mode** on, enter the
   computer's IP address, and restart the TV.
3. In Tizen Studio: **Device Manager** → add the TV by its IP address. **Certificate Manager** → make a
   **Samsung** certificate for TV with your Samsung account (the TV's DUID is added from the Device Manager).
4. Install the signed build: download `WygTV.wgt` from the link above, then
   `sdb connect <TV IP>` and `tizen install -n WygTV.wgt -t <TV name from sdb devices>`.
   Or sign your own: `npm run build`, then `TIZEN_AUTHOR_P12=… TIZEN_AUTHOR_PASSWORD=… TIZEN_DISTRIBUTOR_P12=…
   TIZEN_DISTRIBUTOR_PASSWORD=… scripts/sign.sh` and install `dist/WygTV.wgt` the same way.

## Phases

1. **Skeleton**: build, Chromium 69 fallbacks, `config.xml`, bridge for playlists and tuners, Back key. ✔
2. **Bridge**: gzip guides, guides kept between starts (IndexedDB), backups. ✔
3. **Player**: Samsung's AVPlay for live TV, movies and antenna channels (MPEG-2 and Dolby AC-3 play in the TV's
   hardware). ✔ (to check on a real TV: the picture in the Live TV preview box and the mini player)
4. **Remote**: channel up/down, number keys for channels like `4.1`, play/pause and colour keys, sound tracks and
   subtitles from AVPlay, TV wording. ✔
5. **Builds**: signed `.wgt` from GitHub Actions (Samsung certificate as secrets), `wygtv-tizen-latest` release. ✔
6. **Testing** on a real TV in Developer mode: the checklist in [TESTING.md](TESTING.md), and **Settings → About
   → 🛠 Diagnostics** (TV model, firmware, Tizen and web engine versions, fallbacks, recent errors with playlist
   logins masked) to photograph when something goes wrong.
7. **Store**: Samsung TV Seller Office: partnership, listing, tester notes and a self-check in
   [STORE.md](STORE.md); a demo playlist of free test streams (`docs/demo.m3u`); 1920×1080 store screenshots
   (`npm run store-shots`, and the *wygtv-tizen-store-screenshots* artifact of every build). ✔ (submitting is done
   in Seller Office, with your Samsung account)
