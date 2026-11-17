package com.wygtv.tv;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class XtreamProtocol {
    static boolean validLogin(String host, String username, String password) {
        try {
            URI uri = new URI(host);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && !username.trim().isEmpty() && !password.isEmpty();
        } catch (Exception ignored) { return false; }
    }

    static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
