package com.wygtv.tv;

import android.app.Dialog;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;

/**
 * Wrap-around scrolling for menus and lists: Down on the last item goes back to the first,
 * and Up on the first item goes to the last. Holding the key stops at the end instead of looping.
 */
final class FocusWrap {
    private FocusWrap() { }

    /**
     * Screens: a scrolling column of buttons (side menu of categories, channel lists, Settings, Favorites…).
     * Returns true when the key was used.
     */
    static boolean screenKey(View root, View focused, KeyEvent e) {
        int key = e.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_DOWN && key != KeyEvent.KEYCODE_DPAD_UP) return false;
        if (root == null || focused == null || !(focused.getParent() instanceof LinearLayout)) return false;
        LinearLayout list = (LinearLayout) focused.getParent();
        if (list.getOrientation() != LinearLayout.VERTICAL || !(list.getParent() instanceof ScrollView)) return false;
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
        int n = list.getChildCount(), at = list.indexOfChild(focused);
        boolean[] can = new boolean[n];
        for (int i = 0; i < n; i++) can[i] = takesFocus(list.getChildAt(i));
        if (RemoteKeys.nextFocusable(can, at, down ? 1 : -1) >= 0) return false; // not at the end yet
        // Something else lines up above or below (a search box, the buttons over the channel list, a row inside the list): go there as usual.
        if (root instanceof ViewGroup) {
            View next = FocusFinder.getInstance().findNextFocus((ViewGroup) root, focused, down ? View.FOCUS_DOWN : View.FOCUS_UP);
            if (next != null && (isInside(next, list) || overlapsSideways(next, focused))) return false;
        }
        int target = RemoteKeys.wrapTarget(can, down);
        if (target < 0 || target == at) return false; // a single item (like a long plot): let the screen scroll
        if (e.getRepeatCount() > 0) return true; // holding the key: stop at the end
        View v = list.getChildAt(target);
        v.requestFocus();
        ScrollView sv = (ScrollView) list.getParent();
        sv.post(() -> { if (down) sv.smoothScrollTo(0, 0); else sv.fullScroll(View.FOCUS_DOWN); });
        return true;
    }

    /** Pop-up lists (pickers, item menus): wrap the list, still letting Down reach the dialog's buttons first. */
    static boolean dialogKey(Dialog d, ListView lv, KeyEvent e) {
        int key = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN || (key != KeyEvent.KEYCODE_DPAD_DOWN && key != KeyEvent.KEYCODE_DPAD_UP)) return false;
        Window w = d.getWindow();
        if (lv == null || w == null || lv.getCount() == 0) return false;
        View focused = w.getCurrentFocus();
        if (focused == null) return false;
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
        int n = lv.getCount();
        if (focused == lv) {
            int pos = lv.getSelectedItemPosition();
            if (pos == ListView.INVALID_POSITION || (down ? pos < n - 1 : pos > 0)) return false; // the list moves itself
        }
        View next = FocusFinder.getInstance().findNextFocus((ViewGroup) w.getDecorView(), focused, down ? View.FOCUS_DOWN : View.FOCUS_UP);
        if (next != null) return false; // e.g. from the last item down to Cancel
        if (e.getRepeatCount() > 0) return true;
        lv.requestFocus();
        lv.setSelection(down ? 0 : n - 1);
        return true;
    }

    private static boolean takesFocus(View v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.isFocusable() && v.isEnabled();
    }

    private static boolean isInside(View v, View group) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == group) return true;
        return false;
    }

    private static boolean overlapsSideways(View a, View b) {
        int[] pa = new int[2], pb = new int[2];
        a.getLocationOnScreen(pa);
        b.getLocationOnScreen(pb);
        return pa[0] < pb[0] + b.getWidth() && pb[0] < pa[0] + a.getWidth();
    }
}
