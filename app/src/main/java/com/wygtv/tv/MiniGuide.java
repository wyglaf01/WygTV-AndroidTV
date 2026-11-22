package com.wygtv.tv;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The mini TV guide on the right of Live TV: each channel row shows its logo and name, then what is on now (with
 * times and minutes left) and what is on next. Guide data is fetched a few rows ahead of the focused row and kept
 * for a while, so going back to a category shows it straight away.
 */
final class MiniGuide {
    private static final long KEEP_MS = 30 * 60_000L;
    private static final int AHEAD = 14;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final class Entry {
        final List<EpgGrid.Program> programs; final long fetched;
        Entry(List<EpgGrid.Program> programs, long fetched) { this.programs = programs; this.fetched = fetched; }
    }
    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Set<String> LOADING = new HashSet<>();
    /** Which channel each row shows, so late guide data never lands on a row that was rebuilt. */
    private static final Map<TextView, String> ROWS = new WeakHashMap<>();
    private static final Map<TextView, Object[]> ROW_DATA = new WeakHashMap<>();
    private static Runnable ticker;

    private MiniGuide() { }

    /** Forget all guide data, e.g. after switching playlists. */
    static void clear() { CACHE.clear(); }

    /** Fills the channel list with guide rows for items[from, limit). */
    static List<TextView> fill(MainActivity a, LinearLayout list, JSONArray items, int from, int limit, ItemAction ok, ItemAction more) {
        List<TextView> rows = new ArrayList<>();
        List<JSONObject> data = new ArrayList<>();
        for (int i = from; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("name", "Untitled");
            TextView row = a.button(title, list, () -> ok.run(item));
            row.setTag(item.optString("stream_id"));
            row.setOnLongClickListener(v -> { more.run(item); return true; });
            row.setPadding(row.getPaddingLeft(), a.dp(9), row.getPaddingRight(), a.dp(9));
            row.setLineSpacing(0, 1.05f);
            ChannelIcons.attach(row, item.optString("stream_icon"), title, 46);
            ROWS.put(row, key(a, item));
            ROW_DATA.put(row, new Object[]{a, item});
            render(a, row, item);
            rows.add(row);
            data.add(item);
        }
        for (int i = 0; i < rows.size(); i++) {
            final int at = i;
            TextView row = rows.get(i);
            android.view.View.OnFocusChangeListener old = row.getOnFocusChangeListener();
            row.setOnFocusChangeListener((v, f) -> {
                if (old != null) old.onFocusChange(v, f);
                if (f) for (int k = Math.max(0, at - 2); k < Math.min(rows.size(), at + AHEAD); k++) request(a, rows.get(k), data.get(k));
            });
        }
        for (int k = 0; k < Math.min(rows.size(), AHEAD); k++) request(a, rows.get(k), data.get(k));
        startTicker();
        return rows;
    }

    interface ItemAction { void run(JSONObject item); }

    /** Playlist + channel id, since two providers can use the same channel number. */
    private static String key(MainActivity a, JSONObject item) {
        String pid = item.optString("playlist");
        return (pid.isEmpty() ? a.library.activeId() : pid) + "|" + item.optString("stream_id");
    }

    private static void request(MainActivity a, TextView row, JSONObject item) {
        String id = key(a, item), sid = item.optString("stream_id");
        Entry have = CACHE.get(id);
        long now = System.currentTimeMillis();
        if (sid.isEmpty() || (have != null && now - have.fetched < KEEP_MS && !runOut(have, now)) || !LOADING.add(id)) return;
        String pid = item.optString("playlist");
        final JSONObject other = pid.isEmpty() || pid.equals(a.library.activeId()) ? null : a.library.find(pid);
        final String action = "get_short_epg&stream_id=" + a.enc(sid) + "&limit=6";
        POOL.execute(() -> {
            List<EpgGrid.Program> progs = new ArrayList<>();
            try {
                JSONArray l = (other == null ? a.apiBlocking(action) : a.library.epgFor(other, action)).optJSONArray("epg_listings");
                TimeZone tz = TimeZone.getDefault();
                if (l != null) for (int k = 0; k < l.length(); k++) {
                    JSONObject e = l.optJSONObject(k);
                    if (e == null) continue;
                    long start = EpgGrid.parseTime(e.optString("start_timestamp"), e.optString("start"), tz);
                    long end = EpgGrid.parseTime(e.optString("stop_timestamp"), e.optString("end", e.optString("stop")), tz);
                    progs.add(new EpgGrid.Program(start, end, a.epgText(e.optString("title")), ""));
                }
            } catch (Exception ignored) {
                // No guide for this channel; the row says so and the channel still plays.
            }
            List<EpgGrid.Program> clean = EpgGrid.clean(progs);
            UI.post(() -> {
                LOADING.remove(id);
                CACHE.put(id, new Entry(clean, System.currentTimeMillis()));
                for (Map.Entry<TextView, String> r : new ArrayList<>(ROWS.entrySet())) {
                    if (!id.equals(r.getValue()) || !r.getKey().isAttachedToWindow()) continue;
                    Object[] d = ROW_DATA.get(r.getKey());
                    if (d != null) render((MainActivity) d[0], r.getKey(), (JSONObject) d[1]);
                }
            });
        });
    }

