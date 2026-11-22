package com.wygtv.tv;

import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Hold OK on a channel in a guide (TV Guide or the Live TV guide): Favorites, Hide / Unhide and Add to channel group,
 * saved in the channel's own playlist so channels from other playlists work too.
 */
final class GuideOptions {
    private GuideOptions() {}

    /** What changed, so the guide can drop the row when it no longer belongs in the list. */
    interface Changed { void run(boolean hidden, boolean favorite); }

    static void show(MainActivity a, JSONObject ch, Changed changed) { show(a, ch, null, changed); }

    /** group: a channel group (◆) the list is showing, which adds "Remove from" it; null otherwise. */
    static void show(MainActivity a, JSONObject ch, String group, Changed changed) {
        if (ch == null) return;
        String pid = ch.optString("playlist");
        JSONObject pl = pid.isEmpty() || pid.equals(a.library.activeId()) ? null : a.library.find(pid);
        if (!pid.isEmpty() && !pid.equals(a.library.activeId()) && pl == null) {
            Toast.makeText(a, "This channel's playlist was removed.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String pre = pl == null ? a.accountPrefix() : Library.prefixOf(pl);
        final String sid = ch.optString("stream_id"), title = ch.optString("name", "Channel");
        String ext0 = ch.optString("container_extension", "ts");
        final String ext = ext0.isEmpty() ? "ts" : ext0;
        final boolean fav = a.isFavoriteIn(pre, "live", sid), hidden = isHidden(a, pre, sid);
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add(fav ? "★  Remove from Favorites" : "☆  Add to Favorites");
        items.add(hidden ? "◉  Unhide channel" : "◌  Hide channel");
        items.add("◆  Add to channel group…");
        if (group != null) items.add("✕  Remove from \"" + VirtualCategories.name(a, group) + "\"");
        items.add("Close");
        new Dialogs.Builder(a).setTitle(title)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        boolean added = a.toggleFavoriteIn(pre, "live", sid, title, ext);
                        Toast.makeText(a, added ? "★ Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
                        if (changed != null) changed.run(isHidden(a, pre, sid), added);
                    } else if (w == 1) {
                        boolean nowHidden = toggleHidden(a, pre, sid);
                        Toast.makeText(a, nowHidden ? "Channel hidden. Unhide it in Settings › Manage playlists." : "Channel shown again", Toast.LENGTH_LONG).show();
                        if (changed != null) changed.run(nowHidden, a.isFavoriteIn(pre, "live", sid));
                    } else if (w == 2) {
                        VirtualCategories.addDialog(a, ch);
                    } else if (w == 3 && group != null) {
                        VirtualCategories.removeFrom(a, group, pid.isEmpty() ? a.library.activeId() : pid, sid);
                        a.loadItems(group);
                    }
                }).show();
    }

    private static boolean isHidden(MainActivity a, String pre, String sid) {
        return a.prefs.getStringSet(pre + "hidden_channels_live", new HashSet<>()).contains(sid);
    }

    /** Same pref the Live TV lists and Settings › Hide and show channels use. Returns true when now hidden. */
    private static boolean toggleHidden(MainActivity a, String pre, String sid) {
        String key = pre + "hidden_channels_live";
        Set<String> h = new HashSet<>(a.prefs.getStringSet(key, new HashSet<>()));
        boolean hide = !h.remove(sid);
        if (hide) h.add(sid);
        a.prefs.edit().putStringSet(key, h).apply();
        return hide;
    }
}
