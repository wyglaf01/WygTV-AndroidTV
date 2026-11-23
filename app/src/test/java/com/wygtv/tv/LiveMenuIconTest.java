package com.wygtv.tv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LiveMenuIconTest {
    @Test public void iconOnly() {
        assertEquals("★", LiveMenu.iconLabel("★ Favorites", false));
        assertEquals("▦", LiveMenu.iconLabel("▦ Full TV guide", false));
        assertEquals("📡", LiveMenu.iconLabel("📡 Local", false));
    }

    @Test public void sharedIconAddsInitial() {
        assertEquals("◆W", LiveMenu.iconLabel("◆ Wyglaf", true));
        assertEquals("›S", LiveMenu.iconLabel("› sports", true));
        assertEquals("▼M", LiveMenu.iconLabel("▼  My playlist  ●", true));
    }

    @Test public void noIconUsesInitial() {
        assertEquals("A", LiveMenu.iconLabel("    All categories", false));
        assertEquals("", LiveMenu.iconOf("All categories"));
        assertEquals("←", LiveMenu.iconOf("← Home"));
    }
}
