package com.wygtv.tv;

import android.content.SharedPreferences;
import android.widget.Toast;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Subtitles and closed captions in the full-screen player.
 *
 * Live channels usually carry closed captions (CEA-608/708) inside the video, which the player shows as a
 * subtitle track. Movies and series can have subtitle tracks of their own. Settings decides whether they
 * start switched on; in the player, MENU then "Subtitles" (or the CC / captions key) picks a track or turns them off.
 */
@OptIn(markerClass = UnstableApi.class)
final class Subtitles {
    static final String PREF_LIVE = "subtitles_live", PREF_VOD = "subtitles_vod";

    private final MainActivity a;
    private final ExoPlayer player;
    private boolean wanted;      // show a subtitle track whenever the stream has one
    private boolean forcedHere;  // already picked a track for what is playing now

    private Subtitles(MainActivity a, ExoPlayer player, boolean on) {
        this.a = a;
        this.player = player;
        player.addListener(new Player.Listener() {
            @Override public void onTracksChanged(Tracks tracks) { ensureShown(tracks); }
            @Override public void onMediaItemTransition(androidx.media3.common.MediaItem item, int reason) { forcedHere = false; }
        });
        set(on);
    }

    /** Sets up subtitles for a new player, switched on or off from the Settings choice for live or on-demand. */
    static Subtitles attach(MainActivity a, ExoPlayer player, boolean live) {
        return new Subtitles(a, player, defaultOn(a.prefs, live));
    }

    static boolean defaultOn(SharedPreferences prefs, boolean live) {
        return prefs.getBoolean(live ? PREF_LIVE : PREF_VOD, false);
    }

    boolean on() { return wanted; }

    /** Channel changes keep the player, so a new stream gets a fresh pick of track. */
    void newStream() { forcedHere = false; }

    /** On: prefer the TV's language, accept tracks with no language (most closed captions). Off: no text track. */
    private void set(boolean on) {
        wanted = on;
        forcedHere = false;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (on) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(Locale.getDefault().getLanguage()).setSelectUndeterminedTextLanguage(true);
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        player.setTrackSelectionParameters(b.build());
        ensureShown(player.getCurrentTracks());
    }

    /** When subtitles are wanted but the language rules picked nothing, show the first track the stream has. */
    private void ensureShown(Tracks tracks) {
        if (!wanted || forcedHere || tracks == null || tracks.isTypeSelected(C.TRACK_TYPE_TEXT)) return;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                forcedHere = true;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i)).build());
                return;
            }
        }
    }

    /** The CC / captions key: on or off. */
    void toggle() {
        set(!wanted);
        toast(wanted ? (textTracks().isEmpty() ? "Subtitles on (this channel has none right now)" : "Subtitles on") : "Subtitles off");
    }

    /** MENU > Subtitles: Off, then every subtitle track in the stream. */
    void choose() {
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> index = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        labels.add("Off");
        int checked = 0;
        for (Tracks.Group g : textTracks()) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                groups.add(g);
                index.add(i);
                labels.add(label(g.getTrackFormat(i), labels.size()));
                if (wanted && g.isTrackSelected(i)) checked = labels.size() - 1;
            }
        }
        if (groups.isEmpty()) {
            String[] items = {"Off", "On (show them when the channel sends any)"};
            new Dialogs.Builder(a).setTitle("Subtitles  •  none in this stream right now")
                    .setSingleChoiceItems(items, wanted ? 1 : 0, (d, w) -> { d.dismiss(); set(w == 1); }).show();
            return;
        }
        new Dialogs.Builder(a).setTitle("Subtitles").setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, w) -> {
            d.dismiss();
            if (w == 0) { set(false); return; }
            Tracks.Group g = groups.get(w - 1);
            wanted = true;
            forcedHere = true;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), index.get(w - 1))).build());
        }).show();
    }

    private List<Tracks.Group> textTracks() {
        List<Tracks.Group> out = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == C.TRACK_TYPE_TEXT && g.isSupported()) out.add(g);
        return out;
    }

    /** "English (CC)", "Spanish", "Track 2", plus the stream's own label when it has one. */
    static String label(Format f, int number) { return label(f.language, f.label, f.sampleMimeType, f.roleFlags, f.selectionFlags, number); }

    static String label(String language, String trackLabel, String mimeType, int roleFlags, int selectionFlags, int number) {
        String name = "";
        if (language != null && !language.isEmpty() && !C.LANGUAGE_UNDETERMINED.equals(language)) {
            name = new Locale(language).getDisplayLanguage();
            if (name.isEmpty()) name = language;
        }
        if (trackLabel != null && !trackLabel.isEmpty() && !trackLabel.equalsIgnoreCase(name)) name = name.isEmpty() ? trackLabel : name + " • " + trackLabel;
        String mime = mimeType == null ? "" : mimeType;
        boolean cc = MimeTypes.APPLICATION_CEA608.equals(mime) || MimeTypes.APPLICATION_CEA708.equals(mime) || mime.contains("cea-608") || mime.contains("cea-708");
        if (name.isEmpty()) name = cc ? "Closed captions" : "Track " + number;
        else if (cc) name += " (CC)";
        if ((roleFlags & C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 && !cc) name += " (SDH)";
        if ((selectionFlags & C.SELECTION_FLAG_FORCED) != 0) name += " (forced)";
        return name;
    }

    private void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }
}
