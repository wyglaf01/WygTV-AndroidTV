package com.wygtv.tv;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class MenuOrderTest {
    @Test public void emptyIsDefault() {
        assertEquals(Arrays.asList(MenuOrder.DEFAULT), MenuOrder.parse(""));
        assertEquals(Arrays.asList(MenuOrder.DEFAULT), MenuOrder.parse(null));
    }

    @Test public void savedOrderKeptAndMissingItemsAddedAtDefaultPlace() {
        List<String> o = MenuOrder.parse("settings,home,bogus,guide,fav,live,home,vod,series,msearch,playlists");
        assertEquals(Arrays.asList("settings", "home", "guide", "fav", "live", "vod", "series", "antenna", "msearch", "playlists"), o);
    }

    @Test public void moveUpAndDownStopsAtEnds() {
        List<String> o = new ArrayList<>(Arrays.asList("a", "b", "c"));
        assertEquals(0, MenuOrder.move(o, 1, -1));
        assertEquals(Arrays.asList("b", "a", "c"), o);
        assertEquals(0, MenuOrder.move(o, 0, -1));
        assertEquals(2, MenuOrder.move(o, 1, 1));
        assertEquals(Arrays.asList("b", "c", "a"), o);
        assertEquals("b,c,a", MenuOrder.join(o));
    }

    @Test public void gapSitsAbovePlaylistsAndSettingsAtTheEnd() {
        assertEquals(8, MenuOrder.gapBefore(Arrays.asList(MenuOrder.DEFAULT)));
        assertEquals(2, MenuOrder.gapBefore(Arrays.asList("settings", "home", "playlists")));
        assertEquals(3, MenuOrder.gapBefore(Arrays.asList("settings", "playlists", "home")));
    }
}
