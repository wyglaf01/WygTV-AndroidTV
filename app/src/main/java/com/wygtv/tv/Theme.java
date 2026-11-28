package com.wygtv.tv;

/**
 * Colour themes picked in Settings (Cobalt metal unless changed). Every theme is made of floating brushed-metal plates (see Skin):
 * a deep main colour for panels and focus, with bronze (or gold) trim, headings and focused tiles. Steampunk keeps the
 * original brass-on-leather artwork; the others wash the artwork in their own colour.
 * Settings > Appearance > Glass effect turns panels and buttons into frosted see-through glass (see {@link #glass}).
 */
final class Theme {
    static final String[] KEYS = {"royal", "steampunk", "cyan", "green", "brown", "gold"};
    static final String[] LABELS = {"Cobalt metal (default)", "Steampunk (copper & brass)", "Teal metal", "Emerald metal", "Mahogany metal", "Gunmetal & gold"};

    // The shared trim: Order STROKE, STROKE_FOCUS, ACCENT, HEADER, INK, TILE_A, TILE_B, TILE_STROKE, COPPER
    private static final int[] BRONZE = {0xffA8743A, 0xffF0C080, 0xffE6B070, 0xffD9A060, 0xff1A0F04, 0xffF0C88A, 0xffB0743A, 0xffFFE6BC, 0xffCD7F32};
    private static final int[] GOLD = {0xffB8913A, 0xffF5D488, 0xffEDC66E, 0xffE0B655, 0xff1A1404, 0xffF5D58E, 0xffB8893A, 0xffFFF0C4, 0xffD9A632};
    private static final int[] BRASS = {0xff8A6A3A, 0xffF0C878, 0xffEACB86, 0xffE8B86A, 0xff2A1708, 0xffF0D797, 0xffBA904A, 0xffFFF0C1, 0xffD9822B};

    // Each theme's own colours. Order: BG, PANEL, PANEL_END, FOCUS, TEXT, MUTED, HINT, AIRING
    private static final int[][] BASES = {
            {0xff010A62, 0xff0A2AD8, 0xe6031590, 0xff1A55F5, 0xffF6F3EC, 0xffCCD4F2, 0xff93A3E0, 0xff0E36C0}, // royal blue: bright cobalt plates on deep cobalt
            {0xff1E1006, 0xff8E5424, 0xe6442A10, 0xffBA7A34, 0xffFFF4DE, 0xffE6CDA4, 0xffC2A47C, 0xff6E4418}, // steampunk: copper plates, brass trim
            {0xff02303A, 0xff0A8C9C, 0xe6044A55, 0xff16B4C6, 0xffF2FBFA, 0xffBFE0DE, 0xff86B4B4, 0xff0A7480}, // teal
            {0xff02301A, 0xff0E9A50, 0xe6064A28, 0xff20C46C, 0xffF2FBF4, 0xffC0E2CC, 0xff8AB89A, 0xff0C7A40}, // emerald
            {0xff360C06, 0xffA8361E, 0xe6561A0E, 0xffD04E2E, 0xffFBF0E8, 0xffE6CABA, 0xffB89484, 0xff842A18}, // mahogany
            {0xff0E0F12, 0xff4E5560, 0xe62A2E34, 0xff6E7684, 0xffF5F1E8, 0xffD4D0C8, 0xff9E9A94, 0xff3C424C}, // black: gunmetal steel plates, gold trim
    };
    private static final int[][] TRIMS = {BRONZE, BRASS, BRONZE, BRONZE, BRONZE, GOLD};

    /** Used when no theme has been picked in Settings. */
    static final String DEFAULT = "royal";
    static String key = DEFAULT;
    static int BG, PANEL, PANEL_END, STROKE, STROKE_FOCUS, FOCUS, ACCENT, HEADER, TEXT, MUTED, HINT, INK, TILE_A, TILE_B, TILE_STROKE, COPPER, AIRING;

    static { apply(DEFAULT); }

    private Theme() { }

    static void apply(String k) {
        int i = indexOf(k);
        key = KEYS[i];
        int[] b = BASES[i], t = TRIMS[i];
        BG = b[0]; PANEL = b[1]; PANEL_END = b[2]; FOCUS = b[3]; TEXT = b[4]; MUTED = b[5]; HINT = b[6]; AIRING = b[7];
        STROKE = t[0]; STROKE_FOCUS = t[1]; ACCENT = t[2]; HEADER = t[3]; INK = t[4]; TILE_A = t[5]; TILE_B = t[6]; TILE_STROKE = t[7]; COPPER = t[8];
    }

    static int indexOf(String k) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(k)) return i;
        return k.equals(DEFAULT) ? 0 : indexOf(DEFAULT);
    }

    /** Themes that were removed, and the theme their users move to. */
    static String migrate(String k) {
        return k == null || k.equals("blue") ? DEFAULT : k;
    }

    /** Settings > Appearance > Glass effect: frosted see-through panels and buttons over a blurred backdrop. */
    static final String PREF_GLASS = "glass_effect";
    static boolean glass;

    /** Top and bottom fill of a button, row or panel: solid, or see-through glass with a light sheen on top. */
    static int[] surface(boolean focused) {
        if (glass) return focused
                ? new int[]{alpha(mix(FOCUS, 0xffffffff, 0.18f), 0xb8), alpha(FOCUS, 0x8c)}
                : new int[]{alpha(mix(PANEL, 0xffffffff, 0.14f), 0x5c), alpha(mix(PANEL, BG, 0.5f), 0x3d)};
        return focused
                ? new int[]{mix(FOCUS, 0xffffffff, 0.10f), FOCUS}
                : new int[]{alpha(PANEL, 0xf2), alpha(mix(PANEL, BG, 0.55f), 0xf2)};
    }

    /** The thin edge around a button, row or panel. Glass gets a frosted light edge, solid gets bronze. */
    static int edge(boolean focused) {
        if (focused) return STROKE_FOCUS;
        return glass ? alpha(mix(STROKE_FOCUS, 0xffffffff, 0.45f), 0x73) : alpha(STROKE, 0xb3);
    }

    /** A panel colour with the glass see-through applied (alpha scaled down when the glass effect is on). */
    static int pane(int color) {
        if (!glass) return color;
        int a = color >>> 24;
        return alpha(color, Math.round(a * 0.55f));
    }

    static boolean steampunk() { return key.equals("steampunk"); }

    /** The colour with its alpha replaced. */
    static int alpha(int color, int a) { return (color & 0x00ffffff) | (a << 24); }

    /** A mix of two colours, t = 0 gives a, t = 1 gives b (alpha mixed too). */
    static int mix(int a, int b, float t) {
        int r = 0;
        for (int s = 0; s <= 24; s += 8) {
            int ca = (a >>> s) & 0xff, cb = (b >>> s) & 0xff;
            r |= (Math.round(ca + (cb - ca) * t) & 0xff) << s;
        }
        return r;
    }
}
