package com.wygtv.tv;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AntennaNameTest {
    @Test public void unnamedTunersAreAntennaTv() throws Exception {
        assertEquals("Antenna TV", Antenna.nameOf(new JSONObject()));
        assertEquals("Antenna TV", Antenna.nameOf(new JSONObject().put("title", "HDHomeRun FLEX 4K")));
        assertEquals("Bedroom", Antenna.nameOf(new JSONObject().put("title", "Bedroom")));
    }
}
