package com.wygtv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The VLC player engine (LibVLC), dressed up as a Media3 {@code Player} so the rest of the app treats it like
 * ExoPlayer: the same on-screen controls (PlayerView), remote keys, channel zapping, auto-reconnect
 * ({@link Recovery}), resume points and subtitles ({@link Subtitles}). VLC plays some streams ExoPlayer
 * can't (odd containers, broken timestamps, unusual codecs).
 *
 * VLC draws the picture into PlayerView's own SurfaceView, and subtitles into a see-through SurfaceView
 * laid on top of it. Settings > Buffer sets VLC's network caching; Settings > Video decoder picks
 * hardware or software decoding.
 */
@OptIn(markerClass = UnstableApi.class)
final class VlcPlayer extends SimpleBasePlayer {
    private final LibVLC vlc;
    private final MediaPlayer mp;
    private final int cachingMs;
    private final boolean hardware;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private MediaItem item;
    private int itemUid;
    private long startMs, seekTarget = -1, seekAt, lastPos;
    private boolean playWhenReady, prepared, opening, buffering, started, ended, seekable, released;
    private long lengthMs;
    private PlaybackException error;
    private VideoSize videoSize = VideoSize.UNKNOWN;
    private TrackSelectionParameters params;
    private Tracks tracks = Tracks.EMPTY;

    private SurfaceView videoView, subtitleView;
    private final View.OnLayoutChangeListener sizer = (v, l, t, r, b, ol, ot, or, ob) -> windowSize(r - l, b - t);

    VlcPlayer(Context c, SharedPreferences prefs) {
        super(Looper.getMainLooper());
        ArrayList<String> options = new ArrayList<>();
        options.add("--http-reconnect");       // pick a dropped HTTP stream back up by itself
        options.add("--audio-time-stretch");   // keep the pitch right when VLC speeds up to catch up
        vlc = new LibVLC(c.getApplicationContext(), options);
        mp = new MediaPlayer(vlc);
        mp.setEventListener(e -> onUi(() -> onVlcEvent(e)));
        cachingMs = cachingMs(Players.bufferSeconds(prefs.getString(Players.PREF_BUFFER, Players.BUFFER_DEFAULT)));
        hardware = !"software".equals(prefs.getString(Players.PREF_DECODER, "auto"));
        params = new TrackSelectionParameters.Builder(c).build();
    }

    /** VLC's network caching for a Settings buffer choice: at least 1 second (VLC's own default). */
    static int cachingMs(int bufferSeconds) { return Math.max(1000, bufferSeconds * 1000); }

    // ---------------------------------------------------------------- state for Media3

    @Override protected State getState() {
        State.Builder b = new State.Builder()
                .setAvailableCommands(commands())
                .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setTrackSelectionParameters(params)
                .setVideoSize(videoSize)
                .setPlayerError(error);
        if (item == null) return b.setPlaybackState(STATE_IDLE).build();
        boolean live = lengthMs <= 0;
        MediaItemData.Builder d = new MediaItemData.Builder(itemUid)
                .setMediaItem(item)
                .setTracks(tracks)
                .setIsSeekable(seekable)
                .setIsDynamic(live)
                .setDurationUs(live ? C.TIME_UNSET : lengthMs * 1000);
        int state = !prepared || error != null ? STATE_IDLE
                : ended ? STATE_ENDED
                : opening || buffering || !started ? STATE_BUFFERING : STATE_READY;
        return b.setPlaylist(Collections.singletonList(d.build()))
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(state)
                .setIsLoading(state == STATE_BUFFERING)
                .setContentPositionMs(this::position)
                .build();
    }

    private Commands commands() {
        Commands.Builder c = new Commands.Builder().addAll(
                COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_RELEASE,
                COMMAND_SET_MEDIA_ITEM, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE,
                COMMAND_GET_TRACKS, COMMAND_SET_TRACK_SELECTION_PARAMETERS, COMMAND_SET_VIDEO_SURFACE,
                COMMAND_SEEK_TO_DEFAULT_POSITION);
        // Before the stream starts nobody knows yet if it can seek; allow it so a resume point can be set.
        if (seekable || !started) c.addAll(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD);
        return c.build();
    }

