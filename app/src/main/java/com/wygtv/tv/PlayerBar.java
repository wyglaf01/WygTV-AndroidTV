package com.wygtv.tv;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's options bar: a row of metal-plate buttons along the bottom of the picture while something plays,
 * opened with D-pad Down (the layout idea comes from VesperTV's TV options row; this is WygTV's own code).
 *
 * Above the row a status line shows what is playing (with LIVE for channels, or the time played for movies and
 * episodes) and the clock. Left / Right move along the row and wrap round, OK presses a button, Up or Back closes it,
 * and it hides by itself after a few seconds without a key press.
 */
@OptIn(markerClass = UnstableApi.class)
final class PlayerBar {
    private static final int HIDE_MS = 8000;
    static final String PREF_FIT = "player_fit";
    private static final int[] FITS = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL};
    private static final String[] FIT_LABELS = {"Fit", "Zoom", "Stretch"};

    /** One button: its icon, its name, a short value under the name (or null), and what OK does. */
    static final class Option {
        final int icon; final String label, detail; final Runnable run;
        Option(int icon, String label, String detail, Runnable run) { this.icon = icon; this.label = label; this.detail = detail; this.run = run; }
    }

    /** Builds the buttons each time the bar opens, so labels such as Pause / Play and ★ are current. */
    interface Options { List<Option> build(); }

    private final MainActivity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final boolean live;
    private final Options options;
    private final FrameLayout root;
    private final LinearLayout row;
    private final HorizontalScrollView scroll;
    private final TextView title, now, clock, tag;
    private final ProgressBar progress;
    private final Runnable hide = this::hide;
    private final Runnable tick = this::tick;

    PlayerBar(MainActivity a, FrameLayout frame, boolean live, Options options) {
        this.a = a;
        this.live = live;
        this.options = options;

        root = new FrameLayout(a);
        root.setVisibility(View.GONE);
        // A dark fade from the bottom edge so the buttons stand out over any picture.
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x99000000, 0x00000000}));

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(a.dp(32), a.dp(56), a.dp(32), a.dp(20));

        // Status line: title  LIVE  now playing ................ clock
        LinearLayout status = new LinearLayout(a);
        status.setGravity(Gravity.CENTER_VERTICAL);
        title = text(20, Theme.TEXT, true);
        status.addView(title, new LinearLayout.LayoutParams(-2, -2));
        tag = text(12, Theme.INK, true);
        tag.setText(live ? "LIVE" : "");
        tag.setPadding(a.dp(8), a.dp(1), a.dp(8), a.dp(1));
        tag.setBackground(Skin.plate(a.getResources(), a.getResources().getDisplayMetrics().density, new int[]{Theme.TILE_A, Theme.TILE_B}, 1, Theme.TILE_STROKE, false));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMargins(a.dp(12), 0, a.dp(12), 0);
        status.addView(tag, tlp);
        tag.setVisibility(live ? View.VISIBLE : View.GONE);
        now = text(16, Theme.MUTED, false);
        status.addView(now, new LinearLayout.LayoutParams(0, -2, 1));
        clock = text(18, Theme.ACCENT, true);
        status.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        column.addView(status, new LinearLayout.LayoutParams(-1, -2));

        // Movies and episodes: how far along, as a thin bronze line.
        progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55ffffff));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, a.dp(4));
        plp.setMargins(0, a.dp(8), 0, 0);
        column.addView(progress, plp);
        progress.setVisibility(live ? View.GONE : View.VISIBLE);

        // The row of buttons, centred; a long row scrolls inside the screen width.
        scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFocusable(false);
        row = new LinearLayout(a);
        row.setGravity(Gravity.TOP);
        scroll.addView(row, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.setMargins(0, a.dp(14), 0, 0);
        column.addView(scroll, slp);

        root.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        applyFit(a, null);
    }

    boolean showing() { return root.getVisibility() == View.VISIBLE; }

    /** Opens the bar over the picture with the first button focused. */
    void show(String name) {
        title.setText(name);
        rebuild(0);
        root.setVisibility(View.VISIBLE);
        tick();
        poke();
    }

    void hide() {
        ui.removeCallbacksAndMessages(null);
        root.setVisibility(View.GONE);
        PlayerView v = a.remote == null ? null : a.remote.currentView();
        if (v != null) v.requestFocus();
    }

    /** What's on now (live channels), filled in by the player when the guide answers. */
    void setNow(String text) { now.setText(text == null ? "" : text); }

    /** Keys while the bar is open. True when used; other keys (media keys, CH±, digits) work as usual. */
    boolean key(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                poke();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) press();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) hide();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                poke();
                return true;
            default:
                poke();
                return false;
        }
    }

    private int focused() {
        for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).isFocused()) return i;
        return 0;
    }

    private void move(int by) {
        int n = row.getChildCount();
        if (n == 0) return;
        int i = (focused() + by + n) % n;
        focus(i);
    }

    private void focus(int i) {
        View v = row.getChildAt(i);
        if (v == null) return;
        v.requestFocus();
        scroll.post(() -> scroll.smoothScrollTo(Math.max(0, v.getLeft() - (scroll.getWidth() - v.getWidth()) / 2), 0));
    }

    private void press() {
        int i = focused();
        View v = row.getChildAt(i);
        if (v == null || !(v.getTag() instanceof Option)) return;
        Option o = (Option) v.getTag();
        o.run.run();
        // Buttons that open a pop-up or leave the player close the bar; the rest (Pause, ★, Picture...) keep it up.
        if (showing()) { rebuild(i); poke(); }
    }

    /** Wraps a button's action so the bar closes first: for buttons that open a pop-up or leave the player. */
    Runnable closing(Runnable r) { return () -> { hide(); r.run(); }; }

    private void rebuild(int focusAt) {
        row.removeAllViews();
        for (Option o : options.build()) row.addView(tile(o));
        root.post(() -> focus(Math.min(focusAt, row.getChildCount() - 1)));
    }

    private void poke() {
        ui.removeCallbacks(hide);
        ui.postDelayed(hide, HIDE_MS);
    }

    /** Once a second while open: the clock, and for movies and episodes the time played. */
    private void tick() {
        ui.removeCallbacks(tick);
        if (!showing()) return;
        clock.setText(android.text.format.DateFormat.getTimeFormat(a).format(new java.util.Date()));
        if (!live && a.player != null) {
            long pos = Math.max(0, a.player.getCurrentPosition()), dur = a.player.getDuration();
            boolean known = dur != C.TIME_UNSET && dur > 0;
            now.setText(known ? time(pos) + "  /  " + time(dur) + "     " + time(dur - pos) + " left" : time(pos));
            progress.setProgress(known ? (int) (pos * 1000 / dur) : 0);
        }
        ui.postDelayed(tick, 1000);
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000), h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(java.util.Locale.US, "%d:%02d", m, sec);
    }

    /** One button: a square brushed-metal plate with the icon, the name under it, and its value under that. */
    private View tile(Option o) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setTag(o);
        t.setPadding(a.dp(3), a.dp(4), a.dp(3), a.dp(4));
        FrameLayout plate = new FrameLayout(a);
        ImageView icon = new ImageView(a);
        icon.setImageResource(o.icon);
        plate.addView(icon, new FrameLayout.LayoutParams(a.dp(30), a.dp(30), Gravity.CENTER));
        // The plate's shadow sits LIFT_DP below and right, so the plate itself is that much smaller than its box.
        int lift = a.dp(Skin.LIFT_DP);
        plate.setPadding(0, 0, lift, lift);
        t.addView(plate, new LinearLayout.LayoutParams(a.dp(58) + lift, a.dp(58) + lift));
        TextView name = text(12, Theme.TEXT, false);
        name.setText(o.label);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(a.dp(74), -2);
        nlp.setMargins(0, a.dp(4), 0, 0);
        t.addView(name, nlp);
        TextView detail = text(11, Theme.ACCENT, false);
        detail.setGravity(Gravity.CENTER);
        detail.setSingleLine(true);
        detail.setText(o.detail == null ? "" : o.detail);
        detail.setVisibility(o.detail == null ? View.INVISIBLE : View.VISIBLE);
        t.addView(detail, new LinearLayout.LayoutParams(a.dp(74), -2));
        View.OnFocusChangeListener paint = (v, has) -> {
            plate.setBackground(has ? Skin.tile(a, true) : Skin.button(a, false));
            plate.setTranslationY(has ? -a.dp(4) : 0);
            icon.setColorFilter(has ? Theme.INK : Theme.TEXT);
            name.setTextColor(has ? Theme.ACCENT : Theme.TEXT);
            name.setTypeface(null, has ? Typeface.BOLD : Typeface.NORMAL);
        };
        t.setOnFocusChangeListener(paint);
        paint.onFocusChange(t, false);
        t.setOnClickListener(v -> { focusOn(v); press(); });
        return t;
    }

    private void focusOn(View v) { if (!v.isFocused()) v.requestFocus(); }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setShadowLayer(4, 0, 1, 0xcc000000);
        return t;
    }

    // ---------------------------------------------------------------- shared buttons

    /** Picture size (Fit / Zoom / Stretch), remembered for every stream. Null = apply the saved choice. */
    static void applyFit(MainActivity a, Integer step) {
        int i = Math.max(0, Math.min(FITS.length - 1, a.prefs.getInt(PREF_FIT, 0)));
        if (step != null) { i = (i + step) % FITS.length; a.prefs.edit().putInt(PREF_FIT, i).apply(); }
        PlayerView v = a.remote == null ? null : a.remote.currentView();
        if (v != null) v.setResizeMode(FITS[i]);
    }

    static String fitLabel(MainActivity a) {
        return FIT_LABELS[Math.max(0, Math.min(FITS.length - 1, a.prefs.getInt(PREF_FIT, 0)))];
    }

    /** The audio tracks of what is playing, for the Audio button (null when there is only one). */
    static List<Tracks.Group> audioGroups(MainActivity a) {
        List<Tracks.Group> out = new ArrayList<>();
        if (a.player == null) return out;
        for (Tracks.Group g : a.player.getCurrentTracks().getGroups()) if (g.getType() == C.TRACK_TYPE_AUDIO) out.add(g);
        return out;
    }

    static int audioCount(MainActivity a) {
        int n = 0;
        for (Tracks.Group g : audioGroups(a)) for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i)) n++;
        return n;
    }

    /** A pop-up of the audio tracks (languages, descriptive audio...), the playing one ticked. */
    static void chooseAudio(MainActivity a) {
        if (a.player == null) return;
        final List<Tracks.Group> groups = new ArrayList<>();
        final List<Integer> index = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int checked = -1, number = 1;
        for (Tracks.Group g : audioGroups(a)) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                String label = Subtitles.label(f, number++);
                if (f.channelCount > 0) label += f.channelCount >= 6 ? "  ·  5.1" : f.channelCount == 2 ? "  ·  stereo" : "";
                if (g.isTrackSelected(i)) checked = labels.size();
                labels.add(label);
                groups.add(g);
                index.add(i);
            }
        }
        if (labels.isEmpty()) { android.widget.Toast.makeText(a, "No audio tracks to choose from yet.", android.widget.Toast.LENGTH_SHORT).show(); return; }
        new Dialogs.Builder(a).setTitle("Audio track")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    d.dismiss();
                    if (a.player == null) return;
                    Tracks.Group g = groups.get(which);
                    a.player.setTrackSelectionParameters(a.player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), index.get(which)))
                            .build());
                }).show();
    }
}
