package com.wygtv.tv;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.Button;

/** Pop-up windows in the current Theme's colours: a panel background with a trim border and accent buttons. */
final class Dialogs {
    private Dialogs() { }

    /** Use instead of AlertDialog.Builder so every pop-up follows the theme. */
    static final class Builder extends AlertDialog.Builder {
        Builder(Context c) { super(c); }

        @Override public AlertDialog create() {
            AlertDialog d = super.create();
            if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(background(getContext()));
            d.setOnShowListener(x -> tint(d));
            // Down on the last item of a pick list goes back to the top (after the buttons, if any), Up on the first goes to the bottom.
            d.setOnKeyListener((x, code, e) -> FocusWrap.dialogKey(d, d.getListView(), e));
            return d;
        }
    }

    static GradientDrawable background(Context c) {
        float dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, c.getResources().getDisplayMetrics());
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Theme.PANEL, Theme.alpha(Theme.BG, 0xff)});
        g.setCornerRadius(14 * dp);
        g.setStroke(Math.round(2 * dp), Theme.STROKE);
        return g;
    }

    /** Colours the buttons; call it from any OnShowListener that replaces the default one. */
    static void tint(AlertDialog d) {
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button b = d.getButton(which);
            if (b != null) b.setTextColor(Theme.ACCENT);
        }
    }
}
