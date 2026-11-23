package com.wygtv.tv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MenuIconsTest {
    @Test public void iconOnly() {
        assertEquals("★", MenuIcons.iconLabel("★ Favorites", false));
        assertEquals("▦", MenuIcons.iconLabel("▦ Full TV guide", false));
        assertEquals("📡", MenuIcons.iconLabel("📡 Local", false));
    }

    @Test public void sharedIconAddsInitial() {
        assertEquals("◆W", MenuIcons.iconLabel("◆ Wyglaf", true));
        assertEquals("›S", MenuIcons.iconLabel("› sports", true));
        assertEquals("▼M", MenuIcons.iconLabel("▼  My playlist  ●", true));
    }

    @Test public void noIconUsesInitial() {
        assertEquals("A", MenuIcons.iconLabel("    All categories", false));
        assertEquals("", MenuIcons.iconOf("All categories"));
        assertEquals("←", MenuIcons.iconOf("← Home"));
    }
}
