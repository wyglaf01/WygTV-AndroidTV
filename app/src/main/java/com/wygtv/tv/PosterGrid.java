package com.wygtv.tv;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Movies and Series as a poster grid: each tile has the poster, title, year and rating (series: where you
 * stopped). Above the grid an info panel shows the focused title's details: year, runtime, rating, genres and plot,
 * first from the list, then from the provider's get_vod_info / get_series_info, then TMDb when a key is set.
 */
final class PosterGrid {
    static final int PAGE = 60, COLUMNS = 5;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    /** Details already fetched this session, by playlist + type + id. */
    private static final Map<String, JSONObject> META = new HashMap<>();

    private PosterGrid() { }

    static void show(MainActivity a, int offset) {
        boolean series = a.mode.equals("series");
        String type = a.mode, key = series ? "series_id" : "stream_id";
        JSONArray items = a.items;
        if (offset == 0 && a.resumeStreamId != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && a.resumeStreamId.equals(it.optString(key))) { offset = i / PAGE * PAGE; break; }
            }
        }
        final int from = offset, generation = a.screenGeneration, list = a.listGeneration;
        a.detail.removeAllViews();

        // Info panel for the focused title.
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(a.dp(10), 0, a.dp(10), a.dp(6));
        a.detail.addView(info, new LinearLayout.LayoutParams(-1, a.dp(150)));
        TextView title = line(a, info, 24, Theme.TEXT, 1);
        title.setTypeface(null, Typeface.BOLD);
        TextView facts = line(a, info, 15, Theme.ACCENT, 1);
        TextView plot = line(a, info, 15, Theme.MUTED, 4);
        title.setText(items.length() == 0 ? "Nothing here yet" : series ? "Series" : "Movies");
        facts.setText(items.length() == 0 ? "" : items.length() + (series ? " series" : " movies") + "  •  press OK on a poster for details");

        ScrollView sv = new ScrollView(a);
        a.detail.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        sv.addView(grid);

        LinearLayout top = new LinearLayout(a);
        grid.addView(top, new LinearLayout.LayoutParams(-1, -2));
        TextView back = a.button("← Categories", top, () -> { if (a.side.getChildCount() > a.sideFixed) a.side.getChildAt(a.sideFixed).requestFocus(); });
        small(a, back);
        if (from > 0) small(a, a.button("← Previous page", top, () -> show(a, Math.max(0, from - PAGE))));
        int limit = Math.min(items.length(), from + PAGE);
        if (limit < items.length()) small(a, a.button("Next page →", top, () -> show(a, limit)));
        if (items.length() > PAGE) {
            TextView where = a.label((from + 1) + "–" + limit + " of " + items.length(), 14);
            where.setTextColor(Theme.MUTED);
            where.setPadding(a.dp(10), 0, a.dp(10), 0);
            top.addView(where, new LinearLayout.LayoutParams(-2, -1));
        }

        Runnable[] pending = new Runnable[1];
        String[] focused = new String[1];
        View firstTile = null, resumeTile = null;
        LinearLayout row = null;
        for (int i = from; i < limit; i++) {
            if ((i - from) % COLUMNS == 0) {
                row = new LinearLayout(a);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            JSONObject item = items.optJSONObject(i);
            if (item == null) item = new JSONObject();
            final JSONObject it = item;
            String id = it.optString(key);
            View tile = tile(a, row, type, it, id, generation);
            tile.setOnFocusChangeListener((v, f) -> {
                v.setScaleX(f ? 1.07f : 1f);
                v.setScaleY(f ? 1.07f : 1f);
                v.setBackground(a.bg(f ? Theme.FOCUS : a.PANEL));
                v.setElevation(f ? a.dp(6) : 0);
                if (!f) return;
                focused[0] = id;
                if (pending[0] != null) UI.removeCallbacks(pending[0]);
                pending[0] = () -> describe(a, type, it, id, title, facts, plot, focused, generation, list);
                UI.postDelayed(pending[0], 220);
            });
            if (firstTile == null) firstTile = tile;
            if (a.resumeStreamId != null && a.resumeStreamId.equals(id)) resumeTile = tile;
        }
        // Keep the last row's tiles the same width as the rest.
        if (row != null) for (int k = row.getChildCount(); k < COLUMNS; k++) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        a.resumeStreamId = null;
        if (resumeTile != null) resumeTile.requestFocus();
        else if (firstTile != null) firstTile.requestFocus();
        else back.requestFocus();
    }

    private static View tile(MainActivity a, LinearLayout row, String type, JSONObject item, String id, int generation) {
        boolean series = type.equals("series");
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(a.dp(5), a.dp(5), a.dp(5), a.dp(6));
        t.setBackground(a.bg(a.PANEL));
        t.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(a.dp(5), a.dp(6), a.dp(5), a.dp(6));
        row.addView(t, lp);

        JSONObject m = Tmdb.fromProvider(item, null);
        String name = m.optString("title", item.optString("name", "Untitled"));
        FrameLayout art = new FrameLayout(a);
        t.addView(art, new LinearLayout.LayoutParams(-1, a.dp(168)));
        TextView placeholder = a.label(name, 15);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setTextColor(Theme.MUTED);
        placeholder.setPadding(a.dp(6), 0, a.dp(6), 0);
        art.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        Details.image(poster, m.optString("poster"), a.dp(140), generation, a);

        boolean fav = a.isFavorite(type, id);
        TextView label = a.label((fav ? "★ " : "") + name, 14);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(a.dp(2), a.dp(5), a.dp(2), 0);
        t.addView(label, new LinearLayout.LayoutParams(-1, -2));
        String sub = series ? Watch.summary(Watch.load(a, id)) : "";
        if (sub.isEmpty()) {
            StringBuilder b = new StringBuilder();
            if (!m.optString("year").isEmpty()) b.append(m.optString("year"));
            if (!m.optString("rating").isEmpty()) b.append(b.length() > 0 ? "   ★ " : "★ ").append(m.optString("rating"));
            sub = b.toString();
        } else sub = "▶ " + sub;
        TextView under = a.label(sub.isEmpty() ? " " : sub, 12);
        under.setTextColor(Theme.MUTED);
        under.setSingleLine(true);
        under.setPadding(a.dp(2), 0, a.dp(2), 0);
        t.addView(under, new LinearLayout.LayoutParams(-1, -2));

        t.setTag(id);
        t.setOnClickListener(v -> a.select(item));
        return t;
    }

    /** Fills the info panel for the focused title: list data now, provider and TMDb details as they arrive. */
    private static void describe(MainActivity a, String type, JSONObject item, String id, TextView title, TextView facts, TextView plot,
                                 String[] focused, int generation, int list) {
        boolean series = type.equals("series");
        String cacheKey = a.accountPrefix() + type + id;
        Runnable[] render = new Runnable[1];
        JSONObject[] meta = {META.containsKey(cacheKey) ? META.get(cacheKey) : Tmdb.fromProvider(item, null)};
        render[0] = () -> {
            if (generation != a.screenGeneration || list != a.listGeneration || !id.equals(focused[0])) return;
            JSONObject m = meta[0];
            title.setText(m.optString("title", item.optString("name")));
            String f = Details.facts(m, series), g = m.optString("genres");
            if (series) { String w = Watch.summary(Watch.load(a, id)); if (!w.isEmpty()) f = f + (f.isEmpty() ? "" : "   •   ") + "▶ " + w; }
            facts.setText(g.isEmpty() ? f : f.isEmpty() ? g : f + "   •   " + g);
            String p = m.optString("plot");
            plot.setText(p.isEmpty() ? (m.has("source") || m.has("fetched") ? "No description." : "") : p);
        };
        render[0].run();
        if (META.containsKey(cacheKey) || a.library.activeIsM3u()) return;
        a.apiQuiet((series ? "get_series_info&series_id=" : "get_vod_info&vod_id=") + a.enc(id), answer -> {
            JSONObject provider = Tmdb.fromProvider(item, answer);
            try { provider.put("fetched", true); } catch (Exception ignored) { }
            meta[0] = provider;
            if (Tmdb.key(a).isEmpty()) { META.put(cacheKey, provider); render[0].run(); return; }
            render[0].run();
            String[] clean = Tmdb.cleanTitle(item.optString("name"));
            POOL.execute(() -> {
                JSONObject t = Tmdb.lookup(a, type, provider.optString("tmdb_id"), clean[0], provider.optString("year", clean[1]));
                UI.post(() -> {
                    meta[0] = t == null ? provider : Tmdb.merge(provider, t);
                    META.put(cacheKey, meta[0]);
                    render[0].run();
                });
            });
        });
    }

    private static TextView line(MainActivity a, LinearLayout parent, int size, int color, int lines) {
        TextView t = a.label("", size);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, a.dp(2), 0, a.dp(2));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private static void small(MainActivity a, TextView b) {
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(a.dp(5), a.dp(2), a.dp(5), a.dp(4));
        b.setLayoutParams(lp);
    }
}
