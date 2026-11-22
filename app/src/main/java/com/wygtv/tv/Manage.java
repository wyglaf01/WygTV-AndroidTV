package com.wygtv.tv;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings → Manage playlists → "Manage categories" and "Hide and show channels".
 * Both open a tick list (ticked = shown) with Save, Tick all / Untick all and Cancel at the top,
 * so several or all items can be hidden or shown in one go. Uses the same per-playlist prefs as
 * the Live TV / Movies / Series menus: hiddenKey(section) and hiddenChannelsKey().
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

    /** Asks Live TV, Movies or Series (just Live TV in Live-TV-only mode), then opens that section's tick list. */
    static void categories(MainActivity a, Runnable after) {
        if (a.liveOnly()) { categoryList(a, "live", after); return; }
        String[] labels = new String[SECTIONS.length];
        for (int i = 0; i < SECTIONS.length; i++) {
            int h = hiddenCategories(a, SECTIONS[i]);
            labels[i] = SECTION_NAMES[i] + " categories" + (h == 0 ? "" : "   (" + h + " hidden)");
        }
        new Dialogs.Builder(a).setTitle("Manage categories")
                .setItems(labels, (d, w) -> categoryList(a, SECTIONS[w], after))
                .setNegativeButton("Cancel", null).show();
    }

    static void categoryList(MainActivity a, String section, Runnable after) {
        String name = SECTION_NAMES[java.util.Arrays.asList(SECTIONS).indexOf(section)];
        a.request("get_" + section + "_categories", o -> {
            JSONArray cats = o == null ? null : o.optJSONArray("list");
            List<String> ids = new ArrayList<>(), titles = new ArrayList<>();
            for (int i = 0; cats != null && i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                ids.add(c.optString("category_id"));
                titles.add(c.optString("category_name", "Category"));
            }
            if (ids.isEmpty()) { Menus.alert(a, "This playlist has no " + name + " categories."); return; }
            Set<String> hidden = new HashSet<>(a.prefs.getStringSet(a.hiddenKey(section), new HashSet<>()));
            boolean[] shown = new boolean[ids.size()];
            for (int i = 0; i < ids.size(); i++) shown[i] = !hidden.contains(ids.get(i));
            tickList(a, name + " categories  •  ticked are shown", titles, shown, ticks -> {
                Set<String> h = new HashSet<>(a.prefs.getStringSet(a.hiddenKey(section), new HashSet<>()));
                int count = 0;
                for (int i = 0; i < ids.size(); i++) {
                    if (ticks[i]) h.remove(ids.get(i)); else { h.add(ids.get(i)); count++; }
                }
                a.prefs.edit().putStringSet(a.hiddenKey(section), h).apply();
                Toast.makeText(a, count == 0 ? "All " + name + " categories are shown" : count + " " + name + " categories hidden", Toast.LENGTH_SHORT).show();
                after.run();
            });
        }, "Unable to load categories");
    }

    // ---------- Channels ----------

    /** Asks which channels to look at: the hidden ones, every channel, or one Live TV category. */
    static void channels(MainActivity a, Runnable after) {
        a.request("get_live_categories", o -> {
            JSONArray cats = o == null ? null : o.optJSONArray("list");
            List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
            int hiddenCount = a.hiddenChannels().size();
            ids.add("#hidden"); labels.add("⊘  Hidden channels  (" + hiddenCount + ")");
            ids.add(""); labels.add("▦  All channels");
            for (int i = 0; cats != null && i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                ids.add(c.optString("category_id"));
                labels.add("›  " + c.optString("category_name", "Category"));
            }
            new Dialogs.Builder(a).setTitle("Hide and show channels")
                    .setItems(labels.toArray(new String[0]), (d, w) -> {
                        if (w == 0 && hiddenCount == 0) { Toast.makeText(a, "No channels are hidden.", Toast.LENGTH_SHORT).show(); return; }
                        channelList(a, ids.get(w), labels.get(w).substring(3).trim(), after);
                    })
                    .setNegativeButton("Cancel", null).show();
        }, "Unable to load categories");
    }

    static void channelList(MainActivity a, String categoryId, String title, Runnable after) {
        boolean onlyHidden = categoryId.equals("#hidden");
        String action = "get_live_streams" + (onlyHidden || categoryId.isEmpty() ? "" : "&category_id=" + a.enc(categoryId));
        a.request(action, o -> {
            JSONArray list = o == null ? null : o.optJSONArray("list");
            Set<String> hidden = a.hiddenChannels();
            List<String> ids = new ArrayList<>(), names = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("stream_id");
                if (id.isEmpty() || !seen.add(id)) continue;
                if (onlyHidden && !hidden.contains(id)) continue;
                ids.add(id);
                names.add(c.optString("name", "Channel"));
            }
            if (ids.isEmpty()) {
                Menus.alert(a, onlyHidden ? "No hidden channels were found in this playlist." : "No channels in " + title + ".");
                return;
            }
            boolean[] shown = new boolean[ids.size()];
            for (int i = 0; i < ids.size(); i++) shown[i] = !hidden.contains(ids.get(i));
            tickList(a, title + "  •  ticked are shown", names, shown, ticks -> {
                Set<String> h = a.hiddenChannels();
                int hid = 0, back = 0;
                for (int i = 0; i < ids.size(); i++) {
                    boolean was = h.contains(ids.get(i));
                    if (ticks[i]) { if (h.remove(ids.get(i))) back++; } else { h.add(ids.get(i)); if (!was) hid++; }
                }
                a.prefs.edit().putStringSet(a.hiddenChannelsKey(), h).apply();
                String msg = hid == 0 && back == 0 ? "No changes" : (hid > 0 ? hid + " hidden" : "") + (hid > 0 && back > 0 ? ", " : "") + (back > 0 ? back + " shown again" : "");
                Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
                after.run();
            });
        }, "Unable to load channels");
    }

    // ---------- Shared tick list ----------

    interface Saved { void run(boolean[] ticked); }

    /**
     * A multi-select list: Save, Tick all / Untick all and Cancel at the top so a long list needs no
     * scrolling; OK on a row flips its tick. Nothing changes until Save.
     */
    static void tickList(MainActivity a, String heading, List<String> titles, boolean[] ticked, Saved onSave) {
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(4));
        TextView h = a.label(heading, 22);
        h.setTextColor(Theme.TEXT);
        head.addView(h);
        TextView count = a.label("", 16);
        count.setTextColor(Theme.MUTED);
        head.addView(count);
        LinearLayout bar = new LinearLayout(a);
        head.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog[] box = new AlertDialog[1];
        TextView save = a.button("✓ Save", bar, () -> { box[0].dismiss(); onSave.run(ticked); });
        TextView toggle = a.button("Untick all", bar, () -> { });
        TextView cancel = a.button("Cancel", bar, () -> box[0].dismiss());
        for (TextView t : new TextView[]{save, toggle, cancel}) { t.setLayoutParams(a.rowLp()); t.setTextSize(17); t.setGravity(Gravity.CENTER); }
        AlertDialog dialog = new Dialogs.Builder(a).setCustomTitle(head)
                .setMultiChoiceItems(titles.toArray(new String[0]), ticked, (d, which, checked) -> ticked[which] = checked).create();
        box[0] = dialog;
        Runnable label = () -> {
            int on = 0;
            for (boolean b : ticked) if (b) on++;
            toggle.setText(on > 0 ? "Untick all" : "Tick all");
            count.setText(on + " shown  •  " + (ticked.length - on) + " hidden  •  OK ticks or unticks one");
        };
        label.run();
        toggle.setOnClickListener(v -> {
            boolean any = false;
            for (boolean b : ticked) any |= b;
            ListView lv = dialog.getListView();
            for (int i = 0; i < ticked.length; i++) { ticked[i] = !any; lv.setItemChecked(i, !any); }
            label.run();
        });
        dialog.setOnShowListener(d -> {
            Dialogs.tint(dialog);
            ListView lv = dialog.getListView();
            lv.setOnItemClickListener((parent, view, pos, id) -> { ticked[pos] = lv.isItemChecked(pos); label.run(); });
            lv.requestFocus();
        });
        dialog.show();
    }
}
