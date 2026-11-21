package com.wygtv.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A backup written from one TV and restored on another brings back every kind of saved value. */
public class BackupTest {
    private static final String ACC = "account_aG9zdAp1c2Vy_", ACC2 = "account_b3RoZXIK_";

    private static FakePrefs sample() {
        FakePrefs p = new FakePrefs();
        p.edit()
                .putString("playlists", "[{\"id\":\"a\",\"type\":\"xtream\"},{\"id\":\"b\",\"type\":\"m3u\"}]")
                .putString("active_playlist", "a")
                .putString("theme", "green")
                .putBoolean("live_only", true)
                .putString("start_screen", "guide")
                .putString("live_format", "m3u8")
                .putString(ACC + "guide_source", "fav")
                .putString(ACC + "fav_live_101", "News HD").putString(ACC + "ext_live_101", "ts")
                .putString(ACC2 + "fav_live_m1a2", "Sports 1")
                .putString(ACC + "fav_vod_55", "")   // removed favorite: kept as empty, not counted
                .putString("virtual_categories", "[{\"id\":\"vc_1\",\"name\":\"Sports\",\"channels\":[{\"stream_id\":\"101\",\"playlist\":\"a\"}]}]")
                .putString(ACC + "mylist_vod", "[{\"stream_id\":\"9\"},{\"stream_id\":\"10\"}]")
                .putStringSet(ACC + "hidden_categories_live", new HashSet<>(Arrays.asList("3", "4")))
                .putStringSet(ACC + "hidden_channels_live", new HashSet<>(Arrays.asList("77")))
                .putLong("guide_loaded", 1700000000000L)
                .putInt("volume", 7)
                .putFloat("scale", 1.5f)
                .commit();
        return p;
    }

    @Test public void roundTripRestoresEveryValue() throws Exception {
        FakePrefs from = sample();
        String file = Backup.export(from).toString(2);
        FakePrefs to = new FakePrefs();
        to.edit().putString("left_over", "x").commit();
        Backup.apply(to, new JSONObject(file).getJSONObject("prefs"));
        assertEquals(from.getAll(), to.getAll());
    }

    @Test public void summaryCountsWhatIsInside() throws Exception {
        String s = Backup.summary(Backup.export(sample()).getJSONObject("prefs"));
        assertEquals("2 playlists, 2 favorites, 1 own category, 2 in My List, 3 hidden items and all settings (theme, app mode, start-up, TV guide choice)", s);
    }

    @Test public void badBackupChangesNothing() throws Exception {
        FakePrefs to = sample();
        Map<String, ?> before = new HashMap<>(to.getAll());
        JSONObject broken = new JSONObject().put("theme", new JSONObject().put("t", "s").put("v", "blue")).put("live_only", new JSONObject().put("t", "b"));
        try { Backup.apply(to, broken); } catch (Exception expected) { }
        assertEquals(before, to.getAll());
        assertTrue(to.getBoolean("live_only", false));
    }

    /** In-memory SharedPreferences: edits apply only on commit/apply, like the real one. */
    static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> data = new HashMap<>();
        @Override public Map<String, ?> getAll() { return new HashMap<>(data); }
        @Override public String getString(String k, String d) { Object v = data.get(k); return v == null ? d : (String) v; }
        @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String k, Set<String> d) { Object v = data.get(k); return v == null ? d : (Set<String>) v; }
        @Override public int getInt(String k, int d) { Object v = data.get(k); return v == null ? d : (Integer) v; }
        @Override public long getLong(String k, long d) { Object v = data.get(k); return v == null ? d : (Long) v; }
        @Override public float getFloat(String k, float d) { Object v = data.get(k); return v == null ? d : (Float) v; }
        @Override public boolean getBoolean(String k, boolean d) { Object v = data.get(k); return v == null ? d : (Boolean) v; }
        @Override public boolean contains(String k) { return data.containsKey(k); }
        @Override public Editor edit() { return new Ed(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        final class Ed implements Editor {
            final Map<String, Object> put = new HashMap<>();
            final Set<String> removed = new HashSet<>();
            boolean clear;
            @Override public Editor putString(String k, String v) { put.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { put.put(k, new HashSet<>(v)); return this; }
            @Override public Editor putInt(String k, int v) { put.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { put.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { put.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { put.put(k, v); return this; }
            @Override public Editor remove(String k) { removed.add(k); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() { if (clear) data.clear(); for (String k : removed) data.remove(k); data.putAll(put); return true; }
            @Override public void apply() { commit(); }
        }
    }
}