    private static boolean runOut(Entry e, long now) {
        return !e.programs.isEmpty() && e.programs.get(e.programs.size() - 1).end <= now;
    }

    /** Name on the first line, then "Now" and "Next" lines from the guide. */
    private static void render(MainActivity a, TextView row, JSONObject item) {
        String title = item.optString("name", "Untitled");
        String sid = item.optString("stream_id");
        String pid = item.optString("playlist");
        boolean fav = (pid.isEmpty() || pid.equals(a.library.activeId())) && a.isFavorite("live", sid);
        SpannableStringBuilder s = new SpannableStringBuilder();
        int st = s.length();
        s.append(fav ? "★ " : "").append(title);
        s.setSpan(new StyleSpan(Typeface.BOLD), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Entry e = CACHE.get(key(a, item));
        long now = System.currentTimeMillis();
        EpgGrid.Program cur = null, next = null;
        if (e != null) for (EpgGrid.Program p : e.programs) {
            if (p.end <= now) continue;
            if (p.start <= now && cur == null) { cur = p; continue; }
            if (next == null) { next = p; break; }
        }
        DateFormat tf = android.text.format.DateFormat.getTimeFormat(a);
        if (e == null) {
            line(s, "Loading guide…", null, Theme.MUTED);
        } else if (cur == null && next == null) {
            line(s, "No guide information", null, Theme.MUTED);
        } else {
            if (cur != null) {
                long left = Math.max(1, (cur.end - now + 59_999) / 60_000);
                line(s, "Now  " + tf.format(new Date(cur.start)) + " – " + tf.format(new Date(cur.end)) + "   ",
                        (cur.title.isEmpty() ? "Untitled" : cur.title) + "   (" + left + " min left)", Theme.ACCENT);
            } else {
                line(s, "Now  ", "No guide information", Theme.ACCENT);
            }
            if (next != null) line(s, "Next  " + tf.format(new Date(next.start)) + "   ", next.title.isEmpty() ? "Untitled" : next.title, Theme.MUTED);
        }
        row.setText(s);
    }

    private static void line(SpannableStringBuilder s, String head, String text, int headColor) {
        s.append("\n");
        int st = s.length();
        s.append(head);
        s.setSpan(new ForegroundColorSpan(headColor), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (text != null) s.append(text);
        s.setSpan(new RelativeSizeSpan(0.78f), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Re-draws visible rows once a minute so "Now" moves on while the screen stays open. */
    private static void startTicker() {
        if (ticker != null) return;
        ticker = new Runnable() {
            @Override public void run() {
                boolean any = false;
                for (Map.Entry<TextView, Object[]> r : new ArrayList<>(ROW_DATA.entrySet())) {
                    if (!r.getKey().isAttachedToWindow()) continue;
                    any = true;
                    MainActivity a = (MainActivity) r.getValue()[0];
                    JSONObject item = (JSONObject) r.getValue()[1];
                    render(a, r.getKey(), item);
                    Entry e = CACHE.get(key(a, item));
                    if (e != null && runOut(e, System.currentTimeMillis()) && r.getKey().hasFocus()) request(a, r.getKey(), item);
                }
                if (any) UI.postDelayed(this, 60_000L); else ticker = null;
            }
        };
        UI.postDelayed(ticker, 60_000L);
    }
}
