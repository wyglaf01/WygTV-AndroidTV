package com.wygtv.tv;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saved playlists (Xtream Codes or M3U), the active playlist's data, "Recently watched" and the
 * optional XMLTV guide. For M3U playlists it answers the same player_api actions the rest of the app
 * already uses for Xtream (get_live_categories, get_live_streams, get_short_epg), so every screen works
 * for both kinds of playlist.
 */
final class Library {
    static final String XTREAM = "xtream", M3U = "m3u";
    private static final long GUIDE_MAX_AGE = 12 * 3600_000L;

    private final MainActivity a;
    private final SharedPreferences prefs;

    private M3u.Playlist m3u;
    private String m3uKey = "";
    // Read without a lock by the Settings screen, so it never waits on a guide download.
    private volatile Xmltv.Guide guide;
    private volatile String guideKey = "";
    private long guideLoadedAt;
    private volatile String guideError = "";
    private Map<String, String[]> xtreamEpgIds;
    private String xtreamEpgKey = "";
    /** Guide downloads can take minutes; they hold this lock, never the playlist one. */
    private final Object guideLock = new Object();
    private final Object m3uLoadLock = new Object();

    /** Supplemental XMLTV guides set in Settings, used for channels the playlist's guide leaves empty. */
    final ExtraGuides extras;

    Library(MainActivity a, SharedPreferences prefs) { this.a = a; this.prefs = prefs; this.extras = new ExtraGuides(a.getCacheDir(), prefs); }

    // ------------------------------------------------------------------ saved playlists

