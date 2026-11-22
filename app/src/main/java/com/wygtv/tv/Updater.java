package com.wygtv.tv;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updates. Every CI build on `app` publishes wygtv.apk plus version.json to the
 * "latest" GitHub release. WygTV compares that versionCode with its own and, when newer,
 * downloads the APK and hands it to the system installer, which installs it over the old one
 * (playlists, favourites and settings are kept).
 */
final class Updater {
    static final String RELEASE = "https://github.com/wyglaf01/WygTV-AndroidTV/releases/latest/download/";
    static final String PREF_AUTO = "update_check_startup";

    private Updater() { }

    static boolean isNewer(int latest, int installed) { return latest > installed; }

    static int installedCode(MainActivity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    static String installedName(MainActivity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    /** Called once at start-up: quietly offers an update when there is one. */
    static void checkAtStartup(MainActivity a) {
        if (a.prefs.getBoolean(PREF_AUTO, true)) check(a, false);
    }

    /** Looks for a newer WygTV. When {@code manual}, also says so when it is already up to date or the check fails. */
    static void check(MainActivity a, boolean manual) {
        if (manual) Toast.makeText(a, "Checking for a WygTV update…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            JSONObject info = null;
            try { info = new JSONObject(new String(fetch(RELEASE + "version.json"), "UTF-8")); } catch (Exception ignored) { }
            final JSONObject latest = info;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (latest == null) { if (manual) Menus.alert(a, "Couldn't reach the WygTV update page. Check the internet connection and try again."); return; }
                int code = latest.optInt("versionCode", -1), mine = installedCode(a);
                String name = latest.optString("versionName", String.valueOf(code));
                if (!isNewer(code, mine)) { if (manual) Toast.makeText(a, "WygTV " + installedName(a) + " is the latest version.", Toast.LENGTH_LONG).show(); return; }
                new Dialogs.Builder(a).setTitle("WygTV update available")
                        .setMessage("Version " + name + " is ready (you have " + installedName(a) + ").\n\nIt installs over this one, and your playlists, favourites and settings are kept.")
                        .setPositiveButton("Update now", (d, w) -> download(a, name))
                        .setNegativeButton("Later", null).show();
            });
        }).start();
    }

    private static void download(MainActivity a, String name) {
        AlertDialog progress = new Dialogs.Builder(a).setTitle("Downloading WygTV " + name).setMessage("Starting…").setCancelable(false).show();
        new Thread(() -> {
            File apk = ApkProvider.file(a);
            String error = null;
            try {
                apk.getParentFile().mkdirs();
                HttpURLConnection c = open(RELEASE + "wygtv.apk");
                int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0, shown = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done - shown > 256 * 1024) {
                            shown = done;
                            final String msg = total > 0 ? (done * 100 / total) + "%  (" + done / 1048576 + " of " + total / 1048576 + " MB)" : done / 1048576 + " MB";
                            a.runOnUiThread(() -> progress.setMessage(msg));
                        }
                    }
                } finally { c.disconnect(); }
                if (total > 0 && apk.length() != total) error = "The download stopped early. Try again.";
            } catch (Exception e) { error = "The download failed: " + e.getMessage(); }
            final String failed = error;
            a.runOnUiThread(() -> {
                try { progress.dismiss(); } catch (Exception ignored) { }
                if (a.isFinishing()) return;
                if (failed != null) Menus.alert(a, failed); else install(a);
            });
        }).start();
    }

    /** Hands the downloaded APK to the system installer, asking for "install unknown apps" first when needed. */
    static void install(MainActivity a) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new Dialogs.Builder(a).setTitle("Allow WygTV to install updates")
                    .setMessage("Turn on \"Allow from this source\" (or \"Install unknown apps\") for WygTV on the next screen, then press Back and choose Update again.")
                    .setPositiveButton("Open setting", (d, w) -> {
                        try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); }
                        catch (Exception e) { Menus.alert(a, "Open Settings > Apps > Special app access > Install unknown apps and allow WygTV."); }
                    })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        i.setDataAndType(ApkProvider.uri(a), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        try { a.startActivity(i); }
        catch (Exception e) { Menus.alert(a, "This TV couldn't open the installer: " + e.getMessage()); }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "WygTV");
        if (c.getResponseCode() != 200) { c.disconnect(); throw new Exception("HTTP " + c.getResponseCode()); }
        return c;
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = open(url + "?t=" + System.currentTimeMillis());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally { c.disconnect(); }
    }
}
