package com.wygtv.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThemeTest {
    @Test public void everyThemeHasAPaletteAndUnknownFallsBackToSteampunk() {
        for (String k : Theme.KEYS) { Theme.apply(k); assertEquals(k, Theme.key); assertNotEquals(0, Theme.PANEL); }
        Theme.apply("nonsense");
        assertTrue(Theme.steampunk());
        assertEquals(Theme.KEYS.length, Theme.LABELS.length);
    }
    @Test public void alphaAndMix() {
        assertEquals(0x80123456, Theme.alpha(0xff123456, 0x80));
        assertEquals(0xff000000, Theme.mix(0xff000000, 0xffffffff, 0f));
        assertEquals(0xff808080, Theme.mix(0xff000000, 0xffffffff, 0.5f));
    }
}
