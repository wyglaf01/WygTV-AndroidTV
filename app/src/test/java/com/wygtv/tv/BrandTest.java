package com.wygtv.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Runs for each app that CI builds: each one must update from its own APK and keep its own name. */
public class BrandTest {
    @Test public void eachAppUpdatesFromItsOwnRelease() {
        if (Brand.OTA) {
            assertEquals("WygOTA", Brand.NAME);
            assertEquals("com.wygtv.ota", BuildConfig.APPLICATION_ID);
            assertEquals(Brand.OTA_RELEASE, Updater.RELEASE);
            assertEquals("wygota.apk", Updater.APK);
        } else {
            assertEquals("WygTV", Brand.NAME);
            assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.wygtv.tv"));
            assertTrue(Updater.APK.startsWith("wygtv"));
        }
    }

    /** The Galaxy Store build is always the plain WygTV app (never WygOTA or the beta). */
    @Test public void galaxyStoreBuildIsWygTv() {
        if (!Brand.STORE) return;
        assertEquals("WygTV", Brand.NAME);
        assertEquals("com.wygtv.tv", BuildConfig.APPLICATION_ID);
    }
}
