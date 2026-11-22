package com.wygtv.tv;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SourceLabelTest {
    @Test public void namesThePlaylistAndItsKind() throws Exception {
        assertEquals("", Library.sourceLabel(null));
        assertEquals("My IPTV  ·  Xtream Codes",
                Library.sourceLabel(new JSONObject().put("type", "xtream").put("title", "My IPTV")));
        assertEquals("Free TV  ·  M3U playlist",
                Library.sourceLabel(new JSONObject().put("type", "m3u").put("title", "Free TV").put("url", "http://x/list.m3u")));
        assertEquals("📡 Antenna TV  ·  HDHomeRun antenna",
                Library.sourceLabel(new JSONObject().put("type", "m3u").put("title", "HDHomeRun FLEX").put("url", "http://192.168.1.20/lineup.json")));
    }
}
