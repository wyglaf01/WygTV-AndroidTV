package com.wygtv.tv;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings → Manage playlists → "Manage categories" and "Hide and show channels".
 * Both open a full-screen page (mode "manage"): a fixed menu bar at the top (Save, Tick all, Untick all,
 * Exit), groups on the left (Live TV / Movies / Series, or the channel categories) and a tick list on the
 * right where ticked = shown. Changes are kept across groups until Save. Back in a list jumps to the menu
 * bar; Back on the menu bar exits (asking to save first if anything changed). Uses the same per-playlist
 * prefs as the Live TV / Movies / Series menus: hiddenKey(section) and hiddenChannelsKey().
 */
final class Manage {
    private Manage() { }

    static final String[] SECTIONS = {"live", "vod", "series"};
    static final String[] SECTION_NAMES = {"Live TV", "Movies", "Series"};

    static int hiddenCategories(MainActivity a, String section) {
        return a.prefs.getStringSet(a.hiddenKey(section), new HashSet<>()).size();
    }

    /** Short summary for the Settings row, e.g. "Live TV 3 hidden  •  Movies all shown  •  Series 1 hidden". */
    static String categorySummary(MainActivity a) {
        StringBuilder b = new StringBuilder();
        int n = a.liveOnly() ? 1 : SECTIONS.length;
        for (int i = 0; i < n; i++) {
            if (b.length() > 0) b.append("  •  ");
            int h = hiddenCategories(a, SECTIONS[i]);
            b.append(SECTION_NAMES[i]).append(h == 0 ? " all shown" : " " + h + " hidden");
        }
        return b.toString();
    }

    static String channelSummary(MainActivity a) {
        int h = a.hiddenChannels().size();
        return h == 0 ? "no channels hidden" : h + (h == 1 ? " channel hidden" : " channels hidden");
    }

    // ---------- Categories ----------

    /** Opens the full-screen category page: Live TV, Movies and Series on the left (just Live TV in Live-TV-only mode). */
    static void categories(MainActivity a, Runnable after) {
        List<Group> groups = new ArrayList<>();
        int n = a.liveOnly() ? 1 : SECTIONS.length;
        for (int i = 0; i < n; i++) {
            String section = SECTIONS[i], name = SECTION_NAMES[i];
            groups.add(new Group(name + " categories", a.hiddenKey(section), true, got ->
                    a.request("get_" + section + "_categories", o -> {
                        JSONArray cats = o == null ? null : o.optJSONArray("list");
                        List<String> ids = new ArrayList<>(), titles = new ArrayList<>();
                        for (int j = 0; cats != null && j < cats.length(); j++) {
                            JSONObject c = cats.optJSONObject(j);
                            if (c == null) continue;
                            ids.add(c.optString("category_id"));
                            titles.add(c.optString("category_name", "Category"));
                        }
                        got.run(ids, titles, "This playlist has no " + name + " categories.");
                    }, "Unable to load categories")));
        }
        new Page(a, "MANAGE CATEGORIES", "categories", groups, 0, after).open();
    }

    // ---------- Channels ----------

    /** Opens the full-screen channel page: Hidden channels, All channels and every Live TV category on the left. */
    static void channels(MainActivity a, Runnable after) {
        a.request("get_live_categories", o -> {
            JSONArray cats = o == null ? null : o.optJSONArray("list");
            String key = a.hiddenChannelsKey();
            List<Group> groups = new ArrayList<>();
            Page[] page = new Page[1];
            groups.add(new Group("⊘  Hidden channels", key, true, got -> loadChannels(a, "", got, page[0].original(key), "No channels are hidden.")));
            groups.add(new Group("▦  All channels", key, false, got -> loadChannels(a, "", got, null, "This playlist has no channels.")));
            for (int i = 0; cats != null && i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("category_id"), name = c.optString("category_name", "Category");
                groups.add(new Group("›  " + name, key, false, got -> loadChannels(a, id, got, null, "No channels in " + name + ".")));
            }
            int start = a.hiddenChannels().isEmpty() ? 1 : 0;
            page[0] = new Page(a, "HIDE AND SHOW CHANNELS", "channels", groups, start, after);
            page[0].open();
        }, "Unable to load categories");
    }

