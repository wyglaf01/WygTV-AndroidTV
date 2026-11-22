package com.wygtv.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VodSearchTest {
    @Test public void startsFirstThenContainsEveryWord() throws Exception {
        JSONArray all = new JSONArray()
                .put(new JSONObject().put("name", "EN - The Batman (2022)"))
                .put(new JSONObject().put("name", "Batman Begins"))
                .put(new JSONObject().put("name", "Superman"))
                .put(new JSONObject().put("name", "Batman & Robin"));
        JSONArray hits = XtreamProtocol.filter(all, new String[0], XtreamProtocol.searchKey("batman"));
        assertEquals(3, hits.length());
        assertEquals("Batman Begins", hits.getJSONObject(0).getString("name"));
        assertEquals("Batman & Robin", hits.getJSONObject(1).getString("name"));
        assertEquals("EN - The Batman (2022)", hits.getJSONObject(2).getString("name"));
        assertEquals(0, XtreamProtocol.filter(all, new String[0], XtreamProtocol.searchKey("joker")).length());
    }
}
