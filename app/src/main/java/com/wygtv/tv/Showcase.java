package com.wygtv.tv;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Main screen option 1 (the default): this week's popular movies and series, in rows of posters to scroll through.
 * The popular titles come from TMDb's trending lists and are matched to the titles your playlists actually have, so
 * every poster opens and plays (the same details page as Movies and Series). Without a TMDb key the rows show the
 * newest titles added to your playlists instead. Live TV, Movies, Series, Search and antenna TV are in the side menu.
 * Option 2 is the classic Home with its tiles; Settings > Appearance > Main screen picks one.
 */
final class Showcase {
    private Showcase() { }

    static final String PREF = "home_style";
    static final String[] LABELS = {"Option 1: Popular movies & series (default)", "Option 2: Classic (Live TV, Movies, Series and Search tiles)"};

    /** Whether the main screen is option 1 (this screen). */
    static boolean on(SharedPreferences p) { return !"classic".equals(p.getString(PREF, "showcase")); }

    static final int PER_ROW = 20;
    /** How long the popular rows are reused before they're looked up again. */
    static final long KEEP_MS = 6L * 60 * 60 * 1000;

    private static boolean loading;

    /** Builds the screen and fills the rows (from the saved rows straight away, refreshed when they're old). */
    static void show(MainActivity a) {
        a.mode = "home";
        a.screenGeneration++;
        a.listGeneration++;
        final int generation = a.screenGeneration;
        FrameLayout scene = new FrameLayout(a);
        View frame = NavRail.wrap(a, scene, "home");
        frame.setBackground(new SteampunkBackdrop(a.getResources(), R.drawable.steampunk_home, true));
        a.setContentView(frame);

        // The highlighted title's picture fills the back of the screen.
        ImageView backdrop = new ImageView(a);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(.30f);
        scene.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(a.dp(28), a.dp(14), a.dp(24), a.dp(8));
        scene.addView(layout, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        layout.addView(header, new LinearLayout.LayoutParams(-1, a.dp(40)));
        TextView brand = a.label("WygTV", 28);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setTextColor(Theme.ACCENT);
        header.addView(brand, new LinearLayout.LayoutParams(-2, -1));
        a.status = a.label("", 13);
        a.status.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        a.status.setTextColor(Theme.MUTED);
        header.addView(a.status, new LinearLayout.LayoutParams(0, -1, 1));

        // Spotlight: what the highlighted poster is.
        TextView title = a.label("Popular this week", 22);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        layout.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView facts = a.label("Movies and series people are watching now, from your playlists", 13);
        facts.setTextColor(Theme.ACCENT);
        facts.setSingleLine(true);
        layout.addView(facts, new LinearLayout.LayoutParams(-1, -2));
        TextView plot = a.label("", 13);
        plot.setTextColor(Theme.MUTED);
        plot.setMaxLines(2);
        plot.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, a.dp(38));
        plp.bottomMargin = a.dp(4);
        layout.addView(plot, plp);

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));

        Spot spot = new Spot(a, generation, backdrop, title, facts, plot);
        JSONObject saved = readSaved(a);
        if (saved != null) fill(a, rows, saved, spot);
        else a.status.setText("Finding this week's popular movies and series in your playlists…");
        if (saved == null || System.currentTimeMillis() - saved.optLong("time") > KEEP_MS || !saved.optString("for").equals(sourcesKey(a)))
            refresh(a, generation, rows, spot);
        else AutoRefresh.show(a, "Watching • " + a.saved("title"));
    }

    /** The spotlight texts and picture, updated as posters are highlighted. */
    private static final class Spot {
        final MainActivity a; final int generation; final ImageView backdrop; final TextView title, facts, plot;
        Spot(MainActivity a, int generation, ImageView backdrop, TextView title, TextView facts, TextView plot) {
            this.a = a; this.generation = generation; this.backdrop = backdrop; this.title = title; this.facts = facts; this.plot = plot;
        }
        void show(JSONObject it, boolean series) {
            title.setText(it.optString("_title", it.optString("name", "")));
            StringBuilder b = new StringBuilder(series ? "Series" : "Movie");
            if (!it.optString("_year").isEmpty()) b.append("  •  ").append(it.optString("_year"));
            if (!it.optString("_rating").isEmpty()) b.append("  •  ★ ").append(it.optString("_rating"));
            JSONObject p = a.library.find(it.optString("playlist"));
            if (p != null && a.library.inUse().length() > 1) b.append("  •  ").append(Library.nameOf(p));
            facts.setText(b);
            plot.setText(it.optString("_overview"));
            String art = it.optString("_backdrop");
            if (!art.isEmpty()) Details.image(backdrop, art, a.getResources().getDisplayMetrics().widthPixels, generation, a);
        }
    }

    /** Looks up the popular titles and matches them to the playlists on a background thread, then redraws the rows. */
    private static void refresh(MainActivity a, int generation, LinearLayout rows, Spot spot) {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            JSONObject result = null;
            try { result = build(a); } catch (OutOfMemoryError e) { System.gc(); } catch (Throwable ignored) { }
            final JSONObject got = result;
            a.runOnUiThread(() -> {
                loading = false;
                if (a.isFinishing() || a.isDestroyed()) return;
                if (got != null) save(a, got);
                if (generation != a.screenGeneration || !"home".equals(a.mode)) return;
                if (got == null) { a.status.setText("Couldn't load the popular titles right now. They'll be tried again next time."); return; }
                fill(a, rows, got, spot);
                AutoRefresh.show(a, "Watching • " + a.saved("title"));
            });
        }, "showcase").start();
    }

    /** The popular rows: TMDb trending matched to every switched-on playlist with movies and series (playing one first). */
    private static JSONObject build(MainActivity a) throws Exception {
        JSONObject out = new JSONObject();
        out.put("time", System.currentTimeMillis());
        out.put("for", sourcesKey(a));
        boolean tmdb = !Tmdb.key(a).isEmpty();
        out.put("tmdb", tmdb);
        for (String type : new String[]{"vod", "series"}) {
            boolean series = type.equals("series");
            Map<String, Integer> byTmdb = null, byTitle = null;
            JSONArray trend = new JSONArray();
            if (tmdb) {
                try { trend = Tmdb.trending(a, series, 3); } catch (Throwable ignored) { }
                byTmdb = new HashMap<>();
                byTitle = new HashMap<>();
                for (int i = 0; i < trend.length(); i++) {
                    JSONObject t = trend.optJSONObject(i);
                    String name = t.optString(series ? "name" : "title");
                    String year = Tmdb.yearOf(t.optString(series ? "first_air_date" : "release_date"));
                    byTmdb.put(String.valueOf(t.optLong("id")), i);
                    String k = XtreamProtocol.searchKey(name);
                    if (!year.isEmpty() && !byTitle.containsKey(k + "|" + year)) byTitle.put(k + "|" + year, i);
                    if (!byTitle.containsKey(k + "|")) byTitle.put(k + "|", i);
                    String original = XtreamProtocol.searchKey(t.optString(series ? "original_name" : "original_title"));
                    if (!original.isEmpty() && !byTitle.containsKey(original + "|")) byTitle.put(original + "|", i);
                }
                if (trend.length() == 0) { byTmdb = null; byTitle = null; }
            }
            // Each popular title once, from the first playlist that has it (the playing one first).
            Map<Integer, JSONObject> best = new java.util.TreeMap<>();
            JSONArray pls = a.library.inUse();
            String active = a.library.activeId();
            java.util.List<JSONObject> order = new java.util.ArrayList<>();
            JSONObject act = a.library.find(active);
            if (act != null && a.library.isOn(act)) order.add(act);
            for (int i = 0; i < pls.length(); i++) { JSONObject p = pls.optJSONObject(i); if (p != null && !p.optString("id").equals(active)) order.add(p); }
            for (JSONObject p : order) {
                if (Library.M3U.equals(p.optString("type"))) continue;
                JSONArray got;
                try { got = a.library.popularIn(p, type, byTmdb, byTitle, PER_ROW); } catch (Throwable e) { continue; }
                for (int i = 0; i < got.length(); i++) {
                    JSONObject o = got.optJSONObject(i);
                    int rank = o.optInt("rank");
                    if (byTmdb == null) rank = best.size(); // newest titles: keep playlist order
                    if (!best.containsKey(rank)) best.put(rank, o);
                }
                if (byTmdb == null && best.size() >= PER_ROW) break;
            }
            JSONArray row = new JSONArray();
            for (Map.Entry<Integer, JSONObject> e : best.entrySet()) {
                if (row.length() >= PER_ROW) break;
                JSONObject o = e.getValue();
                JSONObject t = byTmdb != null && e.getKey() < trend.length() ? trend.optJSONObject(e.getKey()) : null;
                if (t != null) {
                    o.put("_title", t.optString(series ? "name" : "title"));
                    o.put("_year", Tmdb.yearOf(t.optString(series ? "first_air_date" : "release_date")));
                    double v = t.optDouble("vote_average", 0);
                    if (v > 0) o.put("_rating", String.format(java.util.Locale.US, "%.1f", v));
                    o.put("_overview", t.optString("overview"));
                    o.put("_poster", Tmdb.image(t.optString("poster_path"), "w342"));
                    o.put("_backdrop", Tmdb.image(t.optString("backdrop_path"), "w780"));
                } else {
                    JSONObject m = Tmdb.fromProvider(o, null);
                    o.put("_title", m.optString("title", o.optString("name")));
                    o.put("_year", m.optString("year"));
                    o.put("_rating", m.optString("rating"));
                    o.put("_poster", m.optString("poster"));
                }
                row.put(o);
            }
            out.put(type, row);
        }
        return out;
    }

    /** Which playlists the saved rows were made from, so switching playlists on or off looks them up again. */
    private static String sourcesKey(MainActivity a) {
        StringBuilder b = new StringBuilder();
        JSONArray pls = a.library.inUse();
        for (int i = 0; i < pls.length(); i++) { JSONObject p = pls.optJSONObject(i); if (p != null) b.append(p.optString("id")).append(','); }
        return b.toString();
    }

    private static void fill(MainActivity a, LinearLayout rows, JSONObject data, Spot spot) {
        rows.removeAllViews();
        boolean tmdb = data.optBoolean("tmdb");
        JSONArray movies = data.optJSONArray("vod"), series = data.optJSONArray("series");
        View first = null;
        if (movies != null && movies.length() > 0) first = row(a, rows, tmdb ? "Popular movies" : "Newest movies", "vod", movies, spot);
        if (series != null && series.length() > 0) { View f = row(a, rows, tmdb ? "Popular series" : "Newest series", "series", series, spot); if (first == null) first = f; }
        if (rows.getChildCount() == 0) {
            TextView none = a.label(tmdb ? "None of this week's popular titles are in your playlists. Movies and Series are in the side menu."
                    : "Your playlists have no movies or series yet. Live TV is in the side menu.", 16);
            none.setTextColor(Theme.MUTED);
            none.setPadding(0, a.dp(20), 0, 0);
            rows.addView(none);
        } else if (!tmdb) {
            TextView hint = a.label("Showing the newest titles. Add a TMDb key (Settings > Appearance > movie and series info) to see what's popular this week.", 12);
            hint.setTextColor(Theme.HINT);
            rows.addView(hint);
        }
        if (first != null && !a.isFinishing()) first.requestFocus();
    }

    /** One titled row of posters that scrolls sideways. Returns its first poster. */
    private static View row(MainActivity a, LinearLayout rows, String heading, String type, JSONArray items, Spot spot) {
        TextView h = a.label(heading, 17);
        h.setTypeface(null, Typeface.BOLD);
        h.setTextColor(Theme.ACCENT);
        h.setPadding(a.dp(4), a.dp(4), 0, a.dp(2));
        rows.addView(h, new LinearLayout.LayoutParams(-1, -2));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        rows.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout strip = new LinearLayout(a);
        strip.setPadding(a.dp(2), a.dp(4), a.dp(40), a.dp(6));
        hs.addView(strip, new FrameLayout.LayoutParams(-2, -2));
        View first = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            View t = tile(a, strip, type, it, items, spot);
            if (first == null) first = t;
        }
        return first;
    }

    private static View tile(MainActivity a, LinearLayout strip, String type, JSONObject it, JSONArray items, Spot spot) {
        boolean series = type.equals("series");
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
        t.setBackground(Skin.tile(a, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(112), -2);
        lp.setMargins(a.dp(5), a.dp(4), a.dp(5), a.dp(4));
        strip.addView(t, lp);
        String name = it.optString("_title", it.optString("name", "Untitled"));
        FrameLayout art = new FrameLayout(a);
        t.addView(art, new LinearLayout.LayoutParams(-1, a.dp(156)));
        TextView placeholder = a.label(name, 13);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setTextColor(Theme.MUTED);
        placeholder.setPadding(a.dp(4), 0, a.dp(4), 0);
        art.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        String url = it.optString("_poster");
        if (url.isEmpty()) url = it.optString(series ? "cover" : "stream_icon");
        Details.image(poster, url, a.dp(112), spot.generation, a);
        TextView label = a.label(name, 12);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(a.dp(2), a.dp(4), a.dp(2), 0);
        t.addView(label, new LinearLayout.LayoutParams(-1, -2));
        t.setOnFocusChangeListener((v, focused) -> {
            t.setBackground(Skin.tile(a, focused));
            t.setScaleX(focused ? 1.06f : 1f);
            t.setScaleY(focused ? 1.06f : 1f);
            if (focused) spot.show(it, series);
        });
        t.setOnClickListener(v -> open(a, type, it, items));
        return t;
    }

    /** OK on a poster: its details page (play, trailer, My List…), switching to its playlist first if needed. */
    private static void open(MainActivity a, String type, JSONObject item, JSONArray row) {
        String pid = item.optString("playlist");
        if (!pid.isEmpty() && !pid.equals(a.library.activeId())) {
            JSONObject p = a.library.find(pid);
            if (p == null) { Menus.alert(a, "This title's playlist was removed."); return; }
            a.switchPlaylist(p);
        }
        JSONArray same = new JSONArray();
        for (int i = 0; i < row.length(); i++) {
            JSONObject o = row.optJSONObject(i);
            if (o != null && o.optString("playlist").equals(pid)) same.put(o);
        }
        Details.open(a, type, item, "", same, a::home);
    }

    private static File file(MainActivity a) { return new File(a.getFilesDir(), "showcase.json"); }

    private static JSONObject readSaved(MainActivity a) {
        File f = file(a);
        if (!f.exists() || f.length() > 4_000_000) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (Throwable e) { return null; }
    }

    private static void save(MainActivity a, JSONObject data) {
        try (FileOutputStream out = new FileOutputStream(file(a))) { out.write(data.toString().getBytes(StandardCharsets.UTF_8)); } catch (Throwable ignored) { }
    }

    /** Forgets the saved rows (e.g. when playlists update), so the next visit looks them up again. */
    static void forget(MainActivity a) { file(a).delete(); }
}
