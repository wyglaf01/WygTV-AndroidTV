package com.wygtv.tv;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The brass menu down the left of every screen. It shows icons only until focus moves onto it
 * (press Left from the left-most item), then slides open over the screen with labels.
 */
final class NavRail {
    static final int COLLAPSED_DP = 68, EXPANDED_DP = 250;

    private final MainActivity a;
    private final LinearLayout rail;
    private final List<TextView> items = new ArrayList<>();
    private final List<String[]> labels = new ArrayList<>();
    private boolean expanded;
    private View lastContentFocus;

    /** Wraps {@code content} in a frame with the rail on its left and returns the frame to show. */
    static FrameLayout wrap(MainActivity a, View content, String current) {
        FrameLayout frame = new FrameLayout(a);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.leftMargin = a.dp(COLLAPSED_DP);
        frame.addView(content, lp);
        // Added after the content so the content's first item gets focus by default.
        NavRail r = new NavRail(a, current);
        frame.addView(r.rail, new FrameLayout.LayoutParams(a.dp(COLLAPSED_DP), -1, Gravity.START));
        // Remember where focus was in the screen, so Right (or OK on the current screen) goes back there.
        // Registered on the window while this screen is showing, and removed when it goes, so old screens don't pile up.
        android.view.ViewTreeObserver.OnGlobalFocusChangeListener track = (oldFocus, newFocus) -> {
            if (oldFocus != null && newFocus != null && r.inRail(newFocus) && !r.inRail(oldFocus)) r.lastContentFocus = oldFocus;
        };
        frame.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            android.view.ViewTreeObserver observer;
            @Override public void onViewAttachedToWindow(View v) { observer = v.getViewTreeObserver(); observer.addOnGlobalFocusChangeListener(track); }
            @Override public void onViewDetachedFromWindow(View v) { if (observer != null && observer.isAlive()) observer.removeOnGlobalFocusChangeListener(track); observer = null; }
        });
        return frame;
    }

    private NavRail(MainActivity a, String current) {
        this.a = a;
        rail = new LinearLayout(a);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(a.dp(8), a.dp(16), a.dp(8), a.dp(12));
        rail.setBackground(background(false));
        boolean m3u = a.library.activeIsM3u();
        if (a.liveOnly()) {
            // Live TV only (Settings → App mode): the guide is the main screen, plus Live TV and Settings.
            add("▦", "TV Guide", "guide", current, () -> a.openGuide(null));
            add("▣", "Live TV", "live", current, () -> a.browse("live"));
            add("⌕", "Search channels", "search", current, () -> a.searchLive());
            rail.addView(new View(a), new LinearLayout.LayoutParams(-1, 0, 1));
            add("⚙", "Settings", "settings", current, () -> a.settings());
            lockEnds();
            return;
        }
        add("⌂", "Home", "home", current, () -> a.home());
        add("▣", "Live TV", "live", current, () -> a.browse("live"));
        add("▦", "TV Guide", "guide", current, () -> a.openGuide(null));
        if (!m3u) {
            add("▶", "Movies", "vod", current, () -> a.browse("vod"));
            add("▤", "Series", "series", current, () -> a.browse("series"));
        }
        add("↺", "Recently watched", "recent", current, () -> a.recent());
        rail.addView(new View(a), new LinearLayout.LayoutParams(-1, 0, 1));
        add("☰", "Playlists", "playlists", current, () -> a.playlists());
        add("⚙", "Settings", "settings", current, () -> a.settings());
        lockEnds();
    }

    /** Up from the top item and Down from the bottom one stay in the menu. */
    private void lockEnds() {
        if (!items.isEmpty()) {
            TextView top = items.get(0), bottom = items.get(items.size() - 1);
            top.setId(View.generateViewId());
            bottom.setId(View.generateViewId());
            top.setNextFocusUpId(top.getId());
            bottom.setNextFocusDownId(bottom.getId());
        }
    }

    private void add(String icon, String label, String key, String current, Runnable action) {
        boolean here = key.equals(current);
        TextView v = new TextView(a);
        v.setText(icon);
        v.setTextSize(22);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        v.setPadding(a.dp(14), 0, a.dp(8), 0);
        v.setTextColor(here ? Theme.ACCENT : Theme.TEXT);
        if (here) v.setTypeface(null, Typeface.BOLD);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(itemBg(false, here));
        v.setContentDescription(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(48));
        lp.setMargins(0, a.dp(3), 0, a.dp(3));
        rail.addView(v, lp);
        v.setOnClickListener(w -> { if (!here || key.equals("home")) action.run(); else collapseAndReturn(); });
        v.setOnFocusChangeListener((w, focused) -> {
            v.setBackground(itemBg(focused, here));
            v.setTextColor(focused ? Theme.INK : here ? Theme.ACCENT : Theme.TEXT);
            if (focused) setExpanded(true);
            else rail.post(() -> { if (!rail.hasFocus()) setExpanded(false); });
        });
        // Right closes the menu and goes back into the screen.
        v.setOnKeyListener((w, code, e) -> {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { collapseAndReturn(); return true; }
            // Back closes the menu first instead of leaving the screen.
            if (code == android.view.KeyEvent.KEYCODE_BACK) { if (e.getAction() == android.view.KeyEvent.ACTION_UP) collapseAndReturn(); return true; }
            return false;
        });
        items.add(v);
        labels.add(new String[]{icon, label});
    }

    private boolean inRail(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == rail) return true;
        return v == rail;
    }

    private void collapseAndReturn() {
        ViewGroup frame = (ViewGroup) rail.getParent();
        View content = frame == null ? null : frame.getChildAt(0);
        setExpanded(false);
        if (content instanceof ViewGroup) {
            View target = lastContentFocus != null && lastContentFocus.isAttachedToWindow() && lastContentFocus.isShown() ? lastContentFocus : null;
            if (target == null) target = firstFocusable((ViewGroup) content);
            if (target != null) target.requestFocus();
        }
    }

    private static View firstFocusable(ViewGroup g) {
        java.util.ArrayList<View> out = new java.util.ArrayList<>();
        g.addFocusables(out, View.FOCUS_FORWARD);
        return out.isEmpty() ? null : out.get(0);
    }

    private void setExpanded(boolean open) {
        if (open == expanded) return;
        expanded = open;
        ViewGroup.LayoutParams lp = rail.getLayoutParams();
        lp.width = a.dp(open ? EXPANDED_DP : COLLAPSED_DP);
        rail.setLayoutParams(lp);
        rail.setBackground(background(open));
        for (int i = 0; i < items.size(); i++) items.get(i).setText(open ? labels.get(i)[0] + "   " + labels.get(i)[1] : labels.get(i)[0]);
    }

    private GradientDrawable background(boolean open) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                open ? new int[]{Theme.alpha(Theme.BG,0xfa), Theme.alpha(Theme.PANEL,0xf2)} : new int[]{Theme.alpha(Theme.BG,0xe6), Theme.alpha(Theme.PANEL,0xcc)});
        g.setStroke(a.dp(1), Theme.STROKE);
        return g;
    }

    private GradientDrawable itemBg(boolean focused, boolean here) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                focused ? new int[]{Theme.TILE_A, Theme.TILE_B} : here ? new int[]{Theme.alpha(Theme.PANEL,0x66), Theme.alpha(Theme.FOCUS,0x66)} : new int[]{0x00000000, 0x00000000});
        g.setCornerRadius(a.dp(10));
        if (focused || here) g.setStroke(a.dp(focused ? 2 : 1), focused ? Theme.TILE_STROKE : Theme.STROKE);
        return g;
    }
}
