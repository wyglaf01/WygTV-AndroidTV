package com.wygtv.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class M3uTest {
    private static final String SAMPLE = "﻿#EXTM3U url-tvg=\"http://epg.example/guide.xml.gz,http://other\"\n"
            + "#EXTINF:-1 tvg-id=\"bbc1.uk\" tvg-name=\"BBC One\" tvg-logo=\"http://logo/bbc.png\" group-title=\"UK\",BBC One HD\n"
            + "http://s.example/live/1.ts\n"
            + "#EXTINF:-1 tvg-chno=\"7\" group-title=\"News, World\",CNN, International\r\n"
            + "#EXTVLCOPT:http-user-agent=Foo\n"
            + "http://s.example/live/2.m3u8\n"
            + "#EXTINF:0,No Group Channel\n#EXTGRP:Sports\nhttp://s.example/live/3.ts\n"
            + "#EXTINF:-1,Plain\nhttp://s.example/live/4.ts\n"
            + "#EXTINF:-1 group-title=\"UK\",BBC One copy\nhttp://s.example/live/1.ts\n";

    @Test public void readsChannelsGroupsAndAttributes() {
        M3u.Playlist p = M3u.parse(SAMPLE);
        assertEquals(5, p.channels.size());
        assertEquals("http://epg.example/guide.xml.gz", p.guideUrl);
        M3u.Channel bbc = p.channels.get(0);
        assertEquals("BBC One HD", bbc.name);
        assertEquals("bbc1.uk", bbc.tvgId);
        assertEquals("http://logo/bbc.png", bbc.logo);
        assertEquals("UK", bbc.group);
        M3u.Channel cnn = p.channels.get(1);
        assertEquals("CNN, International", cnn.name);
        assertEquals("News, World", cnn.group);
        assertEquals("7", cnn.number);
        assertEquals("Sports", p.channels.get(2).group);
        assertEquals(M3u.UNGROUPED, p.channels.get(3).group);
        assertEquals(java.util.Arrays.asList("UK", "News, World", "Sports", M3u.UNGROUPED), p.groups);
    }

    @Test public void idsAreStableAndUnique() {
        M3u.Playlist a = M3u.parse(SAMPLE), b = M3u.parse(SAMPLE);
        assertEquals(a.channels.get(1).id, b.channels.get(1).id);
        assertEquals(M3u.idFor("http://s.example/live/1.ts") + "-2", a.channels.get(4).id);
        assertEquals(5, a.byId.size());
        assertNotEquals(M3u.idFor("http://a/1"), M3u.idFor("http://a/2"));
    }

    @Test public void tellsPlaylistsFromErrorPages() {
        assertTrue(M3u.looksLikePlaylist(SAMPLE));
        assertFalse(M3u.looksLikePlaylist("<html>403 Forbidden</html>"));
        assertFalse(M3u.looksLikePlaylist(null));
    }

    @Test public void recognisesXtreamGetPhpLinks() {
        String[] x = M3u.xtreamLogin("http://prov.example:8080/get.php?username=bob&password=p%40ss&type=m3u_plus&output=ts");
        assertArrayEquals(new String[]{"http://prov.example:8080", "bob", "p@ss"}, x);
        assertNull(M3u.xtreamLogin("http://prov.example/playlist.m3u"));
        assertNull(M3u.xtreamLogin("http://prov.example/get.php?username=bob"));
    }
}
