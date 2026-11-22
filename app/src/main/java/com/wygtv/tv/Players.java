package com.wygtv.tv;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

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

    static ExoPlayer create(Context c) {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(c)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        return new ExoPlayer.Builder(c, renderers).build();
    }

    /** Explains HDHomeRun problems in plain words: all tuners busy, weak signal, or video this device can't decode. */
    static void explainTunerProblems(Context c, ExoPlayer player) {
        player.addListener(new Player.Listener() {
            boolean toldVideo;

            @Override public void onPlayerError(PlaybackException error) {
                Toast.makeText(c, tunerMessage(error), Toast.LENGTH_LONG).show();
            }

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
