package com.wygtv.tv;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;

/**
 * Playlists, first-time setup, Settings and Recently watched: the setup side of the app,
 * organised the way IPTVnator does it (a playlist manager you start from, then a settings page).
 */
final class Menus {

    static final String[] START_KEYS = {"home", "live", "guide", "favorites", "recent", "last"};
    static final String[] START_LABELS = {"Home", "Live TV", "TV Guide", "Favorites", "Recently watched", "Last channel watched"};
    static final String[] FORMAT_KEYS = {"auto", "ts", "m3u8"};
    static final String[] FORMAT_LABELS = {"Automatic (provider default)", "MPEG-TS (.ts)", "HLS (.m3u8)"};

    private Menus() { }

    // ------------------------------------------------------------------ first-time setup

    static void setup(MainActivity a) {
        a.mode = "setup";
        a.screenGeneration++;
        FrameLayout scene = new FrameLayout(a);
        scene.setBackground(new SteampunkBackdrop(a.getResources(), R.drawable.steampunk_home, true));
        a.setContentView(scene);
        LinearLayout center = new LinearLayout(a);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.setPadding(a.dp(80), a.dp(20), a.dp(80), a.dp(20));
        scene.addView(center, new FrameLayout.LayoutParams(-1, -1));
        TextView small = a.label("WELCOME TO WYGTV", 15);
        small.setTextColor(Theme.ACCENT);
        small.setLetterSpacing(.18f);
        small.setGravity(Gravity.CENTER);
        center.addView(small, new LinearLayout.LayoutParams(-1, a.dp(35)));
        TextView heading = a.label("Add your first playlist", 30);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        center.addView(heading, new LinearLayout.LayoutParams(-1, a.dp(56)));
        TextView hint = a.label("Pick how your provider gave you access, or restore a WygTV backup. You can add more playlists later from the menu.", 16);
        hint.setTextColor(Theme.MUTED);
        hint.setGravity(Gravity.CENTER);
        center.addView(hint, new LinearLayout.LayoutParams(-1, a.dp(40)));
        LinearLayout tiles = new LinearLayout(a);
        tiles.setGravity(Gravity.CENTER);
        center.addView(tiles, new LinearLayout.LayoutParams(-1, a.dp(170)));
        TextView xtream = a.homeTile("⚙", "Xtream Codes\nserver + username", tiles, () -> xtreamForm(a, null, null));
        a.homeTile("☰", "M3U link\nplaylist URL", tiles, () -> m3uForm(a, null));
        a.homeTile("↺", "Restore\nfrom a backup", tiles, () -> Backup.restore(a));
        for (int i = 0; i < tiles.getChildCount(); i++) ((TextView) tiles.getChildAt(i)).setTextSize(21);
        xtream.requestFocus();
    }

    // ------------------------------------------------------------------ playlists manager

