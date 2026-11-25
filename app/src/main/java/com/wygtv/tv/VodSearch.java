package com.wygtv.tv;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;


/**
 * Movies / Series → Search, working like Live TV search: a search bar at the top of the screen, beside the MOVIES / SERIES
 * title, ready to type in right away. Nothing is searched while typing; pressing Search (the button or the keyboard's
 * search key) looks through every saved playlist, the active one first, one at a time on a background thread. Each
 * playlist's list is saved to a file and read item by item, keeping only the matches (Library.searchVodIn), so a big
 * provider never has to fit in memory. Results appear as each playlist finishes and are kept for the session, so coming
 * back from a title's details shows them again. Hidden categories never come up. OK on a poster opens its details
 * (switching to its playlist first when needed); holding OK adds it to that playlist's My List.
 */
final class VodSearch {
    static final String CATEGORY = "search";
    /** At most this many titles per search, like Live TV search. */
    static final int MAX = 150;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** Per section ("vod" / "series"): what's typed, the last search's text, its results and its summary line. */
    private static final java.util.Map<String, String> QUERIES = new java.util.HashMap<>(), SEARCHED = new java.util.HashMap<>(), NOTES = new java.util.HashMap<>();
    private static final java.util.Map<String, JSONArray> RESULTS = new java.util.HashMap<>();
    /** Which search run is current: a newer one (or leaving the screen) stops older ones. */
    private static volatile int run;
    /** The Search screen showing now, so a search started earlier still fills it after coming back from a title. */
    private static Screen screen;

    private VodSearch() { }

    /** Forget every search's results (after a restore or a playlist change). */
    static void clear() { SEARCHED.clear(); RESULTS.clear(); NOTES.clear(); run++; }

