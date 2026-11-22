package com.wygtv.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class XmltvTest {
    @Test public void parsesTimesWithOffsets() {
        assertEquals(1795275000000L, Xmltv.parseTime("20261121153000 +0000"));
        assertEquals(1795275000000L, Xmltv.parseTime("20261121163000 +0100"));
        assertEquals(1795275000000L, Xmltv.parseTime("20261121103000 -0500"));
        assertEquals(-1, Xmltv.parseTime("garbage"));
        assertEquals(-1, Xmltv.parseTime(null));
    }

    @Test public void upcomingStartsWithWhatIsOnNow() {
        List<Xmltv.Program> all = Arrays.asList(new Xmltv.Program(0, 100, "old", ""), new Xmltv.Program(100, 200, "now", ""),
                new Xmltv.Program(200, 300, "next", ""), new Xmltv.Program(300, 400, "later", ""));
        List<Xmltv.Program> up = Xmltv.upcoming(all, 150, 2);
        assertEquals(2, up.size());
        assertEquals("now", up.get(0).title);
        assertEquals("next", up.get(1).title);
    }

    @Test public void looseNamesMatchAcrossProviders() {
        assertEquals("cnn", Xmltv.loose("US: CNN HD"));
        assertEquals("cnn", Xmltv.loose("|US| CNN FHD"));
        assertEquals("cnn", Xmltv.loose("CNN.us"));
        assertEquals("cnn", Xmltv.loose("CNN (East)"));
        assertEquals("bbcone", Xmltv.loose("UK | BBC One"));
        assertEquals("aande", Xmltv.loose("A&E"));
        assertEquals("", Xmltv.loose(null));
    }

    @Test public void findFallsBackToLooseName() {
        Xmltv.Guide g = new Xmltv.Guide();
        g.byChannel.put("CNN.us", Arrays.asList(new Xmltv.Program(0, 100, "News", "")));
        g.addLoose("CNN.us", "CNN.us");
        assertEquals(1, g.find("", "US: CNN HD").size());
        assertEquals(1, g.find("cnn-unknown-id", "CNN").size());
        assertTrue(g.find("", "ESPN").isEmpty());
    }
}
