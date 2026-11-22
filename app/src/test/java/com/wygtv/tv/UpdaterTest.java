package com.wygtv.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdaterTest {
    @Test public void newerOnlyWhenCodeIsHigher() {
        assertTrue(Updater.isNewer(42, 41));
        assertFalse(Updater.isNewer(41, 41));
        assertFalse(Updater.isNewer(-1, 41));
    }

    @Test public void signatureClashSaysToReinstallOnce() {
        assertTrue(Updater.failureText(android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, null).contains("uninstall WygIPTV once"));
        assertEquals("The update didn't install: bad file", Updater.failureText(android.content.pm.PackageInstaller.STATUS_FAILURE, "bad file"));
    }
}