    static void show(MainActivity a) {
        String type = a.mode;
        boolean series = type.equals("series");
        a.listGeneration++;
        a.category = CATEGORY;
        a.detail.removeAllViews();
        final int list = a.listGeneration;

        LinearLayout bar = headerSlot(a);
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setHint(series ? "⌕  Type a series name" : "⌕  Type a movie name");
        field.setTextColor(Theme.TEXT);
        field.setHintTextColor(Theme.HINT);
        field.setTextSize(20);
        field.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10));
        field.setBackground(a.bg(a.PANEL));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setTag(NavRail.TOP_TARGET); // Right from the top of the side menu lands on the bar.
        field.setOnFocusChangeListener((v, f) -> field.setBackground(a.bg(f ? Theme.FOCUS : a.PANEL)));
        bar.addView(field, new LinearLayout.LayoutParams(0, -2, 1));

        TextView summary = a.label("", 15);
        summary.setTextColor(Theme.MUTED);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(a.dp(12), 0, a.dp(12), 0);
        a.detail.addView(summary, new LinearLayout.LayoutParams(-1, a.dp(36)));
        LinearLayout results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        a.detail.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));

        Screen s = new Screen(a, type, list, field, summary, results);
        screen = s;
        TextView go = a.button("⌕ Search", bar, () -> { hideKeyboard(a, field); s.search(); });
        go.setTextSize(17);
        go.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(a.dp(170), a.dp(56));
        goLp.setMargins(a.dp(10), a.dp(5), a.dp(5), a.dp(5));
        go.setLayoutParams(goLp);
        // The bar sits in the title row; take it away again as soon as the results leave the screen (another category, My List…).
        results.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { if (field.getParent() == bar) bar.removeView(field); if (go.getParent() == bar) bar.removeView(go); }
        });

        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int b, int d, int e) { }
            public void onTextChanged(CharSequence c, int b, int d, int e) { }
            public void afterTextChanged(Editable e) { QUERIES.put(type, e.toString()); s.typed(); }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            hideKeyboard(a, field);
            s.search();
            return action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
        });

        String last = QUERIES.containsKey(type) ? QUERIES.get(type) : "";
        field.setText(last);
        field.setSelection(last.length());
        // Coming back (from a title's details, or another category): the last results show again, nothing is searched.
        JSONArray kept = RESULTS.get(type);
        if (!last.isEmpty() && last.equals(SEARCHED.get(type)) && kept != null && kept.length() > 0) {
            boolean returning = a.resumeStreamId != null;
            s.render(returning);
            s.note(lastNote(type));
            if (!returning) field.requestFocus();
            return;
        }
        field.requestFocus();
        s.typed();
    }

    /** One Search screen: its views and what's on it. */
    private static final class Screen {
        final MainActivity a;
        final String type;
        final boolean series;
        final int list;
        final EditText field;
        final TextView summary;
        final LinearLayout results;

        Screen(MainActivity a, String type, int list, EditText field, TextView summary, LinearLayout results) {
            this.a = a; this.type = type; this.series = type.equals("series"); this.list = list;
            this.field = field; this.summary = summary; this.results = results;
        }

        boolean current() { return list == a.listGeneration && CATEGORY.equals(a.category) && type.equals(a.mode); }

        void note(String text) { summary.setText(text == null ? "" : text); }

        String what() { return series ? "series" : "movies"; }

        /** While typing: nothing is searched or loaded yet, just a hint. */
        void typed() {
            if (!current()) return;
            String q = field.getText().toString();
            int pls = xtream(a).size();
            if (XtreamProtocol.searchKey(q).isEmpty())
                note("Type a " + (series ? "series" : "movie") + " name, then press Search (or the search key on the keyboard)." + (pls > 1 ? " All " + pls + " playlists are searched." : ""));
            else if (q.equals(SEARCHED.get(type))) note(lastNote(type));
            else note("Press Search to look for \"" + q.trim() + "\"" + (pls > 1 ? " in " + pls + " playlists" : "") + ".");
        }

        /** Searches every saved playlist, the active one first, one at a time; results show as each finishes. */
        void search() {
            if (!current()) return;
            String typed = field.getText().toString(), q = XtreamProtocol.searchKey(typed);
            QUERIES.put(type, typed);
            if (q.length() < 2) { note("Type at least 2 letters of a " + (series ? "series" : "movie") + " name, then press Search."); return; }
            List<JSONObject> order = xtream(a);
            if (order.isEmpty()) { note("Movies and series come from Xtream playlists. None is switched on."); return; }
            final int mine = ++run;
            JSONArray found = new JSONArray();
            SEARCHED.put(type, typed);
            RESULTS.put(type, found);
            NOTES.put(type, "");
            results.removeAllViews();
            a.items = found;
            note("Searching…");
            new Thread(() -> {
                List<String> problems = new ArrayList<>();
                boolean capped = false;
                for (int i = 0; i < order.size(); i++) {
                    JSONObject p = order.get(i);
                    String name = Library.nameOf(p);
                    final int n = i;
                    UI.post(() -> { Screen cur = on(type); if (mine == run && cur != null) cur.note("Searching " + name + (order.size() > 1 ? " (" + (n + 1) + " of " + order.size() + ")" : "") + "…  " + found.length() + " found so far"); });
                    JSONArray got = null;
                    try { got = a.library.searchVodIn(p, type, q, MAX); }
                    catch (OutOfMemoryError e) { System.gc(); problems.add(name + " (not enough memory)"); }
                    catch (Throwable e) { problems.add(name); }
                    if (mine != run) return;
                    if (got != null && got.length() >= MAX) capped = true;
                    final JSONArray more = got;
                    if (more != null && more.length() > 0) UI.post(() -> {
                        if (mine != run) return;
                        for (int k = 0; k < more.length() && found.length() < MAX; k++) found.put(more.opt(k));
                        // Show them now, unless you're already moving through the posters (they're added at the end then).
                        Screen cur = on(type);
                        if (cur != null && !cur.results.hasFocus()) cur.render(false);
                    });
                }
                final boolean full = capped;
                UI.post(() -> {
                    if (mine != run) return;
                    int c = found.length();
                    String text = (c == 0 ? "Nothing matches \"" + typed.trim() + "\""
                            : c + (c == 1 ? (series ? " series" : " movie") : " " + what()) + " match \"" + typed.trim() + "\""
                            + (full || c >= MAX ? " (showing the first " + MAX + ", type more of the name to narrow it down)" : "")
                            + "  •  OK opens, hold OK for My List")
                            + (problems.isEmpty() ? "" : "  •  couldn't search: " + TextUtils.join(", ", problems));
                    NOTES.put(type, text);
                    Screen cur = on(type);
                    if (cur == null) return;
                    if (cur.results.hasFocus()) {
                        // Keep your place: the poster you're on stays focused when the rest are added.
                        View f = a.getCurrentFocus();
                        Object id = f == null ? null : f.getTag();
                        if (id instanceof String) a.resumeStreamId = (String) id;
                        cur.render(a.resumeStreamId != null);
                    } else cur.render(!cur.field.hasFocus());
                    cur.note(text);
                });
            }, "vod-search").start();
        }

        /** The info panel and posters for the results so far. */
        void render(boolean takeFocus) {
            if (!current()) return;
            JSONArray found = RESULTS.get(type);
            if (found == null) found = new JSONArray();
            a.items = found;
            results.removeAllViews();
            if (found.length() == 0) return;
            PosterGrid.showIn(a, results, found, takeFocus);
        }
    }

    /** The last search's summary line ("Searching…" while it's still going). */
    private static String lastNote(String type) {
        String n = NOTES.get(type);
        return n == null || n.isEmpty() ? "Searching…" : n;
    }

    /** The Search screen for this section, if it's the one showing. */
    private static Screen on(String type) {
        Screen s = screen;
        return s != null && s.type.equals(type) && s.current() ? s : null;
    }

    /** The playlists that can have movies and series (Xtream, switched on), the active one first. */
    private static List<JSONObject> xtream(MainActivity a) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray pls = a.library.inUse();
        String activeId = a.library.activeId();
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < pls.length(); i++) {
                JSONObject p = pls.optJSONObject(i);
                if (p == null || Library.M3U.equals(p.optString("type")) || (pass == 0) != p.optString("id").equals(activeId)) continue;
                out.add(p);
            }
        return out;
    }

    private static void hideKeyboard(MainActivity a, EditText field) {
        ((InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    /**
     * The row at the very top of the screen holding the screen title ("MOVIES" / "SERIES"): the search bar goes there,
     * to the right of the title and lined up with it. The title is wrapped in that row the first time; the row is
     * emptied of any older search bar.
     */
    private static LinearLayout headerSlot(MainActivity a) {
        View top = a.root.getChildAt(0);
        LinearLayout row;
        if (top instanceof LinearLayout && HEADER.equals(top.getTag())) {
            row = (LinearLayout) top;
            while (row.getChildCount() > 1) row.removeViewAt(1);
        } else {
            ViewGroup.LayoutParams lp = top.getLayoutParams();
            a.root.removeViewAt(0);
            row = new LinearLayout(a);
            row.setTag(HEADER);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(top, new LinearLayout.LayoutParams(-2, -1));
            a.root.addView(row, 0, lp);
        }
        LinearLayout slot = new LinearLayout(a);
        slot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1);
        sp.leftMargin = a.dp(28);
        row.addView(slot, sp);
        return slot;
    }

    private static final String HEADER = "vodsearch_header";

}
