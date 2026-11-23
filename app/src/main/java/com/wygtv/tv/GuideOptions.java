package com.wygtv.tv;

import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Hold OK on a channel in a guide (TV Guide or the Live TV guide): Favorites, Hide / Unhide and Add to channel group;
 * OK while a channel plays: Restart, Favorites and channel groups (no Hide),
 * saved in the channel's own playlist so channels from other playlists work too.
 */
final class GuideOptions {
    private GuideOptions() {}

    /** What changed, so the guide can drop the row when it no longer belongs in the list. */
    interface Changed { void run(boolean hidden, boolean favorite); }

    static void show(MainActivity a, JSONObject ch, Changed changed) { show(a, ch, null, changed); }

    // What each item does.
    private static final int RESTART = 0, FAV = 1, HIDE = 2, ADD = 3, NEW = 4, REMOVE = 5, CLOSE = 6;

    /** group: a channel group (◆) the list is showing, which adds "Remove from" it; null otherwise. */
    static android.app.AlertDialog show(MainActivity a, JSONObject ch, String group, Changed changed) {
        return show(a, ch, group, changed, null);
    }

    /** OK while a channel plays: Restart at the top, and no Hidden switch. */
    static android.app.AlertDialog showInPlayer(MainActivity a, JSONObject ch, Runnable restart) {
        return show(a, ch, null, null, restart);
    }

    private static android.app.AlertDialog show(MainActivity a, JSONObject ch, String group, Changed changed, Runnable restart) {
        if (ch == null) return null;
        String pid = ch.optString("playlist");
        JSONObject pl = pid.isEmpty() || pid.equals(a.library.activeId()) ? null : a.library.find(pid);
        if (!pid.isEmpty() && !pid.equals(a.library.activeId()) && pl == null) {
            Toast.makeText(a, "This channel's playlist was removed.", Toast.LENGTH_SHORT).show();
            return null;
        }
        final String pre = pl == null ? a.accountPrefix() : Library.prefixOf(pl);
        final String sid = ch.optString("stream_id"), title = ch.optString("name", "Channel");
        String ext0 = ch.optString("container_extension", "ts");
        final String ext = ext0.isEmpty() ? "ts" : ext0;
        final boolean fav = a.isFavoriteIn(pre, "live", sid), hidden = isHidden(a, pre, sid);
        final boolean inPlayer = restart != null;
        java.util.List<String> items = new java.util.ArrayList<>();
        final java.util.List<Integer> acts = new java.util.ArrayList<>();
        if (inPlayer) { items.add("⟲  Restart"); acts.add(RESTART); }
        items.add(fav ? "★  Remove from Favorites" : "☆  Add to Favorites"); acts.add(FAV);
        if (!inPlayer) { items.add(hideLabel(hidden)); acts.add(HIDE); }
        items.add("◆  Add to channel group…"); acts.add(ADD);
        items.add("＋  New channel group…"); acts.add(NEW);
        if (group != null) { items.add("✕  Remove from \"" + VirtualCategories.name(a, group) + "\""); acts.add(REMOVE); }
        items.add("Close"); acts.add(CLOSE);
        final String[] labels = items.toArray(new String[0]);
        android.app.AlertDialog dlg = new Dialogs.Builder(a).setTitle(title)
                .setItems(labels, (d, which) -> {
                    int w = acts.get(which);
                    if (w == RESTART) {
                        restart.run();
                    } else if (w == FAV) {
                        boolean added = a.toggleFavoriteIn(pre, "live", sid, title, ext);
                        Toast.makeText(a, added ? "★ Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
                        if (changed != null) changed.run(isHidden(a, pre, sid), added);
                    } else if (w == ADD) {
                        VirtualCategories.addDialog(a, ch);
                    } else if (w == NEW) {
                        VirtualCategories.addToNew(a, ch);
                    } else if (w == REMOVE && group != null) {
                        VirtualCategories.removeFrom(a, group, pid.isEmpty() ? a.library.activeId() : pid, sid);
                        a.loadItems(group);
                    }
                }).show();
        final int hidePos = acts.indexOf(HIDE);
        if (hidePos < 0) return dlg;
        // "Hidden" is a switch: OK flips it and the pop-up stays open, so it can be flipped straight back.
        android.widget.ListView lv = dlg.getListView();
        if (lv == null) return dlg;
        final android.widget.AdapterView.OnItemClickListener others = lv.getOnItemClickListener();
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos != hidePos) { if (others != null) others.onItemClick(parent, view, pos, id); return; }
            boolean nowHidden = toggleHidden(a, pre, sid);
            labels[hidePos] = hideLabel(nowHidden);
            if (parent.getAdapter() instanceof android.widget.BaseAdapter) ((android.widget.BaseAdapter) parent.getAdapter()).notifyDataSetChanged();
            if (changed != null) changed.run(nowHidden, a.isFavoriteIn(pre, "live", sid));
        });
        return dlg;
    }

    private static String hideLabel(boolean hidden) { return hidden ? "☑  Hidden: on  (OK to show)" : "☐  Hidden: off  (OK to hide)"; }

    /** True when this channel is hidden in its own playlist. Guides show such a row dimmed until the list reloads. */
    static boolean isHidden(MainActivity a, JSONObject ch) {
        if (ch == null) return false;
        String pid = ch.optString("playlist");
        JSONObject pl = pid.isEmpty() || pid.equals(a.library.activeId()) ? null : a.library.find(pid);
        if (pl == null && !pid.isEmpty() && !pid.equals(a.library.activeId())) return false;
        return isHidden(a, pl == null ? a.accountPrefix() : Library.prefixOf(pl), ch.optString("stream_id"));
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
