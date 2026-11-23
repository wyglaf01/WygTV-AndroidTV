package com.wygtv.tv;

import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The built-in "Free TV" playlist: free, legal-to-watch channels (news, PBS Kids, Pluto, Plex, Samsung TV
 * Plus and other free ad-supported streams) from the public iptv-org list. This is the same list
 * iptvowl.com plays. It is saved like any M3U playlist, so it shows in Live TV, the guides and Search, and
 * it can be switched on or off in Settings > Manage playlists like the others. Nothing is bundled in the
 * app: the list is downloaded from its link, so it stays up to date.
 */
final class FreeTv {
    static final String ID = "builtin_free_tv";
    static final String TITLE = "Free TV";
    static final String URL = "https://iptv-org.github.io/iptv/countries/us.m3u";
    /** Pref: true once the person deleted the Free TV playlist, so it isn't added back on the next start. */
    static final String REMOVED = "free_tv_removed";

    private FreeTv() { }

    static boolean is(JSONObject p) { return p != null && ID.equals(p.optString("id")); }

    static JSONObject playlist() {
        JSONObject p = new JSONObject();
        try {
            p.put("id", ID).put("type", Library.M3U).put("title", TITLE).put("url", URL).put("builtin", true)
                    .put("added", System.currentTimeMillis());
        } catch (JSONException ignored) { }
        return p;
    }

    /** Called at start-up: adds the Free TV playlist unless the person deleted it. */
    static void ensure(Library lib, SharedPreferences prefs) {
        if (prefs.getBoolean(REMOVED, false) || lib.find(ID) != null) return;
        lib.put(playlist());
        if (lib.find(lib.activeId()) == null) lib.activate(lib.find(ID));
    }

    static boolean present(MainActivity a) { return a.library.find(ID) != null; }

    static boolean on(MainActivity a) { JSONObject p = a.library.find(ID); return p != null && a.library.isOn(p); }

    /** The Settings row's summary. */
    static String summary(MainActivity a) {
        JSONObject p = a.library.find(ID);
        if (p == null) return "off  •  press OK to add about 1,500 free US channels (news, kids, movies, Pluto, Plex and more)";
        int n = p.optInt("count", -1);
        return (a.library.isOn(p) ? "on" : "off") + (n >= 0 ? "  •  " + n + " channels" : "") + "  •  press OK to turn " + (a.library.isOn(p) ? "off" : "on");
    }

    /** The Settings row's action: turns Free TV on (adding it back if it was deleted) or off. */
    static void toggle(MainActivity a, Runnable after) {
        JSONObject p = a.library.find(ID);
        java.util.Set<String> off = new java.util.HashSet<>(a.library.offIds());
        boolean turnOn = p == null || !a.library.isOn(p);
        if (p == null) {
            a.prefs.edit().putBoolean(REMOVED, false).apply();
            p = playlist();
            a.library.put(p);
        }
        if (turnOn) off.remove(ID);
        else {
            off.add(ID);
            if (off.size() >= a.library.all().length()) { Toast.makeText(a, "Keep at least one playlist on.", Toast.LENGTH_LONG).show(); return; }
        }
        a.prefs.edit().putStringSet(Library.OFF, off).apply();
        if (turnOn && a.library.find(a.library.activeId()) == null) a.switchPlaylist(p);
        else Manage.applyPlaylistsOnOff(a);
        Toast.makeText(a, turnOn ? "Free TV is on. Its channels are in Live TV." : "Free TV is off.", Toast.LENGTH_LONG).show();
        after.run();
    }
}
