# WygTV for Samsung Smart TVs (Tizen)

WygTV on Samsung TVs from **2020 and later** (Tizen 5.5 and newer). It is WygDesktop's screens (the same ones WygTV-iOS uses) packaged as a Tizen TV app, with the remote mapped to them.

**Download:** https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygtv-tizen-latest/WygTV.wgt

Every change merged into `app` is built by GitHub Actions (`.github/workflows/tizen.yml`) and published there.

## What works

- Xtream Codes logins and M3U links, Live TV, TV guide, Movies and Series, Search, Favorites, themes.
- **Antenna TV:** a black **Tablo** (DUAL, QUAD, LITE) with its own guide. An **HDHomeRun**'s channel list and guide also work, but most HDHomeRun channels are MPEG-2, which a TV app can't convert (there is no FFmpeg on a TV), so only its H.264 channels play.
- **▣ Mini player:** the picture keeps playing in a corner while you browse.
- **Remote:** arrows and OK move and play, **Back** goes back, **CH +/−** change channel, **▶❚❚** pauses, **■** stops, **Info** shows what's on, **Guide** opens the TV guide, **red** opens options. Number keys jump to a channel.
- Not on a TV: other video apps (VLC), picture-in-picture, and playlist files (add playlists by their link).

## Installing it on your TV (developer mode)

Samsung TVs only install apps signed with a certificate made for that TV. You make it once with Samsung's free Tizen Studio, using your Samsung account.

1. **Tizen Studio:** install it from https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/installing-tv-sdk.html. In its Package Manager, under **Extension SDK**, install **TV Extensions** and **Samsung Certificate Extension**.
2. **Developer mode on the TV:** open **Apps**, then press **1 2 3 4 5** on the remote. Switch **Developer mode** on, enter your computer's IP address, then turn the TV off and on again.
3. **Connect:** in Tizen Studio open **Tools → Device Manager → Remote Device Manager**, add the TV's IP address and switch it on (connected).
4. **Certificate:** open **Tools → Certificate Manager → +**, pick **Samsung → TV**, sign in with your Samsung account and make an author and a distributor certificate (privilege level **Public**). The connected TV's DUID is added for you.
5. **Sign and install** (Tizen Studio's `tizen` command is in `tizen-studio/tools/ide/bin`):
   ```
   mkdir WygTV && cd WygTV && unzip ../WygTV.wgt && cd ..
   tizen package -t wgt -s <your certificate profile name> -- WygTV
   tizen install -n WygTV/WygTV.wgt -t <TV name from: sdb devices>
   ```
WygTV then appears in **Apps** on the TV. To update, repeat step 5 with a newer WygTV.wgt (Settings → About & updates tells you when there is one).

## Publishing in Samsung's TV app store

Register as a seller at https://seller.samsungapps.com/tv, create the app there, and upload the package signed with the **distributor certificate Samsung issues for publishing** (Certificate Manager, privilege level Partner or Public as Samsung asks). Samsung reviews TV apps before they appear in the store.

## Building

```
cd WygTV-Tizen
npm install
node scripts/build.mjs 1.0.123      # → dist/WygTV.wgt (unsigned) and build/app
python3 scripts/smoke.py build/app   # loads it in headless Chromium (needs: pip install playwright)
```

`web/tizen-bridge.js` stands in for the desktop app's main process: web requests (XMLHttpRequest, allowed to any server by `config.xml`), guides (kept in memory), the HDHomeRun and Tablo modules from `WygDesktop/src/main`, and the remote's keys. The JavaScript is lowered to Chromium 69 (Tizen 5.5) with esbuild.
