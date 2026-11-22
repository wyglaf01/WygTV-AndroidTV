package com.wygtv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/**
 * Builds every player in the app. Over-the-air TV (HDHomeRun) is MPEG-TS with MPEG-2 video and Dolby
 * AC-3 audio; many tablets and some TV boxes have no AC-3 decoder, so the FFmpeg audio extension
 * (AC-3, E-AC-3 and more) is added. The device's own decoders still win when it has them, so HDMI
 * passthrough to a soundbar keeps working.
 * The renderer settings are Media3 "unstable" API, which is opted into here.
 */
@OptIn(markerClass = UnstableApi.class)
final class Players {
    private Players() { }

    /** Settings: how many seconds to load before a stream starts (and after it runs dry) for the main player. */
    static final String PREF_BUFFER = "play_buffer";
    static final String[] BUFFER_KEYS = {"off", "small", "medium", "large"};
    static final String[] BUFFER_LABELS = {"Off  •  starts fastest", "Small  •  5 seconds (recommended)", "Medium  •  10 seconds", "Large  •  20 seconds"};
    static final String BUFFER_DEFAULT = "small";

    /** Settings: which video decoders to try first. */
    static final String PREF_DECODER = "video_decoder";
    static final String[] DECODER_KEYS = {"auto", "hardware", "software"};
    static final String[] DECODER_LABELS = {"Automatic (device default)", "Hardware first", "Software first"};

    /** Settings: which player engine plays full-screen video. "auto" = ExoPlayer, and VLC when ExoPlayer can't play a stream. */
    static final String PREF_ENGINE = "player_engine";
    static final String[] ENGINE_KEYS = {"auto", "exo", "vlc"};
    static final String[] ENGINE_LABELS = {"ExoPlayer, then VLC if a stream won't play (recommended)", "ExoPlayer only", "VLC"};
    static final String ENGINE_DEFAULT = "auto";

    static String engine(SharedPreferences prefs) {
        String k = prefs.getString(PREF_ENGINE, ENGINE_DEFAULT);
        for (String e : ENGINE_KEYS) if (e.equals(k)) return k;
        return ENGINE_DEFAULT;
    }

    /** Whether a stream ExoPlayer gave up on should be tried again in VLC. */
    static boolean vlcFallback(SharedPreferences prefs) { return "auto".equals(engine(prefs)); }

    /** The full-screen player: VLC when chosen in Settings, otherwise ExoPlayer. */
    static Player main(Context c, SharedPreferences prefs) {
        return "vlc".equals(engine(prefs)) ? new VlcPlayer(c, prefs) : create(c, prefs);
    }

    /** Seconds loaded before playback starts for a buffer choice; 0 keeps Media3's own 2.5 s start. */
    static int bufferSeconds(String key) {
        switch (key == null ? "" : key) {
            case "off": return 0;
            case "medium": return 10;
            case "large": return 20;
            default: return 5;
        }
    }

    /** {min buffer, max buffer, needed to start, needed to restart after running dry}, all in ms. */
    static int[] bufferMs(int seconds) {
        if (seconds <= 0) return new int[] {
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS };
        int start = seconds * 1000;
        int min = Math.max(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, start * 4);
        int max = Math.max(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, min + 20_000);
        return new int[] { min, max, start, Math.max(DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, start * 2) };
    }

    /** The guide preview: quick start, no extra buffer. */
    static ExoPlayer create(Context c) { return create(c, 0); }

    /** The full-screen player, with the buffer and decoder chosen in Settings. */
    static ExoPlayer create(Context c, SharedPreferences prefs) {
        return create(c, bufferSeconds(prefs.getString(PREF_BUFFER, BUFFER_DEFAULT)), prefs.getString(PREF_DECODER, "auto"));
    }

    private static ExoPlayer create(Context c, int bufferSeconds) {
        return create(c, bufferSeconds, c.getSharedPreferences("wygtv", Context.MODE_PRIVATE).getString(PREF_DECODER, "auto"));
    }

    private static ExoPlayer create(Context c, int bufferSeconds, String decoder) {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(c)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecs(decoder));
        // Many IPTV servers redirect from http to https (or the other way); allow it, and wait a bit longer on slow servers.
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(15_000);
        int[] b = bufferMs(bufferSeconds);
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(b[0], b[1], b[2], b[3])
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        return new ExoPlayer.Builder(c, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(c, http)))
                .setLoadControl(load)
                .build();
    }

    /** Puts hardware or software video decoders first; audio and "auto" keep the device's own order. */
    static MediaCodecSelector codecs(String decoder) {
        if (!"hardware".equals(decoder) && !"software".equals(decoder)) return MediaCodecSelector.DEFAULT;
        final boolean software = "software".equals(decoder);
        return (mimeType, secure, tunneling) -> {
            List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling);
            if (!MimeTypes.isVideo(mimeType)) return all;
            List<MediaCodecInfo> first = new ArrayList<>(), rest = new ArrayList<>();
            for (MediaCodecInfo info : all) (info.softwareOnly == software ? first : rest).add(info);
            first.addAll(rest);
            return first;
        };
    }

    /** Tells the viewer when this device can't decode an HDHomeRun channel's video. Errors are explained by {@link #tunerMessage} once {@link Recovery} gives up. */
    static void explainTunerProblems(Context c, Player player) {
        player.addListener(new Player.Listener() {
            boolean toldVideo;

            @Override public void onTracksChanged(Tracks tracks) {
                if (toldVideo || tracks.isEmpty()) return;
                boolean hasVideo = false, canPlayVideo = false;
                for (Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
                    hasVideo = true;
                    if (g.isSupported()) canPlayVideo = true;
                }
                if (hasVideo && !canPlayVideo) {
                    toldVideo = true;
                    Toast.makeText(c, "This device can't decode this channel's video (usually MPEG-2). Sound only. "
                            + "Most Android TV boxes can; many tablets can't.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    static String tunerMessage(PlaybackException error) {
        Throwable t = error;
        while (t != null && !(t instanceof HttpDataSource.InvalidResponseCodeException)) t = t.getCause();
        if (t != null) {
            int code = ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            if (code == 503) return "All of the HDHomeRun's tuners are in use (by another TV, the guide preview or a recording). Stop one and try again.";
            if (code == 404) return "The tuner doesn't have this channel any more. Refresh the tuner from Playlists.";
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
            return "Couldn't reach the HDHomeRun. Check it's on and on the same network.";
        return "This channel didn't play. The signal may be too weak right now.";
    }
}
