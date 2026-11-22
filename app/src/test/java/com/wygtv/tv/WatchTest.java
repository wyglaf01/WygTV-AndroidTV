package com.wygtv.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WatchTest {
    private static JSONObject show() throws Exception {
        JSONObject eps = new JSONObject();
        eps.put("10", new JSONArray().put(ep("x1", "1")));
        eps.put("2", new JSONArray().put(ep("b1", "1")).put(ep("b2", "2")));
        eps.put("1", new JSONArray().put(ep("a1", "1")).put(ep("a2", "2")));
        return eps;
    }

    private static JSONObject ep(String id, String n) throws Exception { return new JSONObject().put("id", id).put("episode_num", n).put("title", "Ep " + id); }

    @Test public void finishedNearTheEnd() {
        assertTrue(Watch.finished(54 * 60_000, 60 * 60_000));
        assertTrue(Watch.finished(58 * 60_000, 60 * 60_000));
        assertFalse(Watch.finished(30 * 60_000, 60 * 60_000));
        assertFalse(Watch.finished(5_000, -9));
    }

    @Test public void seasonsInNumberOrder() throws Exception {
        assertEquals(java.util.Arrays.asList("1", "2", "10"), Watch.seasons(show()));
    }

    @Test public void nextStartsResumesAndAdvances() throws Exception {
        JSONObject eps = show(), state = new JSONObject();
        assertArrayEquals(new String[]{"1", "0", "start"}, Watch.next(eps, state));
        Watch.update(state, "a2", "1", "2", 10 * 60_000, 40 * 60_000);
        assertArrayEquals(new String[]{"1", "1", "resume"}, Watch.next(eps, state));
        assertEquals(10 * 60_000, Watch.resumeAt(state, "a2"));
        Watch.update(state, "a2", "1", "2", 39 * 60_000, 40 * 60_000);
        assertArrayEquals(new String[]{"2", "0", "next"}, Watch.next(eps, state));
        assertEquals(0, Watch.resumeAt(state, "a2"));
        assertEquals("watched S1 E2", Watch.summary(state));
        Watch.update(state, "b1", "2", "1", 40 * 60_000, 40 * 60_000);
        Watch.update(state, "b2", "2", "2", 40 * 60_000, 40 * 60_000);
        Watch.update(state, "x1", "10", "1", 40 * 60_000, 40 * 60_000);
        assertNull(Watch.next(eps, state));
        assertEquals(1, Watch.doneIn(eps.getJSONArray("1"), state));
        assertEquals(2, Watch.doneIn(eps.getJSONArray("2"), state));
    }

    @Test public void rewatchingTheStartKeepsAnEpisodeWatched() throws Exception {
        JSONObject state = new JSONObject();
        Watch.update(state, "a1", "1", "1", 40 * 60_000, 40 * 60_000);
        Watch.update(state, "a1", "1", "1", 20_000, 40 * 60_000);
        assertTrue(Watch.done(state, "a1"));
    }

    @Test public void labels() throws Exception {
        assertEquals("S2 E5", Watch.label("2", new JSONObject().put("episode_num", "5"), 0));
        assertEquals("S2 E3", Watch.label("2", new JSONObject(), 2));
        assertEquals("1h 5m", Watch.clock(65 * 60_000));
        assertEquals("12 min", Watch.clock(12 * 60_000));
    }

    @Test public void afterMovesToTheNextSeason() throws Exception {
        JSONObject eps = show();
        assertArrayEquals(new String[]{"1", "1"}, Watch.after(eps, "a1"));
        assertArrayEquals(new String[]{"2", "0"}, Watch.after(eps, "a2"));
        assertArrayEquals(new String[]{"10", "0"}, Watch.after(eps, "b2"));
        assertNull(Watch.after(eps, "x1"));
        assertNull(Watch.after(eps, "nope"));
        assertNull(Watch.after(null, "a1"));
    }
}