    JSONArray all() {
        try { return new JSONArray(prefs.getString("playlists", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    boolean isEmpty() { return all().length() == 0; }

    // ------------------------------------------------------------------ on / off

    /** Pref: ids of saved playlists that are switched off (Settings > Manage playlists > Turn playlists on or off). */
    static final String OFF = "playlists_off";

    java.util.Set<String> offIds() { return prefs.getStringSet(OFF, java.util.Collections.<String>emptySet()); }

    boolean isOn(JSONObject p) { return p != null && !offIds().contains(p.optString("id")); }

    /**
     * The saved playlists that are switched on: everything the menus, guides, Favorites and Search read from.
     * A switched-off playlist is skipped (never downloaded or refreshed) until it is switched back on.
     */
    JSONArray inUse() {
        JSONArray l = all(), out = new JSONArray();
        java.util.Set<String> off = offIds();
        for (int i = 0; i < l.length(); i++) { JSONObject p = l.optJSONObject(i); if (p != null && !off.contains(p.optString("id"))) out.put(p); }
        return out;
    }

    /**
     * When the playlist in use was switched off, the first switched-on one takes over (a regular playlist
     * before an antenna tuner). Returns that playlist, or null when nothing had to change.
     */
    JSONObject replacementForActive() {
        JSONObject active = find(activeId());
        if (active != null && isOn(active)) return null;
        JSONArray on = inUse();
        JSONObject pick = null;
        for (int i = 0; i < on.length() && pick == null; i++) { JSONObject p = on.optJSONObject(i); if (!isHdhr(p)) pick = p; }
        if (pick == null) pick = on.optJSONObject(0);
        return pick;
    }

    void saveAll(JSONArray list) { prefs.edit().putString("playlists", list.toString()).apply(); }

    /** The prefix that a playlist's favorites, hidden items and lists are saved under (same as MainActivity.accountPrefix() while it is active). */
    static String prefixOf(JSONObject p) {
        boolean isM3u = M3U.equals(p.optString("type"));
        return "account_" + b64((isM3u ? p.optString("url") : p.optString("host")) + "\n" + (isM3u ? "" : p.optString("user"))) + "_";
    }

    String activeId() { return prefs.getString("active_playlist", ""); }

    JSONObject find(String id) {
        JSONArray l = all();
        for (int i = 0; i < l.length(); i++) { JSONObject p = l.optJSONObject(i); if (p != null && id.equals(p.optString("id"))) return p; }
        return null;
    }

    /** Adds or replaces (same id) a playlist entry. */
    void put(JSONObject p) {
        JSONArray l = all(), out = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < l.length(); i++) {
            JSONObject q = l.optJSONObject(i);
            if (q == null) continue;
            if (q.optString("id").equals(p.optString("id"))) { out.put(p); replaced = true; } else out.put(q);
        }
        if (!replaced) out.put(p);
        saveAll(out);
    }

    void remove(String id) {
        JSONArray l = all(), out = new JSONArray();
        for (int i = 0; i < l.length(); i++) { JSONObject q = l.optJSONObject(i); if (q != null && !q.optString("id").equals(id)) out.put(q); }
        saveAll(out);
        java.util.Set<String> off = new java.util.HashSet<>(offIds());
        if (off.remove(id)) prefs.edit().putStringSet(OFF, off).apply();
        JSONObject gone = null;
        for (int i = 0; i < l.length(); i++) { JSONObject q = l.optJSONObject(i); if (q != null && q.optString("id").equals(id)) gone = q; }
        if (gone != null && M3U.equals(gone.optString("type"))) m3uFile(gone.optString("url")).delete();
        if (id.equals(activeId())) {
            JSONObject next = replacementForActive();
            if (next == null && out.length() > 0) next = out.optJSONObject(0);
            if (next != null) activate(next);
            else prefs.edit().remove("active_playlist").remove("title").remove("host").remove("user").remove("pass").remove("expiry")
                    .remove("type").remove("m3u_url").remove("epg_url").apply();
        }
    }

    static JSONObject newPlaylist(String type) {
        JSONObject p = new JSONObject();
        try { p.put("id", UUID.randomUUID().toString()).put("type", type).put("added", System.currentTimeMillis()); } catch (JSONException ignored) { }
        return p;
    }

    /** Makes a playlist the one every screen reads from. */
    void activate(JSONObject p) {
        boolean isM3u = M3U.equals(p.optString("type"));
        prefs.edit().putString("active_playlist", p.optString("id"))
                .putString("type", isM3u ? M3U : XTREAM)
                .putString("title", p.optString("title"))
                .putString("host", isM3u ? p.optString("url") : p.optString("host"))
                .putString("user", isM3u ? "" : p.optString("user"))
                .putString("pass", isM3u ? "" : p.optString("pass"))
                .putString("expiry", p.optString("expiry"))
                .putString("m3u_url", isM3u ? p.optString("url") : "")
                .putString("epg_url", p.optString("epg"))
                .apply();
    }

    /** Older versions saved one Xtream login directly; turn it into the first saved playlist. */
    void migrate() {
        if (prefs.contains("playlists")) return;
        String host = prefs.getString("host", "");
        JSONArray l = new JSONArray();
        if (!host.isEmpty()) {
            JSONObject p = newPlaylist(XTREAM);
            try {
                String title = prefs.getString("title", "").trim();
                p.put("title", title.isEmpty() ? Menus.hostOf(host) : title).put("host", host).put("user", prefs.getString("user", ""))
                        .put("pass", prefs.getString("pass", "")).put("expiry", prefs.getString("expiry", ""));
            } catch (JSONException ignored) { }
            l.put(p);
            saveAll(l);
            activate(p);
        } else saveAll(l);
    }

    boolean activeIsM3u() { return M3U.equals(prefs.getString("type", "")); }

    /** An HDHomeRun antenna tuner: saved as an M3U playlist whose link is the tuner's lineup.json. */
    boolean activeIsHdhr() { return activeIsM3u() && Hdhr.isLineupUrl(prefs.getString("m3u_url", "")); }

    static boolean isHdhr(JSONObject p) { return p != null && M3U.equals(p.optString("type")) && Hdhr.isLineupUrl(p.optString("url")); }

    // ------------------------------------------------------------------ M3U

    File m3uFile(String url) { return new File(a.getFilesDir(), "playlist_" + M3u.idFor(url) + ".m3u"); }

    /** Downloads and parses an M3U link (for the add/refresh form). Saves a copy so start-up is offline-fast. */
    M3u.Playlist downloadM3u(String url) throws Exception {
        String text = Hdhr.playlistText(url);
        if (!M3u.looksLikePlaylist(text)) throw new IllegalStateException("not a playlist");
        M3u.Playlist p = M3u.parse(text);
        try (OutputStream out = new FileOutputStream(m3uFile(url))) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        synchronized (this) { if (url.equals(m3uKey)) { m3u = p; } }
        synchronized (otherM3u) { if (otherM3u.containsKey(url)) otherM3u.put(url, p); }
        return p;
    }

    /** The active M3U playlist, loading it from the saved copy (or the network) if needed. Call off the UI thread. */
    M3u.Playlist m3uBlocking() throws Exception {
        // Loading holds its own lock, so the UI thread's quick checks (m3uReady, m3uStreamUrl) never wait on a download.
        synchronized (m3uLoadLock) { return loadM3u(); }
    }

    private M3u.Playlist loadM3u() throws Exception {
        String url = prefs.getString("m3u_url", "");
        synchronized (this) { if (m3u != null && url.equals(m3uKey)) return m3u; }
        File f = m3uFile(url);
        String text = f.exists() ? readFile(f) : null;
        if (text == null || !M3u.looksLikePlaylist(text)) {
            text = Hdhr.playlistText(url);
            if (!M3u.looksLikePlaylist(text)) throw new IllegalStateException("not a playlist");
            try (OutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        }
        M3u.Playlist parsed = M3u.parse(text);
        synchronized (this) { m3u = parsed; m3uKey = url; }
        return parsed;
    }

    /** Already-loaded channel's address, or null. */
    synchronized String m3uStreamUrl(String id) {
        if (m3u == null || !prefs.getString("m3u_url", "").equals(m3uKey)) return null;
        M3u.Channel c = m3u.byId.get(id);
        return c == null ? null : c.url;
    }

    synchronized boolean m3uReady() { return m3u != null && prefs.getString("m3u_url", "").equals(m3uKey); }

    /** Answers a player_api action for the active M3U playlist. Call off the UI thread. */
    JSONObject answer(String action) throws Exception {
        String[] parts = action.split("&");
        String name = parts[0];
        Map<String, String> args = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) args.put(parts[i].substring(0, eq), URLDecoder.decode(parts[i].substring(eq + 1), "UTF-8"));
        }
        JSONObject out = new JSONObject();
        JSONArray list = new JSONArray();
        if (name.equals("get_live_categories")) {
            for (String g : m3uBlocking().groups) list.put(new JSONObject().put("category_id", g).put("category_name", g));
        } else if (name.equals("get_live_streams")) {
            M3u.Playlist p = m3uBlocking();
            String cat = args.get("category_id");
            for (int i = 0; i < p.channels.size(); i++) {
                M3u.Channel c = p.channels.get(i);
                if (cat != null && !cat.isEmpty() && !cat.equals(c.group)) continue;
                list.put(channelJson(c, i));
            }
        } else if (name.equals("get_short_epg")) {
            int limit = 4;
            try { limit = Integer.parseInt(args.containsKey("limit") ? args.get("limit") : "4"); } catch (NumberFormatException ignored) { }
            JSONArray epg = shortEpg(args.containsKey("stream_id") ? args.get("stream_id") : "", limit);
            out.put("epg_listings", epg == null ? new JSONArray() : epg);
            return out;
        }
        out.put("list", list);
        return out;
    }

    /**
     * Every live channel of a saved playlist that isn't active (for Search across playlists), each tagged with
     * "playlist" = its id. Channels and categories hidden in that playlist are left out. Call off the UI thread.
     */
    JSONArray liveChannelsOf(JSONObject p) throws Exception {
        JSONArray raw = new JSONArray();
        if (M3U.equals(p.optString("type"))) {
            M3u.Playlist pl = m3uOf(p);
            for (int i = 0; i < pl.channels.size(); i++) raw.put(channelJson(pl.channels.get(i), i));
        } else {
            String body = download(p.optString("host") + "/player_api.php?username=" + java.net.URLEncoder.encode(p.optString("user"), "UTF-8")
                    + "&password=" + java.net.URLEncoder.encode(p.optString("pass"), "UTF-8") + "&action=get_live_streams", 60000);
            raw = new JSONArray(body.trim());
        }
        String pre = prefixOf(p), id = p.optString("id");
        java.util.Set<String> hiddenCats = prefs.getStringSet(pre + "hidden_categories_live", java.util.Collections.<String>emptySet());
        java.util.Set<String> hiddenChannels = prefs.getStringSet(pre + "hidden_channels_live", java.util.Collections.<String>emptySet());
        JSONArray out = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject o = raw.optJSONObject(i);
            if (o == null || hiddenCats.contains(o.optString("category_id")) || hiddenChannels.contains(o.optString("stream_id"))) continue;
            o.put("playlist", id);
            out.put(o);
        }
        return out;
    }

    /**
     * Every movie ("vod") or series of a saved Xtream playlist (for the master Search), each tagged with
     * "playlist" = its id. Hidden categories are left out; M3U playlists have none. Call off the UI thread.
     */
    JSONArray vodOf(JSONObject p, String type) throws Exception {
        JSONArray out = new JSONArray();
        if (M3U.equals(p.optString("type"))) return out;
        String body = download(p.optString("host") + "/player_api.php?username=" + java.net.URLEncoder.encode(p.optString("user"), "UTF-8")
                + "&password=" + java.net.URLEncoder.encode(p.optString("pass"), "UTF-8")
                + "&action=" + (type.equals("series") ? "get_series" : "get_vod_streams"), 90000);
        JSONArray raw = new JSONArray(body.trim());
        java.util.Set<String> hiddenCats = prefs.getStringSet(prefixOf(p) + "hidden_categories_" + type, java.util.Collections.<String>emptySet());
        String id = p.optString("id");
        for (int i = 0; i < raw.length(); i++) {
            JSONObject o = raw.optJSONObject(i);
            if (o == null || hiddenCats.contains(o.optString("category_id"))) continue;
            o.put("playlist", id);
            out.put(o);
        }
        return out;
    }

    // ---------------------------------------------------------------- other (not active) playlists

    /** Parsed M3U playlists other than the active one, by link, so their channels can play and preview without switching. */
    private final Map<String, M3u.Playlist> otherM3u = new HashMap<>();

    /** An M3U playlist parsed from its saved copy (or the network). Call off the UI thread. */
    M3u.Playlist m3uOf(JSONObject p) throws Exception {
        String url = p.optString("url");
        synchronized (otherM3u) { M3u.Playlist got = otherM3u.get(url); if (got != null) return got; }
        File f = m3uFile(url);
        String text = f.exists() ? readFile(f) : null;
        if (text == null || !M3u.looksLikePlaylist(text)) {
            text = Hdhr.playlistText(url);
            if (!M3u.looksLikePlaylist(text)) throw new IllegalStateException("not a playlist");
            try (OutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        }
        M3u.Playlist parsed = M3u.parse(text);
        synchronized (otherM3u) { otherM3u.put(url, parsed); }
        return parsed;
    }

    /** Forgets parsed playlists that aren't active (after a restore). */
    void dropOtherPlaylists() {
        synchronized (otherM3u) { otherM3u.clear(); }
        synchronized (otherXtreamIds) { otherXtreamIds.clear(); }
        extras.clear();
    }

    /** A channel's address in any saved playlist; null for an M3U playlist that isn't loaded yet (see {@link #m3uOf}). */
    String streamUrlFor(JSONObject p, String id, String ext) {
        if (M3U.equals(p.optString("type"))) {
            synchronized (otherM3u) {
                M3u.Playlist pl = otherM3u.get(p.optString("url"));
                M3u.Channel c = pl == null ? null : pl.byId.get(id);
                return c == null ? null : c.url;
            }
        }
        String f = prefs.getString("live_format", "auto");
        if (!f.equals("auto")) ext = f;
        return p.optString("host") + "/live/" + XtreamProtocol.encode(p.optString("user")) + "/" + XtreamProtocol.encode(p.optString("pass")) + "/" + id + "." + ext;
    }

    /** The provider's short guide for a channel of another Xtream playlist (M3U playlists that aren't active have none here). Call off the UI thread. */
    JSONObject epgFor(JSONObject p, String action) throws Exception {
        return shortEpgWithExtras(action, p, () -> {
            if (isHdhr(p)) {
                String sid = "";
                int limit = 4;
                for (String part : action.split("&")) {
                    if (part.startsWith("stream_id=")) sid = URLDecoder.decode(part.substring(10), "UTF-8");
                    else if (part.startsWith("limit=")) try { limit = Integer.parseInt(part.substring(6)); } catch (NumberFormatException ignored) { }
                }
                return new JSONObject().put("epg_listings", hdhrEpg(p.optString("url"), sid, limit, p));
            }
            if (M3U.equals(p.optString("type"))) return new JSONObject().put("epg_listings", new JSONArray());
            String body = download(p.optString("host") + "/player_api.php?username=" + XtreamProtocol.encode(p.optString("user"))
                    + "&password=" + XtreamProtocol.encode(p.optString("pass")) + "&action=" + action, 20000);
            return new JSONObject(body.trim());
        });
    }

    /** The player pop-up's source line: the playlist's name and what kind of source it is. */
    static String sourceLabel(JSONObject p) {
        if (p == null) return "";
        if (isHdhr(p)) return "📡 " + Antenna.nameOf(p) + "  ·  HDHomeRun antenna";
        return nameOf(p) + "  ·  " + (M3U.equals(p.optString("type")) ? "M3U playlist" : "Xtream Codes");
    }

    /** A playlist's name for labels: its title, else its server. */
    static String nameOf(JSONObject p) {
        if (p == null) return "removed playlist";
        String t = p.optString("title");
        return t.isEmpty() ? Menus.hostOf(p.optString(M3U.equals(p.optString("type")) ? "url" : "host")) : t;
    }

    private static JSONObject channelJson(M3u.Channel c, int index) throws JSONException {
        return new JSONObject().put("stream_id", c.id).put("name", c.name).put("category_id", c.group)
                .put("stream_icon", c.logo).put("epg_channel_id", c.tvgId)
                .put("num", c.number.isEmpty() ? String.valueOf(index + 1) : c.number)
                .put("container_extension", "ts").put("stream_type", "live");
    }

    // ------------------------------------------------------------------ TV guide (XMLTV)

    /** The XMLTV address in use: the one set in Settings, else the one the M3U advertises. */
    String guideUrl() {
        String custom = prefs.getString("epg_url", "");
        if (!custom.isEmpty()) return custom;
        if (activeIsM3u()) { synchronized (this) { if (m3uReady() && !m3u.guideUrl.isEmpty()) return m3u.guideUrl; } }
        return "";
    }

    String guideStatus() {
        Xmltv.Guide g = guide;
        if (g != null && guideKey.equals(guideUrl())) return "loaded, " + g.byChannel.size() + " channels";
        return guideError;
    }

    void dropGuide() { synchronized (hdhrGuideLock) { hdhrGuides.clear(); } synchronized (guideLock) { guide = null; guideKey = ""; guideError = ""; } new File(a.getCacheDir(), guideCacheName(guideUrl())).delete(); }

    private static String guideCacheName(String url) { return "guide_" + M3u.idFor(url) + ".xml"; }

    private Xmltv.Guide guideBlocking() { synchronized (guideLock) { return loadGuide(); } }

    private Xmltv.Guide loadGuide() {
        String url = guideUrl();
        if (url.isEmpty()) return null;
        long now = System.currentTimeMillis();
        if (guide != null && url.equals(guideKey) && now - guideLoadedAt < GUIDE_MAX_AGE / 2) return guide;
        if (url.equals(guideKey) && guide == null && !guideError.isEmpty() && now - guideLoadedAt < 10 * 60_000L) return null;
        guideKey = url;
        guideLoadedAt = now;
        try {
            File f = new File(a.getCacheDir(), guideCacheName(url));
            if (!f.exists() || now - f.lastModified() > GUIDE_MAX_AGE) {
                File tmp = new File(a.getCacheDir(), f.getName() + ".part");
                HttpURLConnection c = open(url, 120000);
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                } finally { c.disconnect(); }
                if (!tmp.renameTo(f)) throw new IllegalStateException("could not save guide");
            }
            try (InputStream in = new FileInputStream(f)) { guide = Xmltv.parse(in, now - 3 * 3600_000L, now + 30 * 3600_000L); }
            guideError = "";
            return guide;
        } catch (Throwable e) {
            guide = null;
            guideError = "could not load (" + e.getClass().getSimpleName() + ")";
            return null;
        }
    }

    /**
     * Guide entries for one channel in the provider's get_short_epg format, or null when the provider's own
     * guide should be used. Call off the UI thread.
     */
    JSONArray shortEpg(String streamId, int limit) {
        Xmltv.Guide g = guideBlocking();
        if (g == null && activeIsHdhr()) return hdhrEpg(prefs.getString("m3u_url", ""), streamId, limit, null);
        if (g == null) return activeIsM3u() ? new JSONArray() : null;
        String tvgId, name;
        if (activeIsM3u()) {
            M3u.Channel c;
            try { c = m3uBlocking().byId.get(streamId); } catch (Exception e) { return new JSONArray(); }
            if (c == null) return new JSONArray();
            tvgId = c.tvgId; name = c.tvgName.isEmpty() ? c.name : c.tvgName;
        } else {
            String[] ids = xtreamEpgIds().get(streamId);
            if (ids == null) return null;
            tvgId = ids[0]; name = ids[1];
        }
        List<Xmltv.Program> progs = g.find(tvgId, name);
        if (progs.isEmpty() && activeIsHdhr()) return hdhrEpg(prefs.getString("m3u_url", ""), streamId, limit, null);
        if (progs.isEmpty()) return activeIsM3u() ? new JSONArray() : null;
        return listings(Xmltv.upcoming(progs, System.currentTimeMillis(), Math.max(1, limit)));
    }

    // ------------------------------------------------------------------ HDHomeRun guide

    /** Guide by tuner address: {loaded at (Long), channel number → programmes (Map) or null after a failure}. */
    private final Map<String, Object[]> hdhrGuides = new HashMap<>();
    private final Object hdhrGuideLock = new Object();

    /**
     * A tuner channel's upcoming programmes from SiliconDust's guide (the one the HDHomeRun app shows),
     * matched by channel number. {@code other} is a saved tuner that isn't active, or null. Call off the UI thread.
     */
    JSONArray hdhrEpg(String lineupUrl, String streamId, int limit, JSONObject other) {
        M3u.Channel c;
        try { c = (other == null ? m3uBlocking() : m3uOf(other)).byId.get(streamId); } catch (Exception e) { return new JSONArray(); }
        if (c == null) return new JSONArray();
        Map<String, List<Xmltv.Program>> g = hdhrGuide(Hdhr.baseOfLineup(lineupUrl));
        List<Xmltv.Program> progs = g == null ? null : g.get(c.number.isEmpty() ? c.tvgId : c.number);
        if (progs == null || progs.isEmpty()) return new JSONArray();
        return listings(Xmltv.upcoming(progs, System.currentTimeMillis(), Math.max(1, limit)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Xmltv.Program>> hdhrGuide(String base) {
        if (base.isEmpty()) return null;
        synchronized (hdhrGuideLock) {
            long now = System.currentTimeMillis();
            Object[] have = hdhrGuides.get(base);
            if (have != null) {
                long age = now - (Long) have[0];
                if (have[1] != null && age < 30 * 60_000L) return (Map<String, List<Xmltv.Program>>) have[1];
                if (have[1] == null && age < 5 * 60_000L) return null;
            }
            Map<String, List<Xmltv.Program>> got;
            try { got = Hdhr.loadGuide(base, 12); } catch (Exception e) { got = null; }
            if (got == null && have != null && have[1] != null) got = (Map<String, List<Xmltv.Program>>) have[1];
            hdhrGuides.put(base, new Object[]{now, got});
            return got;
        }
    }

    /** XMLTV programmes in the provider's get_short_epg format (base64 title and description). */
    static JSONArray listings(List<Xmltv.Program> progs) {
        JSONArray out = new JSONArray();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
        for (Xmltv.Program p : progs) {
            try {
                out.put(new JSONObject()
                        .put("title", b64(p.title)).put("description", b64(p.desc))
                        .put("start", f.format(new java.util.Date(p.start))).put("end", f.format(new java.util.Date(p.stop)))
                        .put("start_timestamp", String.valueOf(p.start / 1000)).put("stop_timestamp", String.valueOf(p.stop / 1000)));
            } catch (JSONException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ supplemental guides

    /**
     * Answers get_short_epg with the supplemental guides mixed in: {@code provider} gives the playlist's own
     * answer (provider guide or its XMLTV guide). By default the playlist's answer wins and the extra guides
     * only fill channels it has nothing for; with "use first" set, the extra guides are tried first.
     * {@code other} is a saved playlist that isn't active, or null for the active one. Call off the UI thread.
     */
    JSONObject shortEpgWithExtras(String action, JSONObject other, java.util.concurrent.Callable<JSONObject> provider) throws Exception {
        if (!action.startsWith("get_short_epg")) return provider.call();
        String sid = "";
        int limit = 4;
        for (String part : action.split("&")) {
            if (part.startsWith("stream_id=")) sid = URLDecoder.decode(part.substring(10), "UTF-8");
            else if (part.startsWith("limit=")) try { limit = Integer.parseInt(part.substring(6)); } catch (NumberFormatException ignored) { }
        }
        List<String> sources = new java.util.ArrayList<>(extras.urls());
        // A saved M3U playlist that isn't active has no provider guide here: use the guide it names first.
        if (other != null && M3U.equals(other.optString("type"))) {
            String own = other.optString("epg");
            if (own.isEmpty()) try { own = m3uOf(other).guideUrl; } catch (Exception ignored) { }
            if (!own.isEmpty() && !sources.contains(own)) sources.add(extras.first() ? sources.size() : 0, own);
        }
        if (sources.isEmpty() || sid.isEmpty()) return provider.call();
        boolean first = extras.first();
        if (first) { JSONArray e = extraEpg(other, sid, sources, limit); if (e.length() > 0) return ExtraGuides.wrap(e); }
        JSONObject own = null;
        Exception failed = null;
        try { own = provider.call(); }
        catch (InterruptedException | java.io.InterruptedIOException stop) { throw stop; }
        catch (Exception e) { failed = e; }
        JSONArray list = own == null ? null : own.optJSONArray("epg_listings");
        if (list != null && list.length() > 0) return own;
        if (!first) { JSONArray e = extraEpg(other, sid, sources, limit); if (e.length() > 0) return ExtraGuides.wrap(e); }
        if (own == null) throw failed != null ? failed : new IllegalStateException("no guide answer");
        return own;
    }

    /** One channel's programmes from the given guides, matched by its tvg-id, then its name. */
    private JSONArray extraEpg(JSONObject other, String sid, List<String> sources, int limit) throws InterruptedException {
        String[] ids = channelIds(other, sid);
        if (ids == null) return new JSONArray();
        return extras.lookup(sources, ids[0], ids[1], limit);
    }

    /** {tvg-id, name} of a channel in the active playlist (other == null) or a saved one; null if unknown. */
    private String[] channelIds(JSONObject other, String sid) {
        try {
            boolean m3uList = other == null ? activeIsM3u() : M3U.equals(other.optString("type"));
            if (m3uList) {
                M3u.Channel c = (other == null ? m3uBlocking() : m3uOf(other)).byId.get(sid);
                return c == null ? null : new String[]{c.tvgId, c.tvgName.isEmpty() ? c.name : c.tvgName};
            }
            return (other == null ? xtreamEpgIds() : otherXtreamIds(other)).get(sid);
        } catch (Exception e) {
            return null;
        }
    }

    /** stream_id -> {epg_channel_id, name} for saved Xtream playlists that aren't active, by playlist id. */
    private final Map<String, Map<String, String[]>> otherXtreamIds = new HashMap<>();

    private Map<String, String[]> otherXtreamIds(JSONObject p) throws Exception {
        String key = prefixOf(p);
        synchronized (otherXtreamIds) {
            Map<String, String[]> got = otherXtreamIds.get(key);
            if (got != null) return got;
            Map<String, String[]> m = new HashMap<>();
            String body = download(p.optString("host") + "/player_api.php?username=" + XtreamProtocol.encode(p.optString("user"))
                    + "&password=" + XtreamProtocol.encode(p.optString("pass")) + "&action=get_live_streams", 60000);
            JSONArray list = new JSONArray(body.trim());
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null) m.put(o.optString("stream_id"), new String[]{o.optString("epg_channel_id"), o.optString("name")});
            }
            otherXtreamIds.put(key, m);
            return m;
        }
    }

    /** stream_id -> {epg_channel_id, name} for an Xtream playlist that uses a custom guide. */
    private Map<String, String[]> xtreamEpgIds() { synchronized (guideLock) { return loadXtreamEpgIds(); } }

    private Map<String, String[]> loadXtreamEpgIds() {
        String key = a.accountPrefix();
        if (xtreamEpgIds != null && key.equals(xtreamEpgKey)) return xtreamEpgIds;
        Map<String, String[]> m = new HashMap<>();
        JSONArray list = a.liveIndex != null && key.equals(a.liveIndexAccount) ? a.liveIndex : null;
        if (list == null) {
            try { list = new JSONObject("{\"list\":" + download(a.base() + "&action=get_live_streams", 60000) + "}").optJSONArray("list"); }
            catch (Exception e) { return m; }
        }
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null) m.put(o.optString("stream_id"), new String[]{o.optString("epg_channel_id"), o.optString("name")});
        }
        xtreamEpgIds = m;
        xtreamEpgKey = key;
        return m;
    }

    private static String b64(String s) { return android.util.Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP); }

    // ------------------------------------------------------------------ Recently watched

    private String recentKey() { return a.accountPrefix() + "recent"; }

    JSONArray recent() {
        try { return new JSONArray(prefs.getString(recentKey(), "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    void recordPlay(String type, String id, String ext, String title) {
        JSONArray old = recent(), out = new JSONArray();
        try { out.put(new JSONObject().put("type", type).put("id", id).put("ext", ext).put("title", title).put("at", System.currentTimeMillis())); }
        catch (JSONException ignored) { }
        for (int i = 0; i < old.length() && out.length() < 40; i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null || (o.optString("type").equals(type) && o.optString("id").equals(id))) continue;
            out.put(o);
        }
        prefs.edit().putString(recentKey(), out.toString()).apply();
    }

    void removeRecent(String type, String id) {
        JSONArray old = recent(), out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null && !(o.optString("type").equals(type) && o.optString("id").equals(id))) out.put(o);
        }
        prefs.edit().putString(recentKey(), out.toString()).apply();
    }

    void clearRecent() { prefs.edit().remove(recentKey()).apply(); }

    // ------------------------------------------------------------------ network helpers

    static HttpURLConnection open(String url, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(readTimeout);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "WygTV/1.0 (Android TV)");
        return c;
    }

    static String download(String url, int readTimeout) throws Exception {
        HttpURLConnection c = open(url, readTimeout);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
