package com.wygtv.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Runs for both apps: each one must update from its own APK and keep its own name. */
public class BrandTest {
    @Test public void eachAppUpdatesFromItsOwnRelease() {
        if (Brand.OTA) {
            assertEquals("WygOTA", Brand.NAME);
            assertEquals("wygota.apk", Brand.APK);
            assertTrue(Brand.RELEASE.endsWith("/releases/download/wygota-latest/"));
            assertEquals("com.wygtv.ota", BuildConfig.APPLICATION_ID);
        } else {
            assertEquals("WygTV", Brand.NAME);
            assertEquals("wygtv.apk", Brand.APK);
            assertTrue(Brand.RELEASE.endsWith("/releases/latest/download/"));
            assertEquals("com.wygtv.tv", BuildConfig.APPLICATION_ID);
        }
        assertEquals(Brand.RELEASE, Updater.RELEASE);
    }
}
