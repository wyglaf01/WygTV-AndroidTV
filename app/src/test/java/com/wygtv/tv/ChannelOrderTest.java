package com.wygtv.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ChannelOrderTest {
    private static JSONArray channels() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("name", "CNN").put("num", "12"))
                .put(new JSONObject().put("name", "abc").put("num", "5.1"))
                .put(new JSONObject().put("name", "Zee").put("num", ""))
                .put(new JSONObject().put("name", "BBC").put("num", "5"));
    }

    private static String names(JSONArray a) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < a.length(); i++) s.append(i == 0 ? "" : ",").append(a.optJSONObject(i).optString("name"));
        return s.toString();
    }

    @Test public void playlistOrderKeepsTheList() throws Exception {
        JSONArray in = channels();
        assertSame(in, ChannelOrder.sort(in, "playlist"));
        assertSame(in, ChannelOrder.sort(in, "unknown"));
    }

    @Test public void byNumberPutsNumberlessLast() throws Exception {
        assertEquals("BBC,abc,CNN,Zee", names(ChannelOrder.sort(channels(), "number")));
    }

    @Test public void byName() throws Exception {
        assertEquals("abc,BBC,CNN,Zee", names(ChannelOrder.sort(channels(), "name")));
        assertEquals("Zee,CNN,BBC,abc", names(ChannelOrder.sort(channels(), "name_desc")));
    }

    @Test public void externalPlayerScope() {
        assertFalse(ExternalPlayer.usedFor("", "all", "live"));
        assertTrue(ExternalPlayer.usedFor("org.videolan.vlc", "all", "vod"));
        assertTrue(ExternalPlayer.usedFor("ask", "live", "live"));
        assertFalse(ExternalPlayer.usedFor("ask", "live", "series"));
        assertTrue(ExternalPlayer.usedFor("ask", "vod", "series"));
        assertFalse(ExternalPlayer.usedFor("ask", "vod", "live"));
    }
}
