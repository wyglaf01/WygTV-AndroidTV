package com.wygtv.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThemeTest {
    @Test public void everyThemeHasAPaletteAndUnknownFallsBackToRoyal() {
        for (String k : Theme.KEYS) { Theme.apply(k); assertEquals(k, Theme.key); assertNotEquals(0, Theme.PANEL); }
        Theme.apply("nonsense");
        assertEquals("royal", Theme.key);
        assertEquals(Theme.DEFAULT, Theme.KEYS[0]);
        assertFalse(Theme.steampunk());
        assertEquals(Theme.KEYS.length, Theme.LABELS.length);
    }
    @Test public void blueIsGoneAndMovesToRoyal() {
        for (String k : Theme.KEYS) assertNotEquals("blue", k);
        assertEquals("royal", Theme.migrate("blue"));
        assertEquals("royal", Theme.migrate(null));
        assertEquals("cyan", Theme.migrate("cyan"));
    }
    @Test public void everyThemeHasBronzeOrGoldTrim() {
        for (String k : Theme.KEYS) {
            Theme.apply(k);
            int r = (Theme.STROKE >> 16) & 0xff, g = (Theme.STROKE >> 8) & 0xff, b = Theme.STROKE & 0xff;
            assertTrue(k, r > g && g > b);
        }
        Theme.apply(Theme.DEFAULT);
    }
    @Test public void glassIsSeeThrough() {
        Theme.glass = false;
        int solid = Theme.surface(false)[0] >>> 24;
        assertEquals(0xff112233, Theme.pane(0xff112233));
        Theme.glass = true;
        assertTrue((Theme.surface(false)[0] >>> 24) < solid);
        assertTrue((Theme.pane(0xff112233) >>> 24) < 0xff);
        Theme.glass = false;
    }
    @Test public void alphaAndMix() {
        assertEquals(0x80123456, Theme.alpha(0xff123456, 0x80));
        assertEquals(0xff000000, Theme.mix(0xff000000, 0xffffffff, 0f));
        assertEquals(0xff808080, Theme.mix(0xff000000, 0xffffffff, 0.5f));
    }
}
