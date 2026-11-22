package com.wygtv.tv;

/**
 * Which app this build is. The same code builds two apps (Gradle product flavors):
 * WygTV (IPTV playlists, movies, series and antenna TV) and WygOTA (only live over-the-air TV
 * from an HDHomeRun tuner: no playlists, movies or series).
 */
final class Brand {
    /** True in the WygOTA app. */
    static final boolean OTA = BuildConfig.OTA;
    /** "WygTV" or "WygOTA", for anything the person reads. */
    static final String NAME = OTA ? "WygOTA" : "WygTV";
    /** The APK each CI build on `app` publishes for this app. */
    static final String APK = OTA ? "wygota.apk" : "wygtv.apk";
    /** Where that APK and its version.json are downloaded from (fixed links, also used for Downloader codes). */
    static final String RELEASE = OTA
            ? "https://github.com/wyglaf01/WygTV-AndroidTV/releases/download/wygota-latest/"
            : "https://github.com/wyglaf01/WygTV-AndroidTV/releases/latest/download/";

    private Brand() { }
}