    /** Loads a category's channels (or every channel when categoryId is ""); onlyThese keeps just those ids. */
    private static void loadChannels(MainActivity a, String categoryId, Got got, Set<String> onlyThese, String empty) {
        String action = "get_live_streams" + (categoryId.isEmpty() ? "" : "&category_id=" + a.enc(categoryId));
        a.request(action, o -> {
            JSONArray list = o == null ? null : o.optJSONArray("list");
            List<String> ids = new ArrayList<>(), names = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("stream_id");
                if (id.isEmpty() || !seen.add(id)) continue;
                if (onlyThese != null && !onlyThese.contains(id)) continue;
                ids.add(id);
                names.add(c.optString("name", "Channel"));
            }
            got.run(ids, names, empty);
        }, "Unable to load channels");
    }

    // ---------- The full-screen tick page ----------

    interface Got { void run(List<String> ids, List<String> names, String emptyMessage); }
    interface Loader { void load(Got got); }

    /** One entry on the left: what it is called, which hidden-set pref it edits, and how to load its rows. */
    static final class Group {
        final String label, key; final boolean showCount; final Loader loader;
        Group(String label, String key, boolean showCount, Loader loader) { this.label = label; this.key = key; this.showCount = showCount; this.loader = loader; }
    }

    /** The page that is open now (mode "manage"), for the Back key. */
    static Page page;

    /** Back on the manage page: from a list, jump to the menu bar; from the menu bar, exit (asking to save first). */
    static boolean back(MainActivity a) {
        Page p = page;
        if (p == null || p.a != a) return false;
        p.back();
        return true;
    }

    static final class Page {
        final MainActivity a; final String title, what; final List<Group> groups; final Runnable after;
        /** Hidden ids per pref key: as saved, and as edited on this page. */
        final Map<String, Set<String>> saved = new HashMap<>(), edited = new HashMap<>();
        final Handler ui = new Handler(Looper.getMainLooper());
        int current, loadGeneration; boolean focusListOnLoad = true;
        List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        LinearLayout bar; TextView save, info; ListView left, right; BaseAdapter leftAdapter, rightAdapter;

        Page(MainActivity a, String title, String what, List<Group> groups, int start, Runnable after) {
            this.a = a; this.title = title; this.what = what; this.groups = groups; this.after = after; this.current = start;
            for (Group g : groups) if (!saved.containsKey(g.key)) {
                saved.put(g.key, new HashSet<>(a.prefs.getStringSet(g.key, new HashSet<>())));
                edited.put(g.key, new HashSet<>(saved.get(g.key)));
            }
        }

        Set<String> original(String key) { return saved.get(key); }
        Set<String> hidden() { return edited.get(groups.get(current).key); }

        boolean changed() {
            for (String k : saved.keySet()) if (!saved.get(k).equals(edited.get(k))) return true;
            return false;
        }

        void open() {
            page = this;
            LivePreview.release();
            a.mode = "manage";
            a.screenGeneration++;
            a.listGeneration++;
            LinearLayout root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(a.dp(24), a.dp(14), a.dp(24), a.dp(12));
            root.setBackground(new SteampunkBackdrop(a.getResources(), R.drawable.steampunk_panel, false));
            a.setContentView(root);

            TextView h = a.label(title, 26);
            h.setTypeface(null, Typeface.BOLD);
            h.setTextColor(a.BRASS);
            root.addView(h, new LinearLayout.LayoutParams(-1, a.dp(48)));

            // The fixed menu bar: it never scrolls away, and Back from a list lands here.
            bar = new LinearLayout(a);
            root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            save = a.button("✓ Save", bar, this::save);
            TextView tickAll = a.button("☑ Tick all", bar, () -> tickAll(true));
            TextView untickAll = a.button("☐ Untick all", bar, () -> tickAll(false));
            TextView exit = a.button("✕ Exit", bar, this::exit);
            for (TextView t : new TextView[]{save, tickAll, untickAll, exit}) { t.setLayoutParams(a.rowLp()); t.setTextSize(18); t.setGravity(Gravity.CENTER); }

            info = a.label("", 15);
            info.setTextColor(Theme.MUTED);
            info.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
            root.addView(info, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout panes = new LinearLayout(a);
            root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
            left = list();
            right = list();
            if (groups.size() > 1) panes.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
            panes.addView(right, new LinearLayout.LayoutParams(0, -1, groups.size() > 1 ? 2 : 1));

            leftAdapter = new Rows() {
                @Override public int getCount() { return groups.size(); }
                @Override public View getView(int i, View v, ViewGroup parent) {
                    Group g = groups.get(i);
                    int n = g.showCount ? edited.get(g.key).size() : -1;
                    TextView t = text(v, g.label + (n > 0 ? "   (" + n + " hidden)" : ""));
                    t.setBackground(i == current ? a.bg(a.PANEL) : null);
                    t.setTextColor(i == current ? Theme.TEXT : Theme.MUTED);
                    return t;
                }
            };
            left.setAdapter(leftAdapter);
            left.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    ui.removeCallbacksAndMessages(null);
                    if (pos != current) ui.postDelayed(() -> show(pos, false), 350);
                }
                @Override public void onNothingSelected(AdapterView<?> p) { }
            });
            left.setOnItemClickListener((p, v, pos, id) -> {
                ui.removeCallbacksAndMessages(null);
                if (pos != current) show(pos, true); else if (!ids.isEmpty()) right.requestFocus();
            });

            rightAdapter = new Rows() {
                @Override public int getCount() { return ids.size(); }
                @Override public View getView(int i, View v, ViewGroup parent) {
                    boolean shown = !hidden().contains(ids.get(i));
                    TextView[] t = new TextView[1];
                    LinearLayout row = checkRow(v, t);
                    ((CheckBox) row.getChildAt(0)).setChecked(shown);
                    t[0].setText(names.get(i));
                    t[0].setTextColor(shown ? Theme.TEXT : Theme.MUTED);
                    return row;
                }
            };
            right.setAdapter(rightAdapter);
            right.setOnItemClickListener((p, v, pos, id) -> {
                String rowId = ids.get(pos);
                if (!hidden().remove(rowId)) hidden().add(rowId);
                refresh();
            });

            left.setSelection(current);
            show(current, true);
        }

        /** A list with the theme's focus highlight; CH+/CH- page through it. */
        ListView list() {
            ListView lv = new ListView(a);
            lv.setSelector(a.bg(Theme.FOCUS));
            lv.setDrawSelectorOnTop(false);
            lv.setDivider(null);
            lv.setDividerHeight(a.dp(4));
            lv.setItemsCanFocus(false);
            lv.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
            lv.setClipToPadding(false);
            lv.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key != KeyEvent.KEYCODE_CHANNEL_DOWN && key != KeyEvent.KEYCODE_CHANNEL_UP) return false;
                int n = lv.getCount();
                if (n == 0) return true;
                int step = Math.max(1, lv.getChildCount() - 1) * (key == KeyEvent.KEYCODE_CHANNEL_DOWN ? 1 : -1);
                lv.setSelection(Math.max(0, Math.min(n - 1, lv.getSelectedItemPosition() + step)));
                return true;
            });
            return lv;
        }

        abstract static class Rows extends BaseAdapter {
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }
        }

        TextView text(View recycled, String s) {
            TextView t = recycled instanceof TextView ? (TextView) recycled : a.label("", 19);
            t.setPadding(a.dp(16), a.dp(11), a.dp(12), a.dp(11));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setText(s);
            return t;
        }

        LinearLayout checkRow(View recycled, TextView[] out) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
                CheckBox box = new CheckBox(a);
                box.setFocusable(false);
                box.setClickable(false);
                box.setButtonTintList(ColorStateList.valueOf(Theme.ACCENT));
                row.addView(box, new LinearLayout.LayoutParams(-2, -2));
                TextView t = a.label("", 19);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(a.dp(10), a.dp(4), 0, a.dp(4));
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            }
            out[0] = (TextView) row.getChildAt(1);
            return row;
        }

        /** Loads group {@code i} into the tick list; edits so far are kept. */
        void show(int i, boolean focusList) {
            current = i;
            focusListOnLoad = focusList;
            int gen = ++loadGeneration;
            ids = new ArrayList<>(); names = new ArrayList<>();
            rightAdapter.notifyDataSetChanged();
            leftAdapter.notifyDataSetChanged();
            info.setText("Loading " + groups.get(i).label.replaceAll("^[^A-Za-z0-9]+", "") + "…");
            groups.get(i).loader.load((newIds, newNames, empty) -> {
                if (page != this || gen != loadGeneration) return;
                ids = newIds; names = newNames;
                right.setSelection(0);
                refresh();
                if (ids.isEmpty()) info.setText(empty + (changed() ? "   •   unsaved changes" : ""));
                if (focusListOnLoad) { focusListOnLoad = false; (ids.isEmpty() ? (groups.size() > 1 ? left : save) : right).requestFocus(); }
            });
        }

        void refresh() {
            rightAdapter.notifyDataSetChanged();
            leftAdapter.notifyDataSetChanged();
            int on = 0;
            Set<String> h = hidden();
            for (String id : ids) if (!h.contains(id)) on++;
            String name = groups.get(current).label.replaceAll("^[^A-Za-z0-9]+", "");
            info.setText(name + "   •   " + on + " shown, " + (ids.size() - on) + " hidden   •   ticked are shown, OK ticks or unticks"
                    + (changed() ? "   •   unsaved changes" : "") + "   •   Back = menu bar");
            save.setText(changed() ? "✓ Save changes" : "✓ Save");
        }

        void tickAll(boolean show) {
            if (ids.isEmpty()) return;
            Set<String> h = hidden();
            for (String id : ids) { if (show) h.remove(id); else h.add(id); }
            refresh();
        }

        void back() {
            View f = a.getCurrentFocus();
            boolean inBar = f != null && f.getParent() == bar;
            if (inBar) exit(); else save.requestFocus();
        }

        void exit() {
            if (!changed()) { close(); return; }
            new Dialogs.Builder(a).setTitle("Save your changes?")
                    .setMessage("You ticked or unticked some " + what + " but haven't saved.")
                    .setPositiveButton("Save", (d, w) -> save())
                    .setNegativeButton("Don't save", (d, w) -> close())
                    .setNeutralButton("Keep editing", null).show();
        }

        void save() {
            int hid = 0, back = 0;
            android.content.SharedPreferences.Editor e = a.prefs.edit();
            for (String k : saved.keySet()) {
                Set<String> was = saved.get(k), now = edited.get(k);
                if (was.equals(now)) continue;
                for (String id : now) if (!was.contains(id)) hid++;
                for (String id : was) if (!now.contains(id)) back++;
                e.putStringSet(k, new HashSet<>(now));
            }
            e.apply();
            String msg = hid == 0 && back == 0 ? "No changes" : (hid > 0 ? hid + " hidden" : "") + (hid > 0 && back > 0 ? ", " : "") + (back > 0 ? back + " shown again" : "");
            Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
            close();
        }

        void close() {
            ui.removeCallbacksAndMessages(null);
            if (page == this) page = null;
            after.run();
        }
    }
}