    private long position() {
        if (released) return lastPos;
        if (!started) return startMs;
        if (seekTarget >= 0 && SystemClock.elapsedRealtime() - seekAt < 1500) return seekTarget;
        seekTarget = -1;
        lastPos = Math.max(0, mp.getTime());
        return lastPos;
    }

    // ---------------------------------------------------------------- commands from the app

    @Override protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> items, int startIndex, long startPositionMs) {
        item = items.isEmpty() ? null : items.get(Math.max(0, Math.min(startIndex, items.size() - 1)));
        itemUid++;
        startMs = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        if (prepared && item != null) open();
        else { mp.stop(); reset(); }
        return done();
    }

    @Override protected ListenableFuture<?> handlePrepare() {
        boolean was = prepared;
        prepared = true;
        // setMediaItem on a prepared player has already opened the new item.
        if (item != null && (!was || error != null)) open();
        return done();
    }

    @Override protected ListenableFuture<?> handleSetPlayWhenReady(boolean play) {
        playWhenReady = play;
        if (prepared && item != null && error == null) {
            if (play && ended) { startMs = 0; open(); }
            else if (play) mp.play();
            else if (started) mp.pause();
        }
        return done();
    }

    @Override protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        long to = positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
        if (ended && prepared) { startMs = to; open(); return done(); }
        if (started) {
            if (!mp.isSeekable()) return done();
            if (lengthMs > 0) to = Math.min(to, lengthMs);
            seekTarget = to;
            seekAt = SystemClock.elapsedRealtime();
            mp.setTime(to);
        } else startMs = to;
        return done();
    }

    @Override protected ListenableFuture<?> handleStop() {
        prepared = false;
        mp.stop();
        reset();
        return done();
    }

    @Override protected ListenableFuture<?> handleRelease() {
        if (released) return done();
        lastPos = position();
        released = true;
        ui.removeCallbacksAndMessages(null);
        detach();
        mp.setEventListener(null);
        mp.stop();
        mp.release();
        vlc.release();
        return done();
    }

    @Override protected ListenableFuture<?> handleSetTrackSelectionParameters(TrackSelectionParameters p) {
        params = p;
        applySubtitles();
        return done();
    }

    @Override protected ListenableFuture<?> handleSetVideoOutput(Object out) {
        if (out instanceof SurfaceView) attach((SurfaceView) out);
        return done();
    }

    @Override protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object out) {
        if (out == null || out == videoView) detach();
        return done();
    }

    // ---------------------------------------------------------------- VLC

    /** Starts (or restarts) the current item from {@link #startMs}. */
    private void open() {
        reset();
        error = null;
        opening = true;
        Uri uri = item.localConfiguration == null ? null : item.localConfiguration.uri;
        if (uri == null) { fail("Nothing to play."); return; }
        Media m = new Media(vlc, uri);
        m.setHWDecoderEnabled(hardware, false);
        m.addOption(":network-caching=" + cachingMs);
        mp.setMedia(m);
        m.release();
        mp.play();
    }

    private void reset() {
        opening = buffering = started = ended = seekable = false;
        lengthMs = 0;
        seekTarget = -1;
        tracks = Tracks.EMPTY;
    }

    private void onVlcEvent(MediaPlayer.Event e) {
        if (released) return;
        switch (e.type) {
            case MediaPlayer.Event.Opening: opening = true; break;
            case MediaPlayer.Event.Buffering: buffering = e.getBuffering() < 100f; break;
            case MediaPlayer.Event.Playing:
                opening = buffering = false;
                if (!started) {
                    started = true;
                    seekable = mp.isSeekable();
                    lengthMs = Math.max(lengthMs, mp.getLength());
                    if (startMs > 0 && seekable) { mp.setTime(startMs); seekTarget = startMs; seekAt = SystemClock.elapsedRealtime(); }
                    refreshTracks();
                }
                if (!playWhenReady) mp.pause();
                break;
            case MediaPlayer.Event.Paused: break;
            case MediaPlayer.Event.LengthChanged: lengthMs = e.getLengthChanged(); break;
            case MediaPlayer.Event.SeekableChanged: seekable = e.getSeekable(); break;
            case MediaPlayer.Event.ESAdded:
            case MediaPlayer.Event.ESDeleted:
            case MediaPlayer.Event.ESSelected: refreshTracks(); break;
            case MediaPlayer.Event.EndReached:
                // A live stream never really ends: the server dropped it. Report it so Recovery reconnects.
                if (lengthMs <= 0 || !seekable) fail("The stream stopped.");
                else ended = true;
                break;
            case MediaPlayer.Event.EncounteredError: fail("VLC couldn't play this stream."); break;
            default: return; // time and position updates: PlayerView reads the position itself
        }
        invalidateState();
    }

    private void fail(String why) {
        // An input/output error, so Recovery treats it like a network drop and reconnects.
        error = new PlaybackException(why, null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        opening = buffering = false;
        invalidateState();
    }

    /** VLC's subtitle and closed-caption tracks, shown to the app as Media3 text tracks. */
    private void refreshTracks() {
        MediaPlayer.TrackDescription[] spus = mp.getSpuTracks();
        int selected = mp.getSpuTrack();
        List<Tracks.Group> groups = new ArrayList<>();
        if (spus != null) for (MediaPlayer.TrackDescription t : spus) {
            if (t.id < 0) continue; // VLC's "Disable" entry
            Format f = new Format.Builder().setId(String.valueOf(t.id)).setLabel(t.name).setSampleMimeType(MimeTypes.TEXT_UNKNOWN).build();
            groups.add(new Tracks.Group(new TrackGroup("vlc-spu-" + t.id, f), false, new int[] {C.FORMAT_HANDLED}, new boolean[] {t.id == selected}));
        }
        tracks = new Tracks(groups);
        applySubtitles();
    }

    /** Applies the app's subtitle choice: off, a picked track, or VLC's own default. */
    private void applySubtitles() {
        if (!started || released) return;
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            if (mp.getSpuTrack() >= 0) mp.setSpuTrack(-1);
            return;
        }
        for (TrackSelectionOverride o : params.overrides.values()) {
            if (o.getType() != C.TRACK_TYPE_TEXT || o.trackIndices.isEmpty()) continue;
            try {
                int id = Integer.parseInt(o.mediaTrackGroup.getFormat(o.trackIndices.get(0)).id);
                if (mp.getSpuTrack() != id) mp.setSpuTrack(id);
            } catch (RuntimeException ignored) { }
            return;
        }
    }

    // ---------------------------------------------------------------- picture

    private void attach(SurfaceView v) {
        if (v == videoView) return;
        detach();
        videoView = v;
        IVLCVout vout = mp.getVLCVout();
        vout.setVideoView(v);
        if (v.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) v.getParent();
            subtitleView = new SurfaceView(v.getContext());
            subtitleView.setZOrderMediaOverlay(true);
            subtitleView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
            parent.addView(subtitleView, parent.indexOfChild(v) + 1, new ViewGroup.LayoutParams(-1, -1));
            vout.setSubtitlesView(subtitleView);
        }
        vout.attachViews((o, w, h, visibleW, visibleH, sarNum, sarDen) -> onUi(() -> videoLayout(visibleW, visibleH, sarNum, sarDen)));
        v.addOnLayoutChangeListener(sizer);
        windowSize(v.getWidth(), v.getHeight());
    }

    private void detach() {
        if (videoView == null) return;
        videoView.removeOnLayoutChangeListener(sizer);
        mp.getVLCVout().detachViews();
        if (subtitleView != null && subtitleView.getParent() instanceof ViewGroup) ((ViewGroup) subtitleView.getParent()).removeView(subtitleView);
        subtitleView = null;
        videoView = null;
    }

    private void windowSize(int w, int h) {
        if (w > 0 && h > 0 && !released) mp.getVLCVout().setWindowSize(w, h);
    }

    /** Tells PlayerView the picture's shape so it letterboxes it like ExoPlayer's. */
    private void videoLayout(int w, int h, int sarNum, int sarDen) {
        if (released || w <= 0 || h <= 0) return;
        float par = sarNum > 0 && sarDen > 0 ? (float) sarNum / sarDen : 1f;
        videoSize = new VideoSize(w, h, par);
        invalidateState();
    }

    private void onUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else ui.post(r);
    }

    private static ListenableFuture<?> done() { return Futures.immediateVoidFuture(); }
}
