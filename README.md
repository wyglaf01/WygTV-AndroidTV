# WygTV Android TV — source project

Android TV / Google TV remote-friendly Xtream Codes client. Includes TV launcher, dark glass-style menus, Home, saved playlist login, Live TV, Movies, Series, category browsing, favorites, series episodes, and Media3 playback. Uses the Xtream Codes `player_api.php` API. Use only playlists/content you are authorized to access.

## Build
Install Android Studio with JDK 17 and Android SDK Platform 35. Open this folder as an Android Studio project and run **Build > Build APK(s)**. Output: `app/build/outputs/apk/debug/app-debug.apk`. You can install via `adb install -r app/build/outputs/apk/debug/app-debug.apk` with Android TV developer/USB or network debugging enabled.

## Status / limitations
Source is provided; **APK was not compiled or device-tested** in this environment. This is a starter TV version, not the fully polished WygTV design: satellite art and logo assets from the prior project are not embedded. The EPG side-by-side guide, multiple named playlists, search, settings startup toggle, category side-by-side submenus, and persistent back stack need additional implementation. Login data is stored in app-private SharedPreferences without encryption. Android targetSdk 28 is used for compatibility with HTTP Xtream servers; prefer HTTPS. Long category lists are not virtualized. Some providers may use different stream formats or URL conventions.
