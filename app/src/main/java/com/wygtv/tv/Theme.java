package com.wygtv.tv;

/**
 * Colour themes picked in Settings (Royal blue & bronze unless changed). Steampunk keeps the original brass-on-leather look and artwork;
 * the others recolour every panel, button, menu and the guide, and wash the artwork in their own colour.
 */
final class Theme {
    static final String[] KEYS = {"royal", "steampunk", "blue", "cyan", "brown", "gold", "green"};
    static final String[] LABELS = {"Royal blue & bronze (default)", "Steampunk (brass & leather)", "Blue", "Cyan", "Brown", "Gold", "Green"};

    // Order: BG, PANEL, PANEL_END, STROKE, STROKE_FOCUS, FOCUS, ACCENT, HEADER, TEXT, MUTED, HINT, INK, TILE_A, TILE_B, TILE_STROKE, COPPER, AIRING
    private static final int[][] PALETTES = {
            // Royal blue panels with bronze trim, matching the launcher icon.
            {0xff060C24, 0xff1A3480, 0xe60A1640, 0xffA8743A, 0xffF0C080, 0xff2D52C4, 0xffE6B070, 0xffD9A060, 0xffF4EFE6, 0xffC6CADD, 0xff8C93B8, 0xff1A0F04, 0xffF0C88A, 0xffB0743A, 0xffFFE6BC, 0xffCD7F32, 0xff23408F},
            {0xff140B06, 0xff3A2414, 0xe61C100A, 0xff8A6A3A, 0xffF0C878, 0xff9C5B22, 0xffEACB86, 0xffE8B86A, 0xffF5E8CA, 0xffD9BF94, 0xffB89F7C, 0xff2A1708, 0xffF0D797, 0xffBA904A, 0xffFFF0C1, 0xffD9822B, 0xff6E3E1C},
            {0xff070D1C, 0xff142A4E, 0xe60A1428, 0xff3F5F92, 0xff8FB8FF, 0xff2F5FB0, 0xff9CC3FF, 0xff8DB6F5, 0xffEAF1FF, 0xffB4C6E4, 0xff7F93B5, 0xff081428, 0xffA9CBFF, 0xff5D8FD9, 0xffE1ECFF, 0xff4C9BFF, 0xff1C3F78},
            {0xff04161A, 0xff0F3A42, 0xe6061F24, 0xff2F7A86, 0xff7FE7F2, 0xff148A98, 0xff8FEFF7, 0xff6FDCE8, 0xffE6FBFD, 0xffA8D9DE, 0xff6FA4AA, 0xff04191D, 0xff9FF3FA, 0xff3BBFCC, 0xffDDFCFF, 0xff22C8D8, 0xff12555F},
            {0xff1A100A, 0xff4A2E1C, 0xe62A1A10, 0xff7A5638, 0xffD9A877, 0xff8A5530, 0xffE3BE96, 0xffD9A877, 0xffF6EADC, 0xffD5BFA6, 0xffA88E74, 0xff2A180C, 0xffE8C9A4, 0xffA9764A, 0xffFBEBD8, 0xffC07A45, 0xff6A4128},
            {0xff171104, 0xff4A3A10, 0xe6261D06, 0xffA88A30, 0xffFFE07A, 0xffB58A12, 0xffFFD966, 0xffFFCF40, 0xffFFF6DC, 0xffE6D59E, 0xffB3A268, 0xff2A2004, 0xffFFE48A, 0xffD4A520, 0xffFFF6C8, 0xffF2B705, 0xff6B5412},
            {0xff07150A, 0xff173F22, 0xe60B2112, 0xff3F7F4E, 0xff9BE6A8, 0xff2E8A45, 0xffA6EBB2, 0xff8FDE9E, 0xffEAFBEE, 0xffB5DDBD, 0xff7FA889, 0xff06180B, 0xffB2F0BD, 0xff4FB866, 0xffE2FCE6, 0xff3FCF63, 0xff1F5A2E},
    };

    /** Used when no theme has been picked in Settings. */
    static final String DEFAULT = "royal";
    static String key = DEFAULT;
    static int BG, PANEL, PANEL_END, STROKE, STROKE_FOCUS, FOCUS, ACCENT, HEADER, TEXT, MUTED, HINT, INK, TILE_A, TILE_B, TILE_STROKE, COPPER, AIRING;

    static { apply(DEFAULT); }

    private Theme() { }

    static void apply(String k) {
        int i = indexOf(k);
        key = KEYS[i];
        int[] p = PALETTES[i];
        BG = p[0]; PANEL = p[1]; PANEL_END = p[2]; STROKE = p[3]; STROKE_FOCUS = p[4]; FOCUS = p[5]; ACCENT = p[6]; HEADER = p[7];
        TEXT = p[8]; MUTED = p[9]; HINT = p[10]; INK = p[11]; TILE_A = p[12]; TILE_B = p[13]; TILE_STROKE = p[14]; COPPER = p[15]; AIRING = p[16];
    }

    static int indexOf(String k) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(k)) return i;
        return k.equals(DEFAULT) ? 0 : indexOf(DEFAULT);
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
