# WygTV Android TV — source project

Android TV / Google TV remote-friendly Xtream Codes client. Includes TV launcher, dark glass-style menus, Home, saved playlist login, Live TV, Movies, Series, category browsing, favorites, series episodes, and Media3 playback. Uses the Xtream Codes `player_api.php` API. Use only playlists/content you are authorized to access.

## Build
Install Android Studio with JDK 17 and Android SDK Platform 35. Open this folder as an Android Studio project and run **Build > Build APK(s)**. Output: `app/build/outputs/apk/debug/app-debug.apk`. You can install via `adb install -r app/build/outputs/apk/debug/app-debug.apk` with Android TV developer/USB or network debugging enabled.

## Status / limitations
Source is provided; **APK was not compiled or device-tested** in this environment. This is a starter TV version, not the fully polished WygTV design: satellite art and logo assets from the prior project are not embedded. The EPG side-by-side guide, multiple named playlists, search, settings startup toggle, category side-by-side submenus, and persistent back stack need additional implementation. Login data is stored in app-private SharedPreferences without encryption. Android targetSdk 28 is used for compatibility with HTTP Xtream servers; prefer HTTPS. Long category lists are not virtualized. Some providers may use different stream formats or URL conventions.


## Xtream integration changes (November 2026)
The `app/src/main` module is the build source. Root-level Java/manifest/banner copies are historical references and are not compiled.

- Existing WygTV home, colors, text branding, banner and category side menu remain in place.
- Login validates HTTP(S) addresses and active authentication before replacing the saved account. Network errors do not show credential-bearing URLs. Existing credentials remain in private preferences.
- Live channel actions include a short EPG guide (up to 20 provider entries, provider-local timestamps, Base64 title/description decoding). This is a dialog, not a timeline guide.
- Categories and favorites scroll with the remote; content is paged in groups of 100 instead of silently cutting off at 1,000. Categories can regain focus from the content pane.
- Favorites retain movie extensions and are isolated by server/username. Legacy favorites are copied to the initially saved account; originals are retained. Live/movie favorites can be removed from the favorites screen.
- Late responses cannot replace a screen after navigation; category responses ignore superseded item requests. Playback Back returns to the prior content type or series seasons; background playback pauses.
- Actions builds on `app`, `main`, `codex/**`, and pull requests, running lint, protocol unit tests and APK assembly, then uploading APK and check reports.

The prior referenced chat contains no separate Xtream source attachment. No original logo/satellite/startup artwork is present in this repository; those assets cannot be restored from this source. This change preserves the available WygTV interface. Test on an Android TV with a valid provider account before merging: rejected login, all three catalogs, season/episode playback, EPG/no-data, favorites across account changes, long lists, D-pad focus, Back and background pause. Device playback and provider integration are not covered by the protocol unit tests.