    static void playlists(MainActivity a) {
        a.mode = "playlists";
        a.shell("PLAYLISTS");
        LinearLayout list = column(a);
        note(a, list, "Press OK on a playlist to watch it, edit it, refresh it or delete it.");
        JSONArray all = a.library.all();
        String active = a.library.activeId();
        View focus = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p == null) continue;
            boolean on = p.optString("id").equals(active);
            TextView row = a.button((on ? "●  " : "○  ") + p.optString("title", "Playlist") + (on ? "   (watching)" : "") + "\n      " + describe(p), list, () -> playlistActions(a, p));
            row.setTextSize(19);
            if (on) row.setTextColor(Theme.ACCENT);
            if (focus == null || on) focus = row;
        }
        TextView addX = a.button("+  Add an Xtream Codes login", list, () -> xtreamForm(a, null, null));
        a.button("+  Add an M3U playlist link", list, () -> m3uForm(a, null));
        (focus == null ? addX : focus).requestFocus();
    }

    static String describe(JSONObject p) {
        StringBuilder s = new StringBuilder();
        if (Library.M3U.equals(p.optString("type"))) {
            s.append("M3U playlist");
            int n = p.optInt("count", -1);
            if (n >= 0) s.append("  •  ").append(n).append(n == 1 ? " channel" : " channels");
            s.append("  •  ").append(hostOf(p.optString("url")));
        } else {
            s.append("Xtream Codes  •  ").append(hostOf(p.optString("host"))).append("  •  ").append(p.optString("user"));
            String exp = expiryText(p.optString("expiry"));
            if (!exp.isEmpty()) s.append("  •  ").append(exp);
        }
        if (!p.optString("epg").isEmpty()) s.append("  •  own TV guide");
        return s.toString();
    }

    static String hostOf(String url) {
        try { String h = new URI(url).getHost(); return h == null ? url : h; } catch (Exception e) { return url; }
    }

    /** Xtream gives a unix time (or null/"Unknown"); show it as a date. */
    static String expiryText(String exp) {
        if (exp == null || exp.isEmpty() || exp.equals("null") || exp.equalsIgnoreCase("unknown")) return "";
        try {
            long t = Long.parseLong(exp.trim()) * 1000L;
            return "expires " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(t));
        } catch (NumberFormatException e) {
            return "expires " + exp;
        }
    }

    private static void playlistActions(MainActivity a, JSONObject p) {
        boolean on = p.optString("id").equals(a.library.activeId());
        String[] items = {on ? "Go to Home" : "Watch this playlist", "Edit", "Refresh now", "Delete"};
        new AlertDialog.Builder(a).setTitle(p.optString("title", "Playlist")).setItems(items, (d, which) -> {
            if (which == 0) { a.switchPlaylist(p); a.home(); }
            else if (which == 1) { if (Library.M3U.equals(p.optString("type"))) m3uForm(a, p); else xtreamForm(a, p, null); }
            else if (which == 2) refresh(a, p);
            else new AlertDialog.Builder(a).setTitle("Delete “" + p.optString("title", "Playlist") + "”?")
                        .setMessage("Its favorites stay saved, so they come back if you add the same playlist again.")
                        .setPositiveButton("Delete", (d2, w2) -> {
                            a.library.remove(p.optString("id"));
                            a.forgetPlaylistData();
                            if (a.library.isEmpty()) setup(a); else playlists(a);
                        }).setNegativeButton("Cancel", null).show();
        }).show();
    }

    private static void refresh(MainActivity a, JSONObject p) {
        Toast.makeText(a, "Refreshing " + p.optString("title", "playlist") + "…", Toast.LENGTH_SHORT).show();
        final int gen = a.screenGeneration;
        new Thread(() -> {
            String msg;
            try {
                if (Library.M3U.equals(p.optString("type"))) {
                    M3u.Playlist m = a.library.downloadM3u(p.optString("url"));
                    p.put("count", m.channels.size());
                    msg = m.channels.size() + " channels loaded.";
                } else {
                    JSONObject info = new JSONObject(Library.download(p.optString("host") + "/player_api.php?username=" + a.enc(p.optString("user"))
                            + "&password=" + a.enc(p.optString("pass")), 20000)).optJSONObject("user_info");
                    if (info == null || info.optInt("auth", 0) != 1) throw new IllegalStateException("login rejected");
                    p.put("expiry", info.optString("exp_date", ""));
                    msg = "Account is " + info.optString("status", "active").toLowerCase(java.util.Locale.ROOT) + ".";
                }
                a.library.put(p);
                if (p.optString("id").equals(a.library.activeId())) a.library.activate(p);
            } catch (Exception e) {
                msg = "Couldn't refresh. Check the address, account and network.";
            }
            final String text = msg;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                Toast.makeText(a, text, Toast.LENGTH_LONG).show();
                if (gen == a.screenGeneration && a.mode.equals("playlists")) playlists(a);
            });
        }).start();
    }

    // ------------------------------------------------------------------ add / edit forms

    /** Xtream login form. {@code prefill} = {server, user, pass} from a pasted get.php link. */
    static void xtreamForm(MainActivity a, JSONObject existing, String[] prefill) {
        a.mode = "login";
        a.shell(existing == null ? "ADD XTREAM CODES LOGIN" : "EDIT PLAYLIST");
        LinearLayout form = column(a);
        form.setPadding(a.dp(80), a.dp(4), a.dp(80), 0);
        EditText title = a.field(form, "Playlist name (e.g. Living room)", existing == null ? "" : existing.optString("title"));
        EditText host = a.field(form, "Server URL (http://host:port) or a full get.php link", prefill != null ? prefill[0] : existing == null ? "" : existing.optString("host"));
        EditText user = a.field(form, "Username", prefill != null ? prefill[1] : existing == null ? "" : existing.optString("user"));
        EditText pass = a.field(form, "Password", prefill != null ? prefill[2] : existing == null ? "" : existing.optString("pass"));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText epg = a.field(form, "TV guide (XMLTV) link, optional: leave empty to use the provider's", existing == null ? "" : existing.optString("epg"));
        a.button("Save & connect", form, () -> {
            String h = host.getText().toString().trim();
            String[] fromLink = M3u.xtreamLogin(h);
            if (fromLink != null) { h = fromLink[0]; user.setText(fromLink[1]); pass.setText(fromLink[2]); host.setText(h); }
            h = h.replaceAll("/+$", "");
            String u = user.getText().toString().trim(), pw = pass.getText().toString();
            if (!XtreamProtocol.validLogin(h, u, pw)) { alert(a, "Enter an http:// or https:// server address, a username and a password."); return; }
            final String server = h;
            a.fetch(h + "/player_api.php?username=" + a.enc(u) + "&password=" + a.enc(pw), data -> {
                JSONObject info = data.optJSONObject("user_info");
                if (info == null || info.optInt("auth", 0) != 1 || !"Active".equalsIgnoreCase(info.optString("status"))) {
                    alert(a, "Login rejected or account inactive. Nothing was saved.");
                    return;
                }
                JSONObject p = existing != null ? existing : Library.newPlaylist(Library.XTREAM);
                String name = title.getText().toString().trim();
                try {
                    p.put("title", name.isEmpty() ? hostOf(server) : name).put("host", server).put("user", u).put("pass", pw)
                            .put("expiry", info.optString("exp_date", "")).put("epg", epg.getText().toString().trim());
                } catch (JSONException ignored) { }
                a.library.put(p);
                a.switchPlaylist(p);
                Toast.makeText(a, "Connected to " + p.optString("title"), Toast.LENGTH_SHORT).show();
                a.home();
            }, "Connection failed");
        });
        a.button("Cancel", form, () -> a.leaveForm());
        (existing == null ? title : host).requestFocus();
    }

    static void m3uForm(MainActivity a, JSONObject existing) {
        a.mode = "login";
        a.shell(existing == null ? "ADD M3U PLAYLIST" : "EDIT PLAYLIST");
        LinearLayout form = column(a);
        form.setPadding(a.dp(80), a.dp(4), a.dp(80), 0);
        EditText title = a.field(form, "Playlist name (e.g. Free channels)", existing == null ? "" : existing.optString("title"));
        EditText url = a.field(form, "M3U / M3U8 link (http://…)", existing == null ? "" : existing.optString("url"));
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText epg = a.field(form, "TV guide (XMLTV) link, optional: leave empty to use the playlist's own", existing == null ? "" : existing.optString("epg"));
        note(a, form, "M3U playlists give you Live TV, grouped the way the playlist groups them.");
        a.button("Save & load", form, () -> {
            String link = url.getText().toString().trim();
            if (!(link.startsWith("http://") || link.startsWith("https://"))) { alert(a, "Enter a link that starts with http:// or https://."); return; }
            String[] xt = M3u.xtreamLogin(link);
            if (xt != null && existing == null) {
                new AlertDialog.Builder(a).setTitle("This is an Xtream Codes link")
                        .setMessage("Adding it as Xtream Codes also gives you Movies, Series and the provider's TV guide.")
                        .setPositiveButton("Add as Xtream", (d, w) -> xtreamForm(a, null, xt))
                        .setNegativeButton("Keep as M3U", (d, w) -> loadM3u(a, existing, title.getText().toString().trim(), link, epg.getText().toString().trim()))
                        .show();
                return;
            }
            loadM3u(a, existing, title.getText().toString().trim(), link, epg.getText().toString().trim());
        });
        a.button("Cancel", form, () -> a.leaveForm());
        (existing == null ? title : url).requestFocus();
    }

    private static void loadM3u(MainActivity a, JSONObject existing, String name, String link, String epg) {
        a.status.setText("Downloading playlist…");
        Toast.makeText(a, "Downloading playlist…", Toast.LENGTH_SHORT).show();
        final int gen = a.screenGeneration;
        new Thread(() -> {
            M3u.Playlist m = null;
            try { m = a.library.downloadM3u(link); } catch (Exception ignored) { }
            final M3u.Playlist loaded = m;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || gen != a.screenGeneration) return;
                if (loaded == null || loaded.channels.isEmpty()) {
                    a.status.setText("");
                    alert(a, loaded == null ? "Couldn't download a playlist from that link. Check it and your network." : "That playlist has no channels.");
                    return;
                }
                JSONObject p = existing != null ? existing : Library.newPlaylist(Library.M3U);
                try {
                    p.put("title", name.isEmpty() ? hostOf(link) : name).put("url", link).put("epg", epg).put("count", loaded.channels.size());
                } catch (JSONException ignored) { }
                a.library.put(p);
                a.switchPlaylist(p);
                Toast.makeText(a, loaded.channels.size() + " channels in " + loaded.groups.size() + " groups", Toast.LENGTH_LONG).show();
                a.home();
            });
        }).start();
    }

    // ------------------------------------------------------------------ settings

    static void settings(MainActivity a) { settings(a, 0); }

    static void settings(MainActivity a, int focusIndex) {
        a.mode = "settings";
        a.shell("SETTINGS");
        LinearLayout list = column(a);
        boolean m3u = a.library.activeIsM3u();
        JSONObject active = a.library.find(a.library.activeId());

        row(a, list, "Playlist", active == null ? "none" : active.optString("title") + "  (" + (m3u ? "M3U" : "Xtream Codes") + ")", () -> a.playlists());
        final int modeRow = list.getChildCount();
        boolean liveOnly = a.liveOnly();
        row(a, list, "App mode", liveOnly ? "Live TV only  •  opens on the TV guide; press OK for the full app" : "Full  •  press OK for Live TV only (opens on the TV guide, hides Movies and Series)", () -> {
            a.prefs.edit().putBoolean("live_only", !a.liveOnly()).apply();
            Toast.makeText(a, a.liveOnly() ? "Live TV only. Settings stays in the side menu to switch back." : "Full app is back.", Toast.LENGTH_LONG).show();
            settings(a, modeRow);
        });
        final int themeRow = list.getChildCount();
        row(a, list, "Theme", Theme.LABELS[Theme.indexOf(a.prefs.getString("theme", Theme.DEFAULT))], () ->
                choose(a, "Theme", Theme.LABELS, Theme.indexOf(a.prefs.getString("theme", Theme.DEFAULT)), i -> {
                    a.prefs.edit().putString("theme", Theme.KEYS[i]).apply();
                    a.applyTheme();
                    settings(a, themeRow);
                }));
        final int startRow = list.getChildCount();
        if (!liveOnly) row(a, list, "Open at start-up", START_LABELS[indexOf(START_KEYS, a.prefs.getString("start_screen", "home"))], () ->
                choose(a, "Open at start-up", START_LABELS, indexOf(START_KEYS, a.prefs.getString("start_screen", "home")), i -> {
                    a.prefs.edit().putString("start_screen", START_KEYS[i]).apply();
                    settings(a, startRow);
                }));
        final int formatRow = list.getChildCount();
        if (!m3u) {
            row(a, list, "Live stream format", FORMAT_LABELS[indexOf(FORMAT_KEYS, a.prefs.getString("live_format", "auto"))], () ->
                    choose(a, "Live stream format (try HLS if channels stutter or won't start)", FORMAT_LABELS, indexOf(FORMAT_KEYS, a.prefs.getString("live_format", "auto")), i -> {
                        a.prefs.edit().putString("live_format", FORMAT_KEYS[i]).apply();
                        settings(a, formatRow);
                    }));
        }
        final int guideRow = list.getChildCount();
        String custom = a.saved("epg_url");
        String guideState = a.library.guideStatus();
        String source = !custom.isEmpty() ? hostOf(custom) + " (your link)" : m3u ? (a.library.guideUrl().isEmpty() ? "none in this playlist" : hostOf(a.library.guideUrl()) + " (from the playlist)") : "the provider's guide";
        row(a, list, "TV guide source", source + (guideState.isEmpty() ? "" : "  •  " + guideState), () -> guideSource(a, active, guideRow));
        if (!custom.isEmpty() || m3u) row(a, list, "Reload TV guide", "download the guide again now", () -> {
            new Thread(a.library::dropGuide).start();
            Toast.makeText(a, "The guide will reload the next time it's shown.", Toast.LENGTH_SHORT).show();
        });
        final int hiddenRow = list.getChildCount();
        int hidden = a.hiddenCount();
        row(a, list, "Hidden categories and channels", hidden == 0 ? "nothing hidden" : hidden + " hidden  •  press OK to show them all again", () -> {
            if (a.hiddenCount() == 0) { Toast.makeText(a, "Nothing is hidden.", Toast.LENGTH_SHORT).show(); return; }
            new AlertDialog.Builder(a).setTitle("Show everything again?").setMessage("Every hidden category and channel in this playlist comes back.")
                    .setPositiveButton("Show all", (d, w) -> { a.unhideAll(); settings(a, hiddenRow); }).setNegativeButton("Cancel", null).show();
        });
        final int recentRow = list.getChildCount();
        int recent = a.library.recent().length();
        row(a, list, "Recently watched", recent == 0 ? "empty" : recent + " items  •  press OK to clear", () -> {
            if (a.library.recent().length() == 0) return;
            new AlertDialog.Builder(a).setTitle("Clear Recently watched?").setPositiveButton("Clear", (d, w) -> { a.library.clearRecent(); settings(a, recentRow); })
                    .setNegativeButton("Cancel", null).show();
        });
        row(a, list, "Back up playlists", "save playlists, favorites, your categories, My List, hidden items and all settings to a dated WygTV backup file", () -> Backup.backup(a));
        row(a, list, "Restore from a backup", "reload everything from a WygTV backup file", () -> Backup.restore(a));
        row(a, list, "Check for updates", "WygTV " + Updater.installedName(a) + "  •  press OK to look for a newer version and install it over this one", () -> Updater.check(a, true));
        final int autoRow = list.getChildCount();
        boolean auto = a.prefs.getBoolean(Updater.PREF_AUTO, true);
        row(a, list, "Look for updates at start-up", auto ? "On  •  offers new versions when WygTV opens" : "Off", () -> {
            a.prefs.edit().putBoolean(Updater.PREF_AUTO, !a.prefs.getBoolean(Updater.PREF_AUTO, true)).apply();
            settings(a, autoRow);
        });
        String version = Updater.installedName(a);
        final String about = "WygTV " + version + " for Android TV";
        row(a, list, "About", about, () -> Toast.makeText(a, about, Toast.LENGTH_SHORT).show());
        int i = Math.max(0, Math.min(focusIndex, list.getChildCount() - 1));
        list.getChildAt(i).requestFocus();
    }

    private static void guideSource(MainActivity a, JSONObject active, int focusRow) {
        if (active == null) return;
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setHint("http://…/guide.xml or .xml.gz");
        input.setText(active.optString("epg"));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        boolean m3u = Library.M3U.equals(active.optString("type"));
        new AlertDialog.Builder(a).setTitle("TV guide (XMLTV) link for this playlist")
                .setMessage(m3u ? "Leave empty to use the guide the playlist names, if it has one." : "Leave empty to use the provider's own guide.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String link = input.getText().toString().trim();
                    if (!link.isEmpty() && !(link.startsWith("http://") || link.startsWith("https://"))) { alert(a, "Enter a link that starts with http:// or https://."); return; }
                    setGuide(a, active, link, focusRow);
                })
                .setNeutralButton("Use default", (d, w) -> setGuide(a, active, "", focusRow))
                .setNegativeButton("Cancel", null).show();
    }

    private static void setGuide(MainActivity a, JSONObject active, String link, int focusRow) {
        try { active.put("epg", link); } catch (JSONException ignored) { }
        a.library.put(active);
        a.library.activate(active);
        new Thread(a.library::dropGuide).start();
        settings(a, focusRow);
    }

    // ------------------------------------------------------------------ recently watched

    static void recent(MainActivity a) {
        a.mode = "recent";
        a.playerReturn = null;
        a.shell("RECENTLY WATCHED");
        LinearLayout list = column(a);
        JSONArray all = a.library.recent();
        if (all.length() == 0) {
            note(a, list, "Nothing yet. Channels, movies and episodes you watch show up here.");
            a.button("▣  Go to Live TV", list, () -> a.browse("live")).requestFocus();
            return;
        }
        note(a, list, "OK plays it again. Hold OK to remove it from this list.");
        JSONArray live = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null || !o.optString("type").equals("live")) continue;
            try { live.put(new JSONObject().put("stream_id", o.optString("id")).put("name", o.optString("title")).put("container_extension", o.optString("ext", "ts"))); }
            catch (JSONException ignored) { }
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("type"), id = o.optString("id"), title = o.optString("title", "Untitled"), ext = o.optString("ext");
            String icon = type.equals("live") ? "▣" : type.equals("vod") ? "▶" : "▤";
            String when = DateUtils.getRelativeTimeSpanString(o.optLong("at", now), now, DateUtils.MINUTE_IN_MILLIS).toString();
            TextView row = a.button(icon + "  " + (a.isFavorite(type, id) ? "★ " : "") + title + "      " + when, list, () -> {
                if (type.equals("live")) a.zapList = live;
                a.play(type, id, ext.isEmpty() ? (type.equals("live") ? "ts" : "mp4") : ext, title);
            });
            row.setTag(id);
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(a).setTitle(title).setItems(new String[]{"Remove from this list", "Cancel"}, (d, w) -> {
                    if (w == 0) { a.library.removeRecent(type, id); recent(a); }
                }).show();
                return true;
            });
        }
        a.button("Clear the list", list, () -> new AlertDialog.Builder(a).setTitle("Clear Recently watched?")
                .setPositiveButton("Clear", (d, w) -> { a.library.clearRecent(); recent(a); }).setNegativeButton("Cancel", null).show());
        if (!a.focusResume(list)) list.getChildAt(1).requestFocus();
    }

    // ------------------------------------------------------------------ helpers

    private static LinearLayout column(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        a.body.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(a.dp(40), 0, a.dp(40), 0);
        sv.addView(list);
        return list;
    }

    private static void note(MainActivity a, LinearLayout parent, String text) {
        TextView t = a.label(text, 15);
        t.setTextColor(Theme.MUTED);
        t.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(8));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void row(MainActivity a, LinearLayout list, String name, String value, Runnable action) {
        TextView v = a.button(name + "\n      " + value, list, action);
        v.setTextSize(19);
    }

    interface Pick { void run(int index); }

    private static void choose(MainActivity a, String title, String[] labels, int current, Pick pick) {
        new AlertDialog.Builder(a).setTitle(title).setSingleChoiceItems(labels, current, (d, which) -> { d.dismiss(); pick.run(which); })
                .setNegativeButton("Cancel", null).show();
    }

    static int indexOf(String[] keys, String key) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(key)) return i;
        return 0;
    }

    static void alert(MainActivity a, String message) {
        new AlertDialog.Builder(a).setMessage(message).setPositiveButton("OK", null).show();
    }
}
