# WygDesktop

The desktop copy of WygTV for **Windows** and **Ubuntu**, in the same royal blue and bronze look.

## Download

Every change merged into the `app` branch is built by GitHub Actions and published here:

| | |
|---|---|
| Windows installer | https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygdesktop-latest/WygDesktop-Setup.exe |
| Windows, no install | https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygdesktop-latest/WygDesktop-Portable.exe |
| Ubuntu (.deb) | https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygdesktop-latest/WygDesktop-linux.deb |
| Ubuntu (AppImage) | https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygdesktop-latest/WygDesktop-linux.AppImage |

Builds from other branches go to the `wygdesktop-test` release instead. Settings → About & updates → Check for updates installs newer builds.

The Windows build isn't code-signed, so SmartScreen may say "Windows protected your PC": click **More info → Run anyway**.
On Ubuntu (22.04 and 24.04 are checked on every build):
- **.deb** (recommended): `sudo apt install ./WygDesktop-linux.deb`, then open WygDesktop from the app menu or run `wygdesktop`.
- **AppImage**: AppImages need FUSE 2, which Ubuntu no longer installs by default: `sudo apt install libfuse2t64` on 24.04 (`libfuse2` on 22.04). Then `chmod +x WygDesktop-linux.AppImage` and run it.
- FFmpeg (for antenna channels and AC-3/DTS sound): the .deb installs Ubuntu's `ffmpeg` package; the AppImage carries its own.

## Features

- **Playlists**: Xtream Codes logins, M3U links or files, HDHomeRun antenna tuners ("Antenna TV", found automatically).
- **Live TV**: Favorites, Recent, categories per playlist, channel list with what's on now, preview with the next shows. Enter plays in the preview, Enter again goes full screen. Hide/show categories.
- **TV Guide**: 12-hour grid. Guides come from the playlist (XMLTV), HDHomeRun's guide, and extra XMLTV links (Settings → TV guides, including free epg.pw guides by country).
- **Movies and Series**: categories, poster grid, My List, Continue watching, Recently added, details with IMDb / Rotten Tomatoes / Metacritic ratings (OMDb, key built in) and optional TMDb art, seasons and episodes with watch progress, next episode plays by itself.
- **Search** across channels, movies and series.
- **Player**: HLS and MPEG-TS play directly. Streams the built-in player can't decode (MPEG-2 antenna channels, AC-3/DTS sound, AVI…) are converted on the fly by the bundled FFmpeg. Live streams reconnect when they stall. Sound track choice, VLC/mpv hand-off.
- Themes (royal blue & bronze default, steampunk, blue, cyan, brown, gold, green), backup and restore, start on the last channel.

## Keys

Arrows move · Enter/OK plays · hold Enter, right-click or M for options · Esc/Backspace goes back · S searches · F11 full screen.
In the player: Up/Down or Page Up/Down change channel, number keys jump to a channel, Left/Right skip in movies, Space/P pauses, I shows info, F full screen.
Mouse works everywhere (double-click a channel to go full screen).

## Developing

```
cd WygDesktop
npm install
npm start          # run the app
npm test           # unit tests
npm run dist:win   # or dist:linux
```

`node test/mock-server.js 8765 <media folder>` runs a pretend Xtream provider (server `http://127.0.0.1:8765`, user `demo`, password `demo`) for trying the app without a real service.

Built with Electron 33, hls.js, mpegts.js and ffmpeg-static. Code: `src/main` (downloads, guides, HDHomeRun, FFmpeg, updates) and `src/renderer` (screens and player).
