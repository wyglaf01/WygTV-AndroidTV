package com.wygtv.tv;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteKeysTest {
    @Test public void readsTopRowAndNumpadDigits() {
        assertEquals(0, RemoteKeys.digit(KeyEvent.KEYCODE_0));
        assertEquals(7, RemoteKeys.digit(KeyEvent.KEYCODE_7));
        assertEquals(9, RemoteKeys.digit(KeyEvent.KEYCODE_NUMPAD_9));
        assertEquals(-1, RemoteKeys.digit(KeyEvent.KEYCODE_DPAD_UP));
    }

    @Test public void buildsChannelNumbers() {
        assertEquals("1", RemoteKeys.appendDigit("", 1));
        assertEquals("12", RemoteKeys.appendDigit("1", 2));
        assertEquals("5", RemoteKeys.appendDigit("0", 5));
        assertEquals("0", RemoteKeys.appendDigit("", 0));
        assertEquals("2345", RemoteKeys.appendDigit("1234", 5));
    }

    @Test public void zapWrapsAround() {
        assertEquals(0, RemoteKeys.wrap(4, 1, 5));
        assertEquals(4, RemoteKeys.wrap(0, -1, 5));
        assertEquals(2, RemoteKeys.wrap(1, 1, 5));
        assertEquals(-1, RemoteKeys.wrap(0, 1, 0));
    }

    @Test public void findsChannelByProviderNumber() {
        String[] nums = {"1", "2", "101", ""};
        assertEquals(2, RemoteKeys.indexOfNumber(nums, "101"));
        assertEquals(-1, RemoteKeys.indexOfNumber(nums, "7"));
        assertEquals(-1, RemoteKeys.indexOfNumber(nums, ""));
    }

    @Test public void pageJumpStopsAtEnds() {
        assertEquals(10, RemoteKeys.pageJump(0, 10, 50));
        assertEquals(49, RemoteKeys.pageJump(45, 10, 50));
        assertEquals(0, RemoteKeys.pageJump(3, -10, 50));
    }
}
