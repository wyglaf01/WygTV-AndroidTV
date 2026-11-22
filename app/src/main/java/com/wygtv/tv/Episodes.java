package com.wygtv.tv;

import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * A series' seasons and episodes with watch progress: a Continue / Next episode button, "3/10 watched" on each
 * season, and ✓ (watched) or ◐ (started, with time left) on each episode. Unfinished episodes resume where they
 * stopped. Hold OK (or Menu) on an episode to mark it watched or unwatched.
 */
final class Episodes {
    private Episodes() { }

    static void show(MainActivity a, String sid, String title) {
        a.playerReturn = () -> show(a, sid, title);
        a.mode = "episodes";
        a.shell("SERIES  •  " + title);
        final int generation = a.screenGeneration;

        LinearLayout left = column(a, 1), right = column(a, 2);
        a.button("← Back", left, () -> back(a));
        TextView cont = a.button("Loading episodes…", left, () -> { });
        cont.setTypeface(null, Typeface.BOLD);
        cont.requestFocus();

        a.request("get_series_info&series_id=" + a.enc(sid), o -> {
            if (generation != a.screenGeneration) return;
            JSONObject episodes = o.optJSONObject("episodes");
            List<String> seasons = Watch.seasons(episodes);
            if (seasons.isEmpty()) { cont.setText("No episodes yet"); return; }
            JSONObject state = Watch.load(a, sid);
            String[] next = Watch.next(episodes, state);
            if (next != null) {
                int idx = Integer.parseInt(next[1]);
                JSONObject e = episodes.optJSONArray(next[0]).optJSONObject(idx);
                long at = Watch.resumeAt(state, e.optString("id"));
                String what = Watch.label(next[0], e, idx);
                cont.setText(next[2].equals("resume") ? "▶  Resume " + what + (at > 0 ? "  •  from " + Watch.clock(at) : "")
                        : next[2].equals("next") ? "▶  Next: " + what : "▶  Start " + what);
                cont.setOnClickListener(v -> play(a, sid, next[0], e, idx));
            } else {
                cont.setText("✓  All watched");
                cont.setOnClickListener(v -> Toast.makeText(a, "You've watched every episode. Pick a season to watch again.", Toast.LENGTH_SHORT).show());
            }
            TextView[] seasonRows = new TextView[seasons.size()];
            for (int i = 0; i < seasons.size(); i++) {
                String s = seasons.get(i);
                seasonRows[i] = a.button("", left, () -> showSeason(a, right, sid, title, s, episodes, true));
                seasonRows[i].setText(seasonLabel(s, episodes.optJSONArray(s), state));
            }
            // Open on the season of the episode just played, else the one with the next episode.
            String resume = a.resumeStreamId, open = next != null ? next[0] : seasons.get(0);
            if (resume != null) for (String s : seasons) {
                JSONArray eps = episodes.optJSONArray(s);
                for (int i = 0; eps != null && i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e != null && resume.equals(e.optString("id"))) open = s;
                }
            }
            showSeason(a, right, sid, title, open, episodes, false);
            if (!a.focusResume(right)) cont.requestFocus();
        }, "Unable to load episodes");
    }

    /** Back from the episode list: to the series details page when it was opened from there, else Series. */
    static void back(MainActivity a) {
        Runnable r = a.episodesBack;
        if (r != null) r.run(); else a.browse("series");
    }

    private static String seasonLabel(String season, JSONArray eps, JSONObject state) {
        int n = eps == null ? 0 : eps.length(), done = Watch.doneIn(eps, state);
        return "Season " + season + "      " + (done == 0 ? n + (n == 1 ? " episode" : " episodes") : done == n ? "✓ watched" : done + "/" + n + " watched");
    }

    private static void showSeason(MainActivity a, LinearLayout right, String sid, String title, String season, JSONObject episodes, boolean focus) {
        right.removeAllViews();
        JSONObject state = Watch.load(a, sid);
        JSONArray eps = episodes.optJSONArray(season);
        TextView head = a.label("Season " + season, 22);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(Theme.ACCENT);
        head.setPadding(a.dp(8), 0, a.dp(8), a.dp(4));
        right.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView first = null;
        for (int i = 0; eps != null && i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) continue;
            final int idx = i;
            String eid = e.optString("id");
            TextView row = a.button(episodeLabel(state, season, e, i), right, () -> play(a, sid, season, e, idx));
            row.setTag(eid);
            row.setTextSize(19);
            row.setOnLongClickListener(v -> {
                boolean nowDone = !Watch.done(Watch.load(a, sid), eid);
                Watch.setDone(a, sid, eid, season, number(e, idx), nowDone);
                ((TextView) v).setText(episodeLabel(Watch.load(a, sid), season, e, idx));
                Toast.makeText(a, nowDone ? "Marked as watched" : "Marked as not watched", Toast.LENGTH_SHORT).show();
                return true;
            });
            if (first == null) first = row;
        }
        TextView hint = a.label("Hold OK on an episode to mark it watched or not watched.", 13);
        hint.setTextColor(Theme.HINT);
        hint.setGravity(Gravity.START);
        hint.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
        right.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        if (focus && first != null) first.requestFocus();
    }

    private static String episodeLabel(JSONObject state, String season, JSONObject e, int i) {
        String eid = e.optString("id"), name = e.optString("title", "Episode " + (i + 1));
        JSONObject w = Watch.entry(state, eid);
        String mark = w == null ? "      " : w.optBoolean("done") ? "✓   " : "◐   ";
        String left = "";
        if (w != null && !w.optBoolean("done") && w.optLong("dur") > 0) left = "   •   " + Watch.clock(w.optLong("dur") - w.optLong("pos")) + " left";
        return mark + Watch.label(season, e, i) + "   " + name + left;
    }

    private static String number(JSONObject e, int i) {
        String n = e.optString("episode_num");
        return n.isEmpty() || n.equals("0") ? String.valueOf(i + 1) : n;
    }

    private static void play(MainActivity a, String sid, String season, JSONObject e, int idx) {
        String eid = e.optString("id"), ext = e.optString("container_extension", "mp4"), name = e.optString("title", "Episode " + (idx + 1));
        a.pendingWatch = new String[]{sid, eid, season, number(e, idx)};
        a.play("series", eid, ext, name);
    }

    private static LinearLayout column(MainActivity a, int weight) {
        ScrollView sv = new ScrollView(a);
        a.body.addView(sv, new LinearLayout.LayoutParams(0, -1, weight));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(a.dp(4), 0, a.dp(4), 0);
        sv.addView(col);
        return col;
    }
}
