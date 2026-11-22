package com.wygtv.tv;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class HdhrTest {

    @Test public void discoverRequestMatchesLibhdhomerun() {
        // type 0x0002, length 12, device type FFFFFFFF, device id FFFFFFFF, then CRC32 little endian.
        byte[] req = Hdhr.discoverRequest();
        assertEquals(4 + 12 + 4, req.length);
        assertEquals(0x00, req[0]); assertEquals(0x02, req[1]);
        assertEquals(0x00, req[2]); assertEquals(12, req[3]);
        assertEquals(Hdhr.TAG_DEVICE_TYPE, req[4]); assertEquals(4, req[5]);
        assertEquals((byte) 0xFF, req[6]);
        assertEquals(Hdhr.TAG_DEVICE_ID, req[10]); assertEquals(4, req[11]);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(req, 0, 16);
        long c = crc.getValue();
        assertEquals((byte) c, req[16]);
        assertEquals((byte) (c >> 24), req[19]);
    }

    private static byte[] reply(long deviceType, String base, boolean withBase) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        Hdhr.tag(p, Hdhr.TAG_DEVICE_TYPE, Hdhr.be32(deviceType));
        Hdhr.tag(p, Hdhr.TAG_DEVICE_ID, Hdhr.be32(0x1234ABCDL));
        Hdhr.tag(p, Hdhr.TAG_TUNER_COUNT, new byte[]{4});
        if (withBase) Hdhr.tag(p, Hdhr.TAG_BASE_URL, base.getBytes());
        return Hdhr.frame(Hdhr.TYPE_DISCOVER_RPY, p.toByteArray());
    }

    @Test public void readsATunerReply() {
        byte[] r = reply(Hdhr.DEVICE_TYPE_TUNER, "http://192.168.1.50:80/", true);
        Hdhr.Device d = Hdhr.parseReply(r, r.length, "192.168.1.50");
        assertNotNull(d);
        assertEquals("1234ABCD", d.id);
        assertEquals(4, d.tuners);
        assertEquals("http://192.168.1.50:80", d.base);
        assertEquals("http://192.168.1.50:80/lineup.json", d.lineupUrl);
    }

    @Test public void oldFirmwareWithoutBaseUrlUsesTheSenderAddress() {
        byte[] r = reply(Hdhr.DEVICE_TYPE_TUNER, "", false);
        Hdhr.Device d = Hdhr.parseReply(r, r.length, "10.0.0.9");
        assertEquals("http://10.0.0.9", d.base);
    }

    @Test public void rejectsBadCrcStorageDevicesAndOtherPackets() {
        byte[] r = reply(Hdhr.DEVICE_TYPE_TUNER, "http://x", true);
        r[r.length - 1] ^= 1;
        assertNull(Hdhr.parseReply(r, r.length, "1.2.3.4"));
        byte[] storage = reply(5, "http://x", true);
        assertNull(Hdhr.parseReply(storage, storage.length, "1.2.3.4"));
        byte[] req = Hdhr.discoverRequest();
        assertNull(Hdhr.parseReply(req, req.length, "1.2.3.4"));
        assertNull(Hdhr.parseReply(new byte[3], 3, "1.2.3.4"));
    }

    @Test public void longTagLengthsUseTwoBytes() {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        Hdhr.tag(p, Hdhr.TAG_DEVICE_TYPE, Hdhr.be32(1));
        StringBuilder auth = new StringBuilder();
        for (int i = 0; i < 200; i++) auth.append('a');
        Hdhr.tag(p, Hdhr.TAG_DEVICE_AUTH, auth.toString().getBytes());
        Hdhr.tag(p, Hdhr.TAG_BASE_URL, "http://h".getBytes());
        byte[] r = Hdhr.frame(Hdhr.TYPE_DISCOVER_RPY, p.toByteArray());
        Hdhr.Device d = Hdhr.parseReply(r, r.length, null);
        assertEquals(200, d.auth.length());
        assertEquals("http://h", d.base);
    }

    @Test public void readsDiscoverJson() throws Exception {
        Hdhr.Device d = Hdhr.parseDiscoverJson("{\"FriendlyName\":\"HDHomeRun FLEX 4K\",\"ModelNumber\":\"HDFX-4K\",\"DeviceID\":\"10ABCDEF\","
                + "\"DeviceAuth\":\"abc\",\"BaseURL\":\"http://192.168.1.50\",\"LineupURL\":\"http://192.168.1.50/lineup.json\",\"TunerCount\":4}", "http://hdhomerun.local");
        assertEquals("HDHomeRun FLEX 4K", d.name);
        assertEquals("http://192.168.1.50/lineup.json", d.lineupUrl);
        assertEquals("abc", d.auth);
        assertEquals(4, d.tuners);
    }

    @Test(expected = IllegalStateException.class) public void recordEngineIsNotATuner() throws Exception {
        Hdhr.parseDiscoverJson("{\"FriendlyName\":\"RECORD\",\"StorageID\":\"x\",\"StorageURL\":\"http://1.2.3.4:59090/recorded_files.json\"}", "http://1.2.3.4:59090");
    }

    @Test public void cloudDiscoveryListsTunersOnly() {
        List<String> got = Hdhr.parseCloud("[{\"DeviceID\":\"1\",\"LocalIP\":\"192.168.1.50\",\"BaseURL\":\"http://192.168.1.50/\"},"
                + "{\"DeviceID\":\"2\",\"LocalIP\":\"192.168.1.51\"},{\"StorageID\":\"s\",\"LocalIP\":\"192.168.1.9\"}]");
        assertEquals(2, got.size());
        assertEquals("http://192.168.1.50", got.get(0));
        assertEquals("http://192.168.1.51", got.get(1));
        assertTrue(Hdhr.parseCloud("not json").isEmpty());
    }

    @Test public void typedAddresses() {
        assertEquals("http://hdhomerun.local", Hdhr.baseFromInput(" hdhomerun.local "));
        assertEquals("http://192.168.1.50", Hdhr.baseFromInput("192.168.1.50"));
        assertEquals("http://192.168.1.50:8080", Hdhr.baseFromInput("192.168.1.50:8080"));
        assertEquals("http://hdhomerun.local", Hdhr.baseFromInput("http://hdhomerun.local/lineup.html"));
        assertEquals("", Hdhr.baseFromInput(""));
        assertEquals("http://192.168.1.50:80", Hdhr.baseOfLineup("http://192.168.1.50:80/lineup.json"));
    }

    @Test public void lineupLinks() {
        assertTrue(Hdhr.isLineupUrl("http://192.168.1.50/lineup.json"));
        assertTrue(Hdhr.isLineupUrl("http://192.168.1.50/lineup.json?show=found"));
        assertFalse(Hdhr.isLineupUrl("http://example.com/playlist.m3u"));
        assertFalse(Hdhr.isLineupUrl(null));
    }

    private static final String LINEUP = "[{\"GuideNumber\":\"4.1\",\"GuideName\":\"WNBC-HD\",\"VideoCodec\":\"MPEG2\",\"AudioCodec\":\"AC3\",\"HD\":1,\"URL\":\"http://192.168.1.50:5004/auto/v4.1\"},"
            + "{\"GuideNumber\":\"4.2\",\"GuideName\":\"COZI\",\"URL\":\"http://192.168.1.50:5004/auto/v4.2\"},"
            + "{\"GuideNumber\":\"7.1\",\"GuideName\":\"WABC \\\"HD\\\"\",\"URL\":\"http://192.168.1.50:5004/auto/v7.1\"},"
            + "{\"GuideNumber\":\"702\",\"GuideName\":\"HBO\",\"DRM\":1,\"URL\":\"http://192.168.1.50:5004/auto/v702\"},"
            + "{\"GuideNumber\":\"703\",\"GuideName\":\"SHO\",\"Tags\":\"favorite,drm\",\"URL\":\"http://192.168.1.50:5004/auto/v703\"},"
            + "{\"GuideNumber\":\"\",\"GuideName\":\"Mystery\",\"URL\":\"http://192.168.1.50:5004/auto/vx\"},"
            + "{\"GuideNumber\":\"9.1\",\"GuideName\":\"NoUrl\"}]";

    @Test public void lineupBecomesAPlaylistGroupedByChannelNumber() throws Exception {
        String text = Hdhr.lineupToM3u(LINEUP);
        assertTrue(M3u.looksLikePlaylist(text));
        M3u.Playlist p = M3u.parse(text);
        assertEquals(4, p.channels.size()); // DRM and URL-less channels left out
        M3u.Channel wnbc = p.channels.get(0);
        assertEquals("4.1  WNBC-HD  HD", wnbc.name);
        assertEquals("4.1", wnbc.number);
        assertEquals("4.1", wnbc.tvgId);
        assertEquals("WNBC-HD", wnbc.tvgName);
        assertEquals("Channel 4  ·  WNBC", wnbc.group);
        assertEquals("http://192.168.1.50:5004/auto/v4.1", wnbc.url);
        assertEquals("Channel 4  ·  WNBC", p.channels.get(1).group);
        assertEquals("WABC 'HD'", p.channels.get(2).tvgName);
        assertEquals(Hdhr.OTHER_GROUP, p.channels.get(3).group);
        assertEquals(3, p.groups.size());
    }

    @Test public void channelNumberParts() {
        assertEquals("4", Hdhr.major("4.1"));
        assertEquals("4", Hdhr.major("4-1"));
        assertEquals("702", Hdhr.major("702"));
        assertEquals("", Hdhr.major(""));
        assertEquals("WNBC", Hdhr.station("WNBC-HD"));
        assertEquals("KQED+", Hdhr.station("KQED+"));
        assertEquals("WABC", Hdhr.station("WABC-DT2"));
    }

    @Test public void readsTheHdhomerunGuide() throws Exception {
        Map<String, List<Xmltv.Program>> g = new LinkedHashMap<>();
        long latest = Hdhr.parseGuide("[{\"GuideNumber\":\"4.1\",\"GuideName\":\"WNBC\",\"Guide\":["
                + "{\"StartTime\":1700003600,\"EndTime\":1700007200,\"Title\":\"Late News\",\"Synopsis\":\"Headlines.\"},"
                + "{\"StartTime\":1700000000,\"EndTime\":1700003600,\"Title\":\"Quiz\",\"EpisodeTitle\":\"Finals\",\"Synopsis\":\"The big one.\"},"
                + "{\"StartTime\":5,\"EndTime\":1}]},{\"GuideNumber\":\"7.1\"}]", g);
        assertEquals(1700007200L, latest);
        List<Xmltv.Program> p = g.get("4.1");
        assertEquals(2, p.size());
        assertEquals("Quiz", p.get(0).title); // sorted by start
        assertEquals("Finals — The big one.", p.get(0).desc);
        assertEquals(1700000000000L, p.get(0).start);
        // A second window that overlaps is not added twice.
        Hdhr.parseGuide("[{\"GuideNumber\":\"4.1\",\"Guide\":[{\"StartTime\":1700003600,\"EndTime\":1700007200,\"Title\":\"Late News\"}]}]", g);
        assertEquals(2, g.get("4.1").size());
        assertTrue(Hdhr.guideUrl("a+b", 0).endsWith("DeviceAuth=a%2Bb"));
        assertTrue(Hdhr.guideUrl("a", 99).endsWith("&Start=99"));
    }
}
