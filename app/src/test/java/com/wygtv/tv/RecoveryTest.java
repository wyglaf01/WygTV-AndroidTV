package com.wygtv.tv;

import androidx.media3.common.PlaybackException;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryTest {
    @Test public void waitsLongerEachTryUpToEightSeconds() {
        assertEquals(1000, Recovery.delayMs(0));
        assertEquals(2000, Recovery.delayMs(1));
        assertEquals(4000, Recovery.delayMs(2));
        assertEquals(8000, Recovery.delayMs(3));
        assertEquals(8000, Recovery.delayMs(7));
    }
    @Test public void retriesNetworkAndBehindLive() {
        assertTrue(Recovery.retryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, false));
        assertTrue(Recovery.retryable(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, false));
        assertTrue(Recovery.retryable(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, false));
        assertFalse(Recovery.retryable(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, false));
        assertTrue(Recovery.retryable(PlaybackException.ERROR_CODE_DECODING_FAILED, true));
    }
    @Test public void refusedCodes() {
        assertTrue(Recovery.refused(503));
        assertTrue(Recovery.refused(404));
        assertFalse(Recovery.refused(500));
        assertFalse(Recovery.refused(0));
    }
    @Test public void bufferChoices() {
        assertEquals(5, Players.bufferSeconds(null));
        assertEquals(0, Players.bufferSeconds("off"));
        assertEquals(20, Players.bufferSeconds("large"));
        int[] b = Players.bufferMs(5);
        assertEquals(5000, b[2]);
        assertTrue(b[0] >= 20_000 && b[1] > b[0] && b[3] >= b[2]);
        assertEquals(Players.BUFFER_KEYS.length, Players.BUFFER_LABELS.length);
        assertEquals(Players.DECODER_KEYS.length, Players.DECODER_LABELS.length);
    }
    @Test public void givesUpSoonerBeforePlayingWhenVlcIsWaiting() {
        assertEquals(2, Recovery.allowedTries(false, 2));
        assertEquals(Recovery.MAX_TRIES, Recovery.allowedTries(true, 2));
        assertEquals(Recovery.MAX_TRIES, Recovery.allowedTries(false, Recovery.MAX_TRIES));
    }
    @Test public void playerChoices() {
        assertEquals(Players.ENGINE_KEYS.length, Players.ENGINE_LABELS.length);
        assertEquals("auto", Players.ENGINE_KEYS[0]);
        assertEquals(Players.ENGINE_DEFAULT, Players.ENGINE_KEYS[0]);
    }
}
