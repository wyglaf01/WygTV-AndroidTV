package com.wygtv.tv;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
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


/**
 * Movies / Series → Search: a search bar at the top of the screen, beside the title, over the poster grid. Every movie (or series) of the playlist is loaded
 * once and filtered as you type; results open the details page on OK, and holding OK adds them to My List.
 */
final class VodSearch {
    static final String CATEGORY = "search";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** Loaded lists, per playlist + section, kept for the session. */
    private static String indexKey = "";
    private static JSONArray index;
    private static String[] keys = new String[0];
    private static final java.util.Map<String, String> QUERIES = new java.util.HashMap<>();

    private VodSearch() { }

    static void show(MainActivity a) {
        String type = a.mode, key = a.accountPrefix() + type;
        a.listGeneration++;
        a.category = CATEGORY;
        a.detail.removeAllViews();
        final int list = a.listGeneration;

        LinearLayout bar = headerSlot(a);
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setHint(type.equals("series") ? "⌕  Type a series name" : "⌕  Type a movie name");
        field.setTextColor(Theme.TEXT);
        field.setHintTextColor(Theme.HINT);
        field.setTextSize(20);
        field.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(10));
        field.setBackground(a.bg(a.PANEL));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setOnFocusChangeListener((v, f) -> field.setBackground(a.bg(f ? Theme.FOCUS : a.PANEL)));
        bar.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        a.detail.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
        // The bar sits in the title row; take it away again as soon as the results leave the screen (another category, My List…).
        results.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { if (field.getParent() == bar) bar.removeView(field); }
        });

        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            if (list != a.listGeneration) return;
            String q = field.getText().toString();
            QUERIES.put(key, q);
            results.removeAllViews();
            if (index == null || !indexKey.equals(key)) { note(a, results, "Loading the list…"); return; }
            String qk = XtreamProtocol.searchKey(q);
            if (qk.length() < 2) { note(a, results, "Type at least 2 letters. " + index.length() + (type.equals("series") ? " series" : " movies") + " to search."); return; }
            JSONArray hits = XtreamProtocol.filter(index, keys, qk);
            a.items = hits;
            if (hits.length() == 0) { note(a, results, "Nothing matches \"" + q.trim() + "\"."); return; }
            PosterGrid.showIn(a, results, hits, a.resumeStreamId != null);
        };
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int b, int c, int d) { }
            public void onTextChanged(CharSequence s, int b, int c, int d) { }
            public void afterTextChanged(Editable s) { UI.removeCallbacks(render[0]); UI.postDelayed(render[0], 350); }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            UI.removeCallbacks(render[0]);
            render[0].run();
            ((InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(), 0);
            return action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
        });

        String last = QUERIES.containsKey(key) ? QUERIES.get(key) : "";
        boolean returning = a.resumeStreamId != null && !last.isEmpty();
        field.setText(last);
        field.setSelection(last.length());
        UI.removeCallbacks(render[0]);
        if (!returning) field.requestFocus();
        if (index != null && indexKey.equals(key)) { render[0].run(); return; }
        note(a, results, "Loading the list…");
        a.request(type.equals("series") ? "get_series" : "get_vod_streams", o -> {
            JSONArray all = o.optJSONArray("list");
            index = a.withoutHiddenIn(type, all == null ? new JSONArray() : all);
            keys = new String[index.length()];
            for (int i = 0; i < keys.length; i++) { JSONObject it = index.optJSONObject(i); keys[i] = it == null ? "" : XtreamProtocol.searchKey(it.optString("name")); }
            indexKey = key;
            render[0].run();
        }, "Unable to load the list");
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

    private static void note(MainActivity a, LinearLayout box, String text) {
        TextView t = a.label(text, 17);
        t.setTextColor(Theme.MUTED);
        t.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }
}
