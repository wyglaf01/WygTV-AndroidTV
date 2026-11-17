package com.wygtv.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class XtreamProtocolTest {
    @Test public void acceptsProviderPortsAndPaths() {
        assertTrue(XtreamProtocol.validLogin("https://provider.example:8443/iptv", "user", "pass"));
        assertTrue(XtreamProtocol.validLogin("http://192.0.2.1:8080", "user", "pass"));
    }
    @Test public void rejectsMalformedOrAmbiguousServerAddresses() {
        for (String host : new String[]{"provider.example", "ftp://provider.example", "https://user:pass@provider.example", "https://provider.example?a=1", "https://provider.example/#fragment", "http://bad host"})
            assertFalse(host, XtreamProtocol.validLogin(host, "user", "pass"));
        assertFalse(XtreamProtocol.validLogin("https://provider.example", " ", "pass"));
        assertFalse(XtreamProtocol.validLogin("https://provider.example", "user", ""));
    }
    @Test public void credentialsCannotChangeUrlStructure() {
        assertEquals("a%20b%2Bc%2Fd%26e%3Df%3F%23", XtreamProtocol.encode("a b+c/d&e=f?#"));
        assertEquals("%C3%A9", XtreamProtocol.encode("é"));
    }
}
