package com.wygtv.tv;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TabloTest {

    @Test public void addressesGetThePort() {
        assertEquals("http://192.168.1.60:8885", Tablo.baseFromInput("192.168.1.60"));
        assertEquals("http://192.168.1.60:8885", Tablo.baseFromInput(" http://192.168.1.60/ "));
        assertEquals("http://192.168.1.60:9000", Tablo.baseFromInput("192.168.1.60:9000"));
        assertEquals("", Tablo.baseFromInput("  "));
    }

    @Test public void channelsLinkIsATunerAndNotAnHdhomerun() {
        String link = Tablo.channelsUrl("http://192.168.1.60:8885");
        assertEquals("http://192.168.1.60:8885/guide/channels", link);
        assertTrue(Tablo.isChannelsUrl(link));
        assertFalse(Tablo.isChannelsUrl("http://192.168.1.60/guide/channels")); // not the Tablo port
        assertFalse(Tablo.isChannelsUrl("http://192.168.1.60:8885/guide/channels/767579"));
        assertFalse(Hdhr.isLineupUrl(link));
        assertEquals("http://192.168.1.60:8885", Tablo.baseOf(link));
    }

    @Test public void watchLinks() {
        String w = Tablo.watchUrl("http://192.168.1.60:8885", "767579");
        assertEquals("http://192.168.1.60:8885/guide/channels/767579/watch.m3u8", w);
        assertTrue(Tablo.isWatchUrl(w));
        assertFalse(Tablo.isWatchUrl("http://192.168.1.60/stream/pl.m3u8?abc"));
        assertFalse(Tablo.isWatchUrl("http://example.com/live/user/pass/1.ts"));
        assertFalse(Tablo.isWatchUrl(null));
    }

    @Test public void cloudLookup() {
        List<Tablo.Device> d = Tablo.parseCloud("{\"success\":true,\"cpes\":[{\"serverid\":\"SID_ABC\",\"name\":\"Den\",\"private_ip\":\"192.168.1.60\",\"public_ip\":\"1.2.3.4\"},{\"serverid\":\"SID_X\"}]}");
        assertEquals(1, d.size());
        assertEquals("SID_ABC", d.get(0).id);
        assertEquals("http://192.168.1.60:8885", d.get(0).base);
        assertTrue(Tablo.parseCloud("not json").isEmpty());
    }

    @Test public void serverInfo() throws Exception {
        Tablo.Device d = Tablo.parseServerInfo("{\"server_id\":\"SID_ABC\",\"name\":\"Tablo\",\"model\":{\"name\":\"Tablo QUAD\",\"tuners\":4,\"wifi\":true}}", "http://192.168.1.60:8885");
        assertEquals("Tablo QUAD", d.model);
        assertEquals(4, d.tuners);
        assertEquals("Tablo QUAD  •  192.168.1.60  •  4 tuners", d.summary());
    }

    @Test(expected = Tablo.LockedException.class) public void lockedFirmwareIsReported() throws Exception {
        Tablo.parseServerInfo("{\"error\":{\"code\":\"unauthorized\",\"details\":null,\"description\":\"Authentication failure\"}}", "http://x:8885");
    }

    @Test(expected = Tablo.LockedException.class) public void lockedChannelList() throws Exception {
        Tablo.parseChannelPaths("{\"error\":{\"code\":\"unauthorized\",\"description\":\"Authentication failure\"}}");
    }

    @Test public void channelsBecomeM3u() throws Exception {
        List<String> paths = Tablo.parseChannelPaths("[\"/guide/channels/2\",\"/guide/channels/1\",\"/recordings/x\"]");
        assertEquals(Arrays.asList("/guide/channels/2", "/guide/channels/1"), paths);
        JSONObject details = new JSONObject()
                .put("/guide/channels/1", new JSONObject("{\"object_id\":1,\"path\":\"/guide/channels/1\",\"channel\":{\"call_sign\":\"WNBC-HD\",\"major\":4,\"minor\":1,\"network\":\"NBC\",\"resolution\":\"hd_1080\"}}"))
                .put("/guide/channels/2", new JSONObject("{\"object_id\":2,\"path\":\"/guide/channels/2\",\"channel\":{\"call_sign\":\"COZI\",\"major\":4,\"minor\":2,\"network\":null,\"resolution\":\"sd\"}}"));
        String m3u = Tablo.channelsToM3u("http://192.168.1.60:8885", paths, details);
        M3u.Playlist p = M3u.parse(m3u);
        assertEquals(2, p.channels.size());
        M3u.Channel first = p.channels.get(0);
        assertEquals("4.1", first.number);
        assertEquals("Channel 4  ·  WNBC", first.group);
        assertTrue(first.name.startsWith("4.1  WNBC-HD  HD"));
        assertEquals("http://192.168.1.60:8885/guide/channels/1/watch.m3u8", first.url);
        assertEquals("4.2  COZI", p.channels.get(1).name);
    }

    @Test public void watchAnswer() throws Exception {
        assertEquals("http://192.168.1.60:80/stream/pl.m3u8?x=1",
                Tablo.parseWatch("{\"token\":\"t\",\"playlist_url\":\"http://192.168.1.60:80/stream/pl.m3u8?x=1\"}", "http://192.168.1.60:8885"));
        assertEquals("http://192.168.1.60/stream/pl.m3u8",
                Tablo.parseWatch("{\"playlist_url\":\"/stream/pl.m3u8\"}", "http://192.168.1.60:8885"));
    }

    @Test(expected = IllegalStateException.class) public void watchWithoutLink() throws Exception {
        Tablo.parseWatch("{\"error\":{\"code\":\"no_tuners\",\"description\":\"All tuners are in use\"}}", "http://x:8885");
    }

    @Test public void guideTimes() {
        assertEquals(1672707600000L, Tablo.parseTime("2023-01-03T01:00Z"));
        assertEquals(1672707630000L, Tablo.parseTime("2023-01-03T01:00:30Z"));
        assertEquals(-1, Tablo.parseTime("soon"));
    }

    @Test public void guideAirings() throws Exception {
        java.util.Map<String, String> numbers = new java.util.HashMap<>();
        numbers.put("/guide/channels/9", "7.1");
        java.util.Map<String, List<Xmltv.Program>> g = new java.util.LinkedHashMap<>();
        // An episode with its channel embedded.
        assertTrue(Tablo.addAiring(new JSONObject("{\"airing_details\":{\"datetime\":\"2023-01-03T01:00Z\",\"duration\":1800,\"show_title\":\"Jeopardy!\","
                + "\"channel\":{\"object_id\":1,\"channel\":{\"major\":4,\"minor\":1}}},\"episode\":{\"title\":\"Finals\",\"description\":\"The big one.\"}}"), numbers, g));
        // A movie that only names its channel path.
        assertTrue(Tablo.addAiring(new JSONObject("{\"airing_details\":{\"datetime\":\"2023-01-03T02:00Z\",\"duration\":7200,\"show_title\":\"Heat\","
                + "\"channel_path\":\"/guide/channels/9\"},\"movie_airing\":{\"description\":\"Cops and robbers.\"}}"), numbers, g));
        assertFalse(Tablo.addAiring(new JSONObject("{\"airing_details\":{\"datetime\":\"x\",\"duration\":60}}"), numbers, g));
        assertFalse(Tablo.addAiring(null, numbers, g));
        Xmltv.Program ep = g.get("4.1").get(0);
        assertEquals("Jeopardy!", ep.title);
        assertEquals("Finals — The big one.", ep.desc);
        assertEquals(1672707600000L + 1800_000L, ep.stop);
        assertEquals("Cops and robbers.", g.get("7.1").get(0).desc);
        // The same airing again is not added twice.
        Tablo.addAiring(new JSONObject("{\"airing_details\":{\"datetime\":\"2023-01-03T01:00Z\",\"duration\":1800,\"show_title\":\"Jeopardy!\","
                + "\"channel\":{\"channel\":{\"major\":4,\"minor\":1}}}}"), numbers, g);
        assertEquals(1, g.get("4.1").size());
        assertEquals(java.util.Arrays.asList("/guide/series/episodes/1"), Tablo.parseAiringPaths("[\"/guide/series/episodes/1\"]"));
    }
}
