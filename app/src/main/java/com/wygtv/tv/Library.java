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

    Library(MainActivity a, SharedPreferences prefs) { this.a = a; this.prefs = prefs; }

    // ------------------------------------------------------------------ saved playlists

    JSONArray all() {
        try { return new JSONArray(prefs.getString("playlists", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    boolean isEmpty() { return all().length() == 0; }

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
        JSONObject gone = null;
        for (int i = 0; i < l.length(); i++) { JSONObject q = l.optJSONObject(i); if (q != null && q.optString("id").equals(id)) gone = q; }
        if (gone != null && M3U.equals(gone.optString("type"))) m3uFile(gone.optString("url")).delete();
        if (id.equals(activeId())) {
            if (out.length() > 0) activate(out.optJSONObject(0));
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

    // ------------------------------------------------------------------ M3U

    File m3uFile(String url) { return new File(a.getFilesDir(), "playlist_" + M3u.idFor(url) + ".m3u"); }

    /** Downloads and parses an M3U link (for the add/refresh form). Saves a copy so start-up is offline-fast. */
    M3u.Playlist downloadM3u(String url) throws Exception {
        String text = download(url, 60000);
        if (!M3u.looksLikePlaylist(text)) throw new IllegalStateException("not a playlist");
        M3u.Playlist p = M3u.parse(text);
        try (OutputStream out = new FileOutputStream(m3uFile(url))) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        synchronized (this) { if (url.equals(m3uKey)) { m3u = p; } }
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
            text = download(url, 60000);
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
            text = download(url, 60000);
            if (!M3u.looksLikePlaylist(text)) throw new IllegalStateException("not a playlist");
            try (OutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        }
        M3u.Playlist parsed = M3u.parse(text);
        synchronized (otherM3u) { otherM3u.put(url, parsed); }
        return parsed;
    }

    /** Forgets parsed playlists that aren't active (after a restore). */
    void dropOtherPlaylists() { synchronized (otherM3u) { otherM3u.clear(); } }

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
        if (M3U.equals(p.optString("type"))) return new JSONObject().put("epg_listings", new JSONArray());
        String body = download(p.optString("host") + "/player_api.php?username=" + XtreamProtocol.encode(p.optString("user"))
                + "&password=" + XtreamProtocol.encode(p.optString("pass")) + "&action=" + action, 20000);
        return new JSONObject(body.trim());
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

    void dropGuide() { synchronized (guideLock) { guide = null; guideKey = ""; guideError = ""; } new File(a.getCacheDir(), guideCacheName(guideUrl())).delete(); }

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
        if (progs.isEmpty()) return activeIsM3u() ? new JSONArray() : null;
        JSONArray out = new JSONArray();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
        for (Xmltv.Program p : Xmltv.upcoming(progs, System.currentTimeMillis(), Math.max(1, limit))) {
            try {
                out.put(new JSONObject()
                        .put("title", b64(p.title)).put("description", b64(p.desc))
                        .put("start", f.format(new java.util.Date(p.start))).put("end", f.format(new java.util.Date(p.stop)))
                        .put("start_timestamp", String.valueOf(p.start / 1000)).put("stop_timestamp", String.valueOf(p.stop / 1000)));
            } catch (JSONException ignored) { }
        }
        return out;
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
