package com.wygtv.tv;

import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * HDHomeRun tuners as a category. A tuner is added in Settings and saved like a playlist (see {@link Hdhr}),
 * but it shows in Live TV as its own category, "📡 Antenna TV", next to Favorites and your categories, instead
 * of in the playlist list. Its channels play the same way a favorite from another playlist does: the tuner
 * becomes the active source while it plays, and Home, Movies and Series switch back to your main playlist.
 */
final class Antenna {
    /** Category id prefix: "antenna_" + the tuner's playlist id. */
    static final String PREFIX = "antenna_";
    /** The last playlist that wasn't a tuner, so Home, Movies and Series can go back to it. */
    static final String MAIN_PLAYLIST = "main_playlist";

    private Antenna() { }

    static boolean isAntenna(String categoryId) { return categoryId != null && categoryId.startsWith(PREFIX); }

    /** Saved tuners, in the order they were added. */
    static List<JSONObject> tuners(MainActivity a) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray all = a.library.all();
        for (int i = 0; i < all.length(); i++) { JSONObject p = all.optJSONObject(i); if (Library.isHdhr(p)) out.add(p); }
        return out;
    }

    /** "📡 Antenna TV" with one tuner, "📡 Antenna TV · Bedroom" with several. */
    static String label(MainActivity a, JSONObject tuner) {
        return tuners(a).size() <= 1 ? "📡 Antenna TV" : "📡 Antenna TV  ·  " + Library.nameOf(tuner);
    }

    /** Adds the Antenna TV entries to the first tier of the Live TV menu. */
    static void addMenuEntries(MainActivity a) {
        for (JSONObject t : tuners(a)) {
            String id = PREFIX + t.optString("id");
            LiveMenu.pick(a, label(a, t), id);
        }
    }

    /** Shows the tuner's channels in the mini guide. */
    static void show(MainActivity a, String categoryId) {
        JSONObject tuner = a.library.find(categoryId.substring(PREFIX.length()));
        if (tuner == null) { a.loadItems("fav"); return; }
        final int ticket = ++a.listGeneration;
        a.category = categoryId;
        if (a.status != null) a.status.setText("Loading antenna channels…");
        new Thread(() -> {
            JSONArray list;
            try { list = a.library.liveChannelsOf(tuner); } catch (Exception e) { list = null; }
            final JSONArray got = list;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || ticket != a.listGeneration) return;
                a.updateStatus();
                if (got == null) {
                    Menus.alert(a, "Couldn't reach the HDHomeRun at " + Menus.hostOf(tuner.optString("url"))
                            + ". Check it's on, or change its address in Settings > HDHomeRun antenna TV.");
                    a.items = new JSONArray();
                } else a.items = got;
                a.showItems();
            });
        }).start();
    }

    /** OK on an antenna channel: tune it, with CH+/CH- walking through the tuner's channels. */
    static void play(MainActivity a, JSONObject item) {
        JSONArray zap = a.items;
        if (!VirtualCategories.useItsPlaylist(a, item)) return;
        a.zapList = zap;
        String ext = item.optString("container_extension", "ts");
        a.play("live", item.optString("stream_id"), ext.isEmpty() ? "ts" : ext, item.optString("name", "Channel"));
    }

    /** Long-press OK on an antenna channel. */
    static void select(MainActivity a, JSONObject item) {
        String title = item.optString("name", "Channel"), sid = item.optString("stream_id");
        JSONObject tuner = a.library.find(item.optString("playlist"));
        String pre = tuner == null ? "" : Library.prefixOf(tuner);
        boolean fav = !pre.isEmpty() && a.isFavoriteIn(pre, "live", sid);
        new Dialogs.Builder(a).setTitle(title).setItems(new String[]{"Play", fav ? "Remove Favorite" : "Add Favorite", "Program guide", "Add to category…", "Close"}, (d, w) -> {
            if (w == 0) play(a, item);
            else if (w == 1 && !pre.isEmpty()) {
                boolean added = a.toggleFavoriteIn(pre, "live", sid, title, "ts");
                Toast.makeText(a, added ? "Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
            } else if (w == 2) {
                if (!VirtualCategories.useItsPlaylist(a, item)) return;
                a.guide(sid, title);
            } else if (w == 3) VirtualCategories.addDialog(a, item);
        }).show();
    }

    /** Remembers the last non-tuner playlist the app switched to. */
    static void remember(MainActivity a, JSONObject p) {
        if (p != null && !Library.isHdhr(p)) a.prefs.edit().putString(MAIN_PLAYLIST, p.optString("id")).apply();
    }

    /**
     * When a tuner is active only because an antenna channel played, Home, Movies and Series go back to the main
     * playlist. Nothing changes when the tuner is the only source.
     */
    static void restoreMain(MainActivity a) {
        if (!a.library.activeIsHdhr()) return;
        JSONObject main = a.library.find(a.prefs.getString(MAIN_PLAYLIST, ""));
        if (main == null) {
            JSONArray all = a.library.all();
            for (int i = 0; i < all.length() && main == null; i++) { JSONObject p = all.optJSONObject(i); if (p != null && !Library.isHdhr(p)) main = p; }
        }
        if (main != null) a.switchPlaylist(main);
    }
}
