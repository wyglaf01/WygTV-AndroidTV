package com.wygtv.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Series watch progress, per playlist and series: for every episode played, where it stopped and whether it was
 * finished; plus which episode was played last. Saved in prefs under {@code <accountPrefix>watch_series_<id>} as
 * {"last": episodeId, "eps": {episodeId: {"pos": ms, "dur": ms, "done": bool, "s": season, "e": episode number}}}.
 */
final class Watch {
    /** An episode counts as watched past 90% or with under 3 minutes left. */
    static boolean finished(long pos, long dur) { return dur > 0 && (pos >= dur * 9 / 10 || dur - pos < 180_000); }

    private Watch() { }

    static String key(MainActivity a, String seriesId) { return a.accountPrefix() + "watch_series_" + seriesId; }

    static JSONObject load(MainActivity a, String seriesId) {
        try { return new JSONObject(a.prefs.getString(key(a, seriesId), "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** Records where an episode stopped (called when the player closes or the app goes to the background). */
    static void record(MainActivity a, String seriesId, String episodeId, String season, String number, long pos, long dur) {
        if (seriesId == null || episodeId == null || pos < 0) return;
        JSONObject state = load(a, seriesId);
        try {
            update(state, episodeId, season, number, pos, dur);
            a.prefs.edit().putString(key(a, seriesId), state.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void update(JSONObject state, String episodeId, String season, String number, long pos, long dur) throws Exception {
        JSONObject eps = state.optJSONObject("eps");
        if (eps == null) { eps = new JSONObject(); state.put("eps", eps); }
        JSONObject old = eps.optJSONObject(episodeId);
        boolean done = finished(pos, dur) || (old != null && old.optBoolean("done") && pos < 60_000);
        eps.put(episodeId, new JSONObject().put("pos", done ? 0 : pos).put("dur", dur).put("done", done).put("s", season).put("e", number));
        state.put("last", episodeId);
    }

    /** Marks or unmarks an episode as watched by hand. */
    static void setDone(MainActivity a, String seriesId, String episodeId, String season, String number, boolean done) {
        JSONObject state = load(a, seriesId);
        try {
            JSONObject eps = state.optJSONObject("eps");
            if (eps == null) { eps = new JSONObject(); state.put("eps", eps); }
            if (done) eps.put(episodeId, new JSONObject().put("pos", 0).put("dur", 0).put("done", true).put("s", season).put("e", number));
            else eps.remove(episodeId);
            a.prefs.edit().putString(key(a, seriesId), state.toString()).apply();
        } catch (Exception ignored) { }
    }

    static JSONObject entry(JSONObject state, String episodeId) {
        JSONObject eps = state.optJSONObject("eps");
        return eps == null ? null : eps.optJSONObject(episodeId);
    }

    static boolean done(JSONObject state, String episodeId) { JSONObject e = entry(state, episodeId); return e != null && e.optBoolean("done"); }

    /** Where to resume an unfinished episode (ms), or 0. */
    static long resumeAt(JSONObject state, String episodeId) {
        JSONObject e = entry(state, episodeId);
        if (e == null || e.optBoolean("done")) return 0;
        long pos = e.optLong("pos");
        return pos > 30_000 ? pos : 0;
    }

    /** Season keys of a get_series_info "episodes" object, in number order. */
    static List<String> seasons(JSONObject episodes) {
        List<String> out = new ArrayList<>();
        if (episodes == null) return out;
        for (Iterator<String> k = episodes.keys(); k.hasNext(); ) out.add(k.next());
        Collections.sort(out, (x, y) -> {
            int a = num(x), b = num(y);
            return a != b ? Integer.compare(a, b) : x.compareTo(y);
        });
        return out;
    }

    /**
     * The episode to offer next: the last one played if it isn't finished (resume), else the one after it (across
     * seasons), else the first episode when nothing was watched. Returns {season, index in that season, "resume"
     * or "next" or "start"}, or null when every episode after the last one played is done.
     */
    static String[] next(JSONObject episodes, JSONObject state) {
        List<String> seasons = seasons(episodes);
        String last = state.optString("last");
        String firstSeason = null;
        for (String s : seasons) { JSONArray eps = episodes.optJSONArray(s); if (eps != null && eps.length() > 0) { firstSeason = s; break; } }
        if (firstSeason == null) return null;
        if (last.isEmpty() || entry(state, last) == null) return new String[]{firstSeason, "0", "start"};
        boolean passed = false;
        for (String s : seasons) {
            JSONArray eps = episodes.optJSONArray(s);
            for (int i = 0; eps != null && i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) continue;
                String id = e.optString("id");
                if (passed && !done(state, id)) return new String[]{s, String.valueOf(i), "next"};
                if (id.equals(last)) {
                    if (!done(state, id)) return new String[]{s, String.valueOf(i), "resume"};
                    passed = true;
                }
            }
        }
        return null;
    }

    /** How many episodes of a season are watched. */
    static int doneIn(JSONArray eps, JSONObject state) {
        int n = 0;
        for (int i = 0; eps != null && i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e != null && done(state, e.optString("id"))) n++;
        }
        return n;
    }

    /** "S1 E4" for an episode of a season (by its episode_num, else its place in the list). */
    static String label(String season, JSONObject e, int index) {
        String n = e == null ? "" : e.optString("episode_num");
        if (n.isEmpty() || n.equals("0")) n = String.valueOf(index + 1);
        return "S" + season + " E" + n;
    }

    /** Short progress note for lists ("watched up to S2 E3"), or "" when nothing was played. */
    static String summary(JSONObject state) {
        String last = state.optString("last");
        JSONObject e = last.isEmpty() ? null : entry(state, last);
        if (e == null) return "";
        String at = "S" + e.optString("s") + " E" + e.optString("e");
        return e.optBoolean("done") ? "watched " + at : "stopped in " + at;
    }

    static String clock(long ms) {
        long m = ms / 60_000;
        return m >= 60 ? m / 60 + "h " + m % 60 + "m" : m + " min";
    }

    private static int num(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return Integer.MAX_VALUE; } }
}
