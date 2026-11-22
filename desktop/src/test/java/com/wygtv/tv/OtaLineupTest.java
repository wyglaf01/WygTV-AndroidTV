package com.wygtv.tv;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OtaLineupTest {
    private static final String LINEUP = "["
            + "{\"GuideNumber\":\"4.1\",\"GuideName\":\"WNBC-HD\",\"HD\":1,\"URL\":\"http://192.168.1.50:5004/auto/v4.1\"},"
            + "{\"GuideNumber\":\"4.2\",\"GuideName\":\"COZI\",\"URL\":\"http://192.168.1.50:5004/auto/v4.2\"},"
            + "{\"GuideNumber\":\"7.1\",\"GuideName\":\"WABC-DT\",\"URL\":\"http://192.168.1.50:5004/auto/v7.1\"},"
            + "{\"GuideNumber\":\"702\",\"GuideName\":\"Premium\",\"DRM\":1,\"URL\":\"http://192.168.1.50:5004/auto/v702\"},"
            + "{\"GuideNumber\":\"9.1\",\"GuideName\":\"NoUrl\"}]";

    @Test public void groupsSubchannelsAndSkipsDrm() throws Exception {
        List<OtaLineup.Channel> c = OtaLineup.parse(LINEUP);
        assertEquals(3, c.size());
        assertEquals("Channel 4  ·  WNBC", c.get(0).group);
        assertEquals("Channel 4  ·  WNBC", c.get(1).group);
        assertEquals("Channel 7  ·  WABC", c.get(2).group);
        assertTrue(c.get(0).hd);
        assertEquals("4.2  COZI", c.get(1).title());
        assertEquals("http://192.168.1.50:5004/auto/v7.1", c.get(2).url);
    }

    @Test public void guideRowsFollowGroups() throws Exception {
        OtaGuide g = new OtaGuide();
        g.setChannels(OtaLineup.parse(LINEUP));
        // header, 4.1, 4.2, header, 7.1
        assertEquals(5, g.rows.size());
        assertEquals(1, g.step(0));
        g.select(1);
        assertEquals(2, g.step(1));
        assertEquals(4, g.step(2));
        assertEquals(4, g.step(-1)); // wraps round
        assertEquals(4, g.indexOf("7.1"));
    }
}
