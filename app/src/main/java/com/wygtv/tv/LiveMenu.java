package com.wygtv.tv;

import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The Live TV menu on the left, in two tiers. The first tier has Favorites, Search, your own added categories and
 * your added playlists. Picking a playlist opens its provider categories as the second tier. Whatever is picked
 * shows in the mini TV guide on the right half.
 */
final class LiveMenu {
    static final String TOP = "top", CATEGORIES = "categories";
    /** Which tier is showing; kept while you play a channel so Back from the player returns to the same place. */
    static String tier = TOP;

    private LiveMenu() { }

    /** Called by browse("live") once the screen is built. */
    static void start(MainActivity a) {
        a.request("get_live_categories", o -> {
            a.categories = o.optJSONArray("list");
            if (a.categories == null) a.categories = new JSONArray();
            if (tier.equals(CATEGORIES)) a.showCategories(); else top(a);
        }, "Unable to load categories");
    }

    /** First tier: Favorites, Search, added categories, added playlists. */
    static void top(MainActivity a) {
        tier = TOP;
        a.side.removeAllViews();
        a.button(a.liveOnly() ? "← TV Guide" : "← Home", a.side, a::home);
        a.button("★ Favorites", a.side, () -> a.loadItems("fav"));
        a.sideFixed = 1;
        a.button("⌕ Search", a.side, a::searchLive);
        Antenna.addMenuEntries(a);
        JSONArray mine = VirtualCategories.all(a);
        for (int i = 0; i < mine.length(); i++) {
            JSONObject c = mine.optJSONObject(i);
            if (c == null) continue;
            String vid = c.optString("id");
            a.button("◆ " + c.optString("name"), a.side, () -> a.loadItems(vid));
        }
        a.button("+ New category", a.side, () -> VirtualCategories.create(a, c -> { a.resumeCategory = c.optString("id"); top(a); }));
        JSONArray pls = a.library.all();
        String active = a.library.activeId();
        for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p == null || Library.isHdhr(p)) continue; // tuners show as Antenna TV above
            boolean on = p.optString("id").equals(active);
            a.button((on ? "▤ " : "▢ ") + Library.nameOf(p) + "  ›", a.side, () -> openPlaylist(a, p));
        }
        a.button("⚙ Manage playlists", a.side, a::playlists);
        String resume = a.resumeCategory;
        a.resumeCategory = null;
        a.loadItems(resume != null && (VirtualCategories.isVirtual(resume) || Antenna.isAntenna(resume)) ? resume : "fav");
    }

    /** Second tier: the playlist's provider categories (switching to it first if needed). */
    static void openPlaylist(MainActivity a, JSONObject p) {
        tier = CATEGORIES;
        if (p.optString("id").equals(a.library.activeId()) && a.categories.length() > 0) {
            a.showCategories();
            if (a.side.getChildCount() > 1) a.side.getChildAt(1).requestFocus();
            return;
        }
        a.switchPlaylist(p);
        a.browse("live");
    }

    /** Back in Live TV: from the categories tier go up to the first tier. False when already at the top. */
    static boolean back(MainActivity a) {
        if (tier.equals(TOP)) return false;
        a.resumeCategory = null;
        top(a);
        return true;
    }

    /** Favorites of every saved playlist, current one first, as mini-guide rows. */
    static void favorites(MainActivity a) {
        a.listGeneration++;
        a.category = "fav";
        Map<String, String> icons = new HashMap<>();
        if (a.activeLive != null) for (int i = 0; i < a.activeLive.length(); i++) {
            JSONObject it = a.activeLive.optJSONObject(i);
            if (it != null) icons.put(it.optString("stream_id"), it.optString("stream_icon"));
        }
        Map<String, ?> stored = a.prefs.getAll();
        JSONArray out = new JSONArray();
        JSONArray pls = a.library.all();
        String active = a.library.activeId();
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < pls.length(); i++) {
            JSONObject p = pls.optJSONObject(i);
            if (p == null || p.optString("id").equals(active) != (pass == 0)) continue;
            String pre = Library.prefixOf(p), head = pre + "fav_live_";
            for (Map.Entry<String, ?> e : stored.entrySet()) {
                if (!e.getKey().startsWith(head)) continue;
                String id = e.getKey().substring(head.length()), title = String.valueOf(e.getValue());
                if (title.isEmpty()) continue;
                Object ext = stored.get(pre + "ext_live_" + id);
                try {
                    JSONObject it = new JSONObject().put("stream_id", id).put("name", title)
                            .put("container_extension", ext == null || String.valueOf(ext).isEmpty() ? "ts" : String.valueOf(ext));
                    if (pass == 0) { String icon = icons.get(id); if (icon != null) it.put("stream_icon", icon); }
                    else it.put("playlist", p.optString("id"));
                    out.put(it);
                } catch (JSONException ignored) { }
            }
        }
        a.items = out;
        if (out.length() == 0) Toast.makeText(a, "No favorites yet. Hold OK on a channel and choose Add Favorite.", Toast.LENGTH_LONG).show();
        a.showItems();
    }

    /** OK on a favorite: switch to its playlist if it's another one, then play, zapping through that playlist's favorites. */
    static void playFavorite(MainActivity a, JSONObject item) {
        String pid = item.optString("playlist");
        JSONArray zap = new JSONArray();
        for (int i = 0; i < a.items.length(); i++) {
            JSONObject it = a.items.optJSONObject(i);
            if (it != null && it.optString("playlist").equals(pid)) zap.put(it);
        }
        if (!VirtualCategories.useItsPlaylist(a, item)) return;
        a.zapList = zap;
        String ext = item.optString("container_extension", "ts");
        a.play("live", item.optString("stream_id"), ext.isEmpty() ? "ts" : ext, item.optString("name", "Untitled"));
    }
}
