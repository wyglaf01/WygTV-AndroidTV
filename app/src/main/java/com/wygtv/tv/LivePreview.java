package com.wygtv.tv;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

/**
 * Live video preview in the top half of Live TV's left side, like the TV guide's preview window. It plays the
 * channel focused in the guide on the right, after a short pause so scrolling stays quick, and stops whenever the
 * screen changes, a channel opens full screen or the app goes to the background.
 */
final class LivePreview {
    private static final int DELAY_MS = 700;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static PlayerView view;
    private static TextView label;
    private static ExoPlayer player;
    private static String playingKey;
    private static JSONObject wanted;
    private static Runnable pending;

    private LivePreview() { }

    /** Builds the preview window into the given column (top half) and returns it. */
    static View build(MainActivity a) {
        release();
        FrameLayout frame = new FrameLayout(a);
        GradientDrawable border = new GradientDrawable();
        border.setColor(0xff000000);
        border.setCornerRadius(a.dp(10));
        border.setStroke(a.dp(3), Theme.STROKE_FOCUS);
        frame.setBackground(border);
        frame.setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3));
        view = new PlayerView(a);
        view.setUseController(false);
        view.setFocusable(false);
        frame.addView(view, new FrameLayout.LayoutParams(-1, -1));
        // Any screen that replaces Live TV (even one not built through shell()) stops the preview.
        final PlayerView mine = view;
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) { if (view == mine) release(); }
        });
        label = a.label("Highlight a channel to preview it", 14);
        label.setTextColor(Theme.MUTED);
        label.setGravity(Gravity.CENTER);
        frame.addView(label, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.setMargins(a.dp(5), a.dp(5), a.dp(5), a.dp(8));
        frame.setLayoutParams(lp);
        return frame;
    }

    /** The guide's focused channel changed: preview it shortly. */
    static void show(MainActivity a, JSONObject item) {
        if (view == null || !view.isAttachedToWindow()) return;
        wanted = item;
        if (pending != null) UI.removeCallbacks(pending);
        pending = () -> { if (wanted == item) start(a, item); };
        UI.postDelayed(pending, DELAY_MS);
    }

    private static void start(MainActivity a, JSONObject item) {
        if (view == null || !view.isAttachedToWindow()) return;
        String pid = item.optString("playlist"), sid = item.optString("stream_id");
        String key = (pid.isEmpty() ? a.library.activeId() : pid) + "|" + sid;
        if (player != null && key.equals(playingKey)) return;
        String ext = item.optString("container_extension", "ts");
        if (ext.isEmpty()) ext = "ts";
        JSONObject other = pid.isEmpty() || pid.equals(a.library.activeId()) ? null : a.library.find(pid);
        String url;
        try { url = other == null ? a.streamUrl("live", sid, ext) : a.library.streamUrlFor(other, sid, ext); } catch (Exception e) { url = null; }
        if (url == null || url.isEmpty()) { stopPlayer(); label.setText("Preview unavailable"); label.setVisibility(View.VISIBLE); return; }
        playingKey = key;
        label.setText("Loading " + item.optString("name", "") + "…");
        label.setVisibility(View.VISIBLE);
        if (player == null) {
            player = new ExoPlayer.Builder(a).build();
            view.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    if (label != null) { label.setText("Preview unavailable"); label.setVisibility(View.VISIBLE); }
                }
                @Override public void onRenderedFirstFrame() { if (label != null) label.setVisibility(View.GONE); }
            });
        }
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
    }

    private static void stopPlayer() {
        if (player != null) {
            if (view != null) view.setPlayer(null);
            player.release();
            player = null;
        }
        playingKey = null;
    }

    /** Stops the preview (screen change, full-screen play, app in the background). */
    static void release() {
        if (pending != null) UI.removeCallbacks(pending);
        pending = null;
        wanted = null;
        stopPlayer();
    }

    /** After the app comes back: preview the focused channel again if the preview is still on screen. */
    static void resume(MainActivity a) {
        if (view == null || !view.isAttachedToWindow()) return;
        View f = a.getCurrentFocus();
        JSONObject item = f == null ? null : MiniGuide.itemOf(f);
        if (item != null) show(a, item);
    }
}
