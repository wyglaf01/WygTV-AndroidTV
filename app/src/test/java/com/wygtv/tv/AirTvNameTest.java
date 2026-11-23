package com.wygtv.tv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AirTvNameTest {
    @Test public void emptyNameIsAirTv() {
        assertEquals("AirTV", AirTv.cleanName(null));
        assertEquals("AirTV", AirTv.cleanName("   "));
        assertEquals("Basement", AirTv.cleanName(" basement "));
    }
}
