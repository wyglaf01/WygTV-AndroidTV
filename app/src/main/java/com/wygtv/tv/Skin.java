package com.wygtv.tv;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

import java.util.Random;

/**
 * The shapes behind buttons, rows, tiles and panels, in the current Theme. One place for edges and shadows, so every
 * screen looks the same.
 *
 * Every button, row, tile and panel is a sharp-cornered brushed-metal plate in the theme colour, with a soft drop
 * shadow below and to the right so it seems to float above the background. Focus keeps the bronze (gold) edge.
 */
final class Skin {
    private Skin() { }

    /** How far the plate floats: the shadow sits this many dp below and to the right of the plate. */
    static final int LIFT_DP = 4;

    /** A button or list row: a brushed plate in the theme colour; focused rows get the brighter focus colour and a bronze edge. */
    static Drawable button(MainActivity a, boolean focused) {
        return plate(a.getResources(), a.dp(1), Theme.surface(focused), focused ? 2 : 1, Theme.edge(focused), true);
    }

    /** A home tile (and a focused guide cell): a brushed plate when idle, polished bronze when focused. */
    static Drawable tile(MainActivity a, boolean focused) {
        int[] fill = focused ? new int[]{Theme.TILE_A, Theme.TILE_B} : Theme.surface(false);
        return plate(a.getResources(), a.dp(1), fill, focused ? 3 : 1, focused ? Theme.TILE_STROKE : Theme.edge(false), true);
    }

    /** A focused side-menu entry: a polished bronze plate with no shadow (the menu is narrow). */
    static Drawable railFocus(MainActivity a) {
        return plate(a.getResources(), a.dp(1), new int[]{Theme.TILE_A, Theme.TILE_B}, 2, Theme.TILE_STROKE, false);
    }

    /** A large panel (pop-ups, info boxes, the player banner). */
    static Drawable panel(Resources r, float dp) {
        int[] fill = {Theme.PANEL, Theme.mix(Theme.PANEL, Theme.BG, 0.7f)};
        return plate(r, dp, fill, 2, Theme.STROKE, true);
    }

    /**
     * A sharp-cornered metal plate: the fill, a brushed-metal grain on top, a light bevel along the top edge and a dark
     * one along the bottom, the edge colour around it, and (with lift) a soft shadow underneath.
     */
    static Drawable plate(Resources r, float dp, int[] fill, int strokeDp, int stroke, boolean lift) {
        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, sheen(fill));
        body.setCornerRadius(0);

        BitmapDrawable grain = new BitmapDrawable(r, grain());
        grain.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);

        // Bevel: a thin bright line on top, a thin dark line along the bottom, as on a cut and polished plate.
        GradientDrawable bevel = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40ffffff, 0x00ffffff, 0x00000000, 0x00000000, 0x38000000});
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(0x00000000);
        edge.setStroke(Math.max(1, Math.round(strokeDp * dp)), stroke);

        if (!lift) return new LayerDrawable(new Drawable[]{body, grain, bevel, edge});

        int s = Math.round(LIFT_DP * dp);
        // Three stacked see-through dark plates, each a step further out, give a soft-edged shadow.
        GradientDrawable sh1 = shade(0x30000000), sh2 = shade(0x38000000), sh3 = shade(0x50000000);
        LayerDrawable l = new LayerDrawable(new Drawable[]{sh1, sh2, sh3, body, grain, bevel, edge});
        l.setLayerInset(0, s / 2, s / 2, 0, 0);
        l.setLayerInset(1, s, s, 0, 0);
        l.setLayerInset(2, s + s / 4, s + s / 4, s / 4, s / 4);
        for (int i = 3; i < 7; i++) l.setLayerInset(i, 0, 0, s, s);
        return l;
    }

    private static GradientDrawable shade(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        return g;
    }

    /** The fill with a brighter band near the top and a darker foot, like light across a metal plate. */
    private static int[] sheen(int[] fill) {
        int top = fill[0], bottom = fill[fill.length - 1];
        int mid = Theme.mix(top, bottom, 0.45f);
        return new int[]{Theme.mix(top, 0xffffffff, 0.10f), top, mid, bottom, Theme.mix(bottom, 0xff000000, 0.18f)};
    }

    private static Bitmap grain;

    /** Fine horizontal streaks of light and dark, tiled over every plate: the brushed-metal look. Made once. */
    private static synchronized Bitmap grain() {
        if (grain != null) return grain;
        int w = 256, h = 64;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setStrokeWidth(1);
        Random rnd = new Random(7);
        for (int y = 0; y < h; y++) {
            // A faint base tone for each line, then a few longer streaks across it.
            int base = rnd.nextInt(18);
            p.setColor(rnd.nextBoolean() ? (base << 24) | 0xffffff : (base << 24));
            c.drawLine(0, y + .5f, w, y + .5f, p);
            for (int k = 0; k < 3; k++) {
                int x = rnd.nextInt(w), len = 30 + rnd.nextInt(140), alpha = 10 + rnd.nextInt(26);
                p.setColor(rnd.nextInt(3) == 0 ? (alpha << 24) : (alpha << 24) | 0xffffff);
                c.drawLine(x, y + .5f, x + len, y + .5f, p);
                if (x + len > w) c.drawLine(x - w, y + .5f, x + len - w, y + .5f, p);
            }
        }
        grain = b;
        return b;
    }
}
