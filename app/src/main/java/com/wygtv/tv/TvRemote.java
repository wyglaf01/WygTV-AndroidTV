package com.wygtv.tv;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.MediaItem;
import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Android TV remote handling for the whole app.
 *
 * Full-screen player: CH+/CH- (or D-pad up/down) change channel, digit keys type a channel number,
 * OK/Info shows the channel banner, Guide opens the TV guide, Last returns to the previous channel,
 * Menu opens options, and the media keys play, pause, rewind and fast-forward.
 * List screens: CH+/CH- and Page keys jump ten rows, Menu opens a row's options, Guide and Search jump
 * straight to the TV guide and channel search.
 */
@OptIn(markerClass = UnstableApi.class) // PlayerView controller show/hide/visibility calls
final class TvRemote {
    private static final int BANNER_MS = 5000, NUMBER_MS = 1800, LIST_JUMP = 10;
    private static final long SEEK_SHORT_MS = 10_000, SEEK_LONG_MS = 30_000;

    private final MainActivity a;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // What the player is showing.
    private PlayerView view;
    private String type = "", id = "", title = "", ext = "";
    private JSONArray zapList;
    private int zapIndex = -1;
    private JSONObject lastChannel;
    private int epgTicket;

    // Overlays.
    private LinearLayout banner;
    private TextView bannerNumber, bannerTitle, bannerNow, bannerNext, numberBox;
    private String typed = "";
    private final Runnable hideBanner = () -> { if (banner != null) banner.setVisibility(View.GONE); };
    private final Runnable commitNumber = this::commitNumber;

    // Full live channel list, loaded in the background so channel numbers can reach any channel.
    private JSONArray allLive;
    private String allLiveAccount = "";
    private boolean loadingAllLive;

    TvRemote(MainActivity a) { this.a = a; }

    String currentId() { return id; }

    // ---------------------------------------------------------------- player screen

    /** Wraps the PlayerView with the channel banner and number overlay, and remembers the channel list for zapping. */
    View playerScreen(PlayerView playerView, String type, String id, String ext, String title, JSONArray list) {
        this.view = playerView;
        this.type = type;
        this.id = id;
        this.ext = ext;
        this.title = title;
        this.typed = "";
        this.lastChannel = null;
        ui.removeCallbacksAndMessages(null);
        playerView.setKeepScreenOn(true); // stop the TV screensaver from starting while something plays
        playerView.setControllerShowTimeoutMs(4000);

        zapList = null;
        zapIndex = -1;
        if (isLive()) {
            if (indexOf(list, id) >= 0) zapList = list;
            else if (allLiveReady() && indexOf(allLive, id) >= 0) zapList = visible(allLive);
            zapIndex = indexOf(zapList, id);
        }

        FrameLayout frame = new FrameLayout(a);
        frame.setBackgroundColor(0xff000000);
        frame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        buildBanner(frame);
        numberBox = text("", 44, Theme.ACCENT, true);
        numberBox.setGravity(Gravity.CENTER);
        numberBox.setBackground(panel());
        numberBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(a.dp(170), a.dp(90), Gravity.TOP | Gravity.END);
        nlp.setMargins(0, a.dp(28), a.dp(36), 0);
        frame.addView(numberBox, nlp);

        if (isLive()) {
            playerView.setControllerAutoShow(false);
            ensureAllLive();
            showBanner();
        }
        return frame;
    }

    private void buildBanner(FrameLayout frame) {
        banner = new LinearLayout(a);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(a.dp(26), a.dp(16), a.dp(26), a.dp(16));
        banner.setBackground(panel());
        banner.setVisibility(View.GONE);
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        bannerNumber = text("", 30, Theme.ACCENT, true);
        top.addView(bannerNumber, new LinearLayout.LayoutParams(-2, -2));
        bannerTitle = text("", 28, Theme.TEXT, true);
        bannerTitle.setSingleLine(true);
        bannerTitle.setPadding(a.dp(18), 0, 0, 0);
        top.addView(bannerTitle, new LinearLayout.LayoutParams(0, -2, 1));
        banner.addView(top);
        bannerNow = text("", 19, Theme.TEXT, false);
        bannerNow.setSingleLine(true);
        banner.addView(bannerNow);
        bannerNext = text("", 16, Theme.MUTED, false);
        bannerNext.setSingleLine(true);
        banner.addView(bannerNext);
        TextView hints = text(isLive()
                ? "▲▼ / CH± change channel    0-9 channel number    OK info    GUIDE TV guide    MENU options    BACK exit"
                : "◀▶ seek    ▶❚❚ play / pause    MENU options    BACK exit", 14, Theme.MUTED, false);
        hints.setPadding(0, a.dp(8), 0, 0);
        banner.addView(hints);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(a.dp(36), 0, a.dp(36), a.dp(28));
        frame.addView(banner, lp);
    }

    private void showBanner() {
        if (banner == null) return;
        bannerNumber.setText(channelNumber(current()));
        bannerTitle.setText(title);
        banner.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, BANNER_MS);
        if (isLive()) loadNowNext();
        else { bannerNow.setText(""); bannerNext.setText(""); }
    }

    private void loadNowNext() {
        final int ticket = ++epgTicket;
        final String forId = id;
        bannerNow.setText("Loading what's on…");
        bannerNext.setText("");
        a.apiQuiet("get_short_epg&stream_id=" + a.enc(forId) + "&limit=2", o -> {
            if (ticket != epgTicket || !forId.equals(id)) return;
            JSONArray list = o == null ? null : o.optJSONArray("epg_listings");
            bannerNow.setText(list == null || list.length() == 0 ? "No guide information" : "Now  " + epgLine(list.optJSONObject(0)));
            bannerNext.setText(list != null && list.length() > 1 ? "Next  " + epgLine(list.optJSONObject(1)) : "");
        });
    }

    private String epgLine(JSONObject e) {
        if (e == null) return "";
        String t = a.epgText(e.optString("title"));
        long start = e.optLong("start_timestamp", 0) * 1000, stop = e.optLong("stop_timestamp", 0) * 1000;
        if (start <= 0 || stop <= 0) return t;
        java.text.DateFormat f = android.text.format.DateFormat.getTimeFormat(a);
        return f.format(new java.util.Date(start)) + " – " + f.format(new java.util.Date(stop)) + "   " + t;
    }

    // ---------------------------------------------------------------- keys

    /** Called from Activity.dispatchKeyEvent before any view sees the key. Returns true when the key was used. */
    boolean handleKey(KeyEvent e) {
        int key = e.getKeyCode();
        if (a.mode.equals("player") && a.player != null && view != null) return playerKey(e, key);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        return listKey(e, key);
    }

    private boolean playerKey(KeyEvent e, int key) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean controls = view.isControllerFullyVisible();
        int digit = RemoteKeys.digit(key);
        if (isLive() && digit >= 0) { if (down) typeDigit(digit); return true; }
        switch (key) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down && isLive()) zap(1);
                return isLive();
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down && isLive()) zap(-1);
                return isLive();
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!isLive() || controls) return false;
                if (down) zap(key == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (controls) return false;
                if (!typed.isEmpty()) { if (down) { ui.removeCallbacks(commitNumber); commitNumber(); } return true; }
                if (!isLive()) return false; // movies and episodes: OK shows the playback controls
                if (down && e.getRepeatCount() == 0) {
                    if (banner.getVisibility() == View.VISIBLE) { banner.setVisibility(View.GONE); view.showController(); }
                    else showBanner();
                }
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (down) { if (banner.getVisibility() == View.VISIBLE) hideBanner.run(); else showBanner(); }
                return true;
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (down && isLive()) lastChannel();
                return isLive();
            case KeyEvent.KEYCODE_GUIDE:
                if (down && isLive()) openGuideFromPlayer();
                return isLive();
            case KeyEvent.KEYCODE_MENU:
                if (down) playerMenu();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (down) { if (a.player.isPlaying()) a.player.pause(); else a.player.play(); flashControls(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down) { a.player.play(); flashControls(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) { a.player.pause(); flashControls(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (!down) a.onBackPressed();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) seek(key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? SEEK_LONG_MS : -SEEK_LONG_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (!isLive()) return false;
                if (down) zap(key == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (isLive() || controls) return false;
                if (down) seek(key == KeyEvent.KEYCODE_DPAD_RIGHT ? SEEK_SHORT_MS : -SEEK_SHORT_MS);
                return true;
            default:
                return false;
        }
    }

    /** Back in the player: first close the number entry or the playback controls, and only then leave. */
    boolean onBack() {
        if (!a.mode.equals("player") || view == null) return false;
        if (!typed.isEmpty()) { ui.removeCallbacks(commitNumber); typed = ""; numberBox.setVisibility(View.GONE); return true; }
        if (view.isControllerFullyVisible()) { view.hideController(); return true; }
        return false;
    }

    /** The player is closing: remember where we were so the previous screen can focus the same channel. */
    void onPlayerExit() {
        ui.removeCallbacksAndMessages(null);
        a.resumeStreamId = id;
        if (a.tvGuide != null && isLive()) a.tvGuide.focusStream(id);
        view = null;
        banner = null;
    }

    private void flashControls() { if (!isLive()) view.showController(); }

    private void seek(long by) {
        if (isLive() || !a.player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return;
        long duration = a.player.getDuration();
        long target = Math.max(0, a.player.getCurrentPosition() + by);
        if (duration > 0) target = Math.min(duration, target);
        a.player.seekTo(target);
        view.showController();
    }

    // ---------------------------------------------------------------- channel changes

    private boolean isLive() { return "live".equals(type); }

    private JSONObject current() { return zapList == null || zapIndex < 0 ? null : zapList.optJSONObject(zapIndex); }

    private void zap(int delta) {
        if (zapList == null || zapList.length() < 2 || zapIndex < 0) {
            Toast.makeText(a, "Open a channel list to change channels with the remote.", Toast.LENGTH_SHORT).show();
            return;
        }
        switchTo(zapList, RemoteKeys.wrap(zapIndex, delta, zapList.length()));
    }

    private void lastChannel() {
        if (lastChannel == null) return;
        JSONObject target = lastChannel;
        String targetId = target.optString("stream_id");
        if (indexOf(zapList, targetId) >= 0) switchTo(zapList, indexOf(zapList, targetId));
        else if (allLiveReady() && indexOf(allLive, targetId) >= 0) { JSONArray all = visible(allLive); switchTo(all, indexOf(all, targetId)); }
    }

    private void switchTo(JSONArray list, int index) {
        JSONObject ch = list == null ? null : list.optJSONObject(index);
        if (ch == null || a.player == null) return;
        JSONObject was = current();
        if (was != null) lastChannel = was;
        else if (!id.isEmpty()) lastChannel = channelStub(id, title, ext);
        zapList = list;
        zapIndex = index;
        id = ch.optString("stream_id");
        title = ch.optString("name", "Channel");
        ext = ch.optString("container_extension", "ts");
        if (ext.isEmpty()) ext = "ts";
        a.player.setMediaItem(MediaItem.fromUri(a.streamUrl("live", id, ext)));
        a.player.prepare();
        a.player.play();
        a.library.recordPlay("live", id, ext, title);
        view.hideController();
        showBanner();
    }

    private static JSONObject channelStub(String id, String title, String ext) {
        JSONObject o = new JSONObject();
        try { o.put("stream_id", id).put("name", title).put("container_extension", ext); } catch (org.json.JSONException ignored) {}
        return o;
    }

    private void typeDigit(int digit) {
        typed = RemoteKeys.appendDigit(typed, digit);
        numberBox.setText(typed);
        numberBox.setVisibility(View.VISIBLE);
        ui.removeCallbacks(commitNumber);
        ui.postDelayed(commitNumber, NUMBER_MS);
    }

    private void commitNumber() {
        String wanted = typed;
        typed = "";
        if (numberBox != null) numberBox.setVisibility(View.GONE);
        if (wanted.isEmpty() || view == null) return;
        // The provider's channel number first, within the list you are watching, then across all channels.
        int i = RemoteKeys.indexOfNumber(numbers(zapList), wanted);
        if (i >= 0) { switchTo(zapList, i); return; }
        if (allLiveReady()) {
            JSONArray all = visible(allLive);
            i = RemoteKeys.indexOfNumber(numbers(all), wanted);
            if (i >= 0) { switchTo(all, i); return; }
        }
        // Providers without channel numbers: use the position in the list.
        int pos = parse(wanted) - 1;
        if (zapList != null && pos >= 0 && pos < zapList.length() && RemoteKeys.indexOfNumber(numbers(zapList), "1") < 0) { switchTo(zapList, pos); return; }
        Toast.makeText(a, loadingAllLive ? "Still loading the channel list, try again in a moment." : "No channel " + wanted, Toast.LENGTH_SHORT).show();
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; } }

    private String channelNumber(JSONObject ch) {
        String n = ch == null ? "" : ch.optString("num");
        if (!n.isEmpty()) return n;
        return zapIndex >= 0 ? String.valueOf(zapIndex + 1) : "";
    }

    private static String[] numbers(JSONArray list) {
        if (list == null) return new String[0];
        String[] out = new String[list.length()];
        for (int i = 0; i < out.length; i++) { JSONObject o = list.optJSONObject(i); out[i] = o == null ? "" : o.optString("num"); }
        return out;
    }

    static int indexOf(JSONArray list, String streamId) {
        if (list == null || streamId == null) return -1;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && streamId.equals(o.optString("stream_id"))) return i;
        }
        return -1;
    }

    private boolean allLiveReady() { return allLive != null && allLiveAccount.equals(a.accountPrefix()); }

    /** All live channels minus hidden channels and hidden categories. */
    private JSONArray visible(JSONArray all) { return a.withoutHiddenChannels(a.withoutHiddenIn("live", all)); }

    private void ensureAllLive() {
        if (allLiveReady() || loadingAllLive) return;
        if (a.liveIndex != null && a.liveIndexAccount.equals(a.accountPrefix())) { setAllLive(a.liveIndex); return; }
        loadingAllLive = true;
        final String account = a.accountPrefix();
        a.apiQuiet("get_live_streams", o -> {
            loadingAllLive = false;
            JSONArray list = o == null ? null : o.optJSONArray("list");
            if (list != null && account.equals(a.accountPrefix())) setAllLive(list);
        });
    }

    private void setAllLive(JSONArray list) {
        allLive = list;
        allLiveAccount = a.accountPrefix();
        if (zapList == null && view != null && isLive()) {
            JSONArray all = visible(list);
            int i = indexOf(all, id);
            if (i >= 0) { zapList = all; zapIndex = i; }
        }
    }

    // ---------------------------------------------------------------- player menu and guide

    private void playerMenu() {
        ui.removeCallbacks(hideBanner);
        if (!isLive()) {
            boolean fav = a.isFavorite(type, id);
            new Dialogs.Builder(a).setTitle(title)
                    .setItems(new String[]{fav ? "Remove from Favorites" : "Add to Favorites", "Exit player"}, (d, w) -> {
                        if (w == 0) toast(a.toggleFavorite(type, id, title, ext) ? "Added to Favorites" : "Removed from Favorites");
                        else a.onBackPressed();
                    }).show();
            return;
        }
        boolean fav = a.isFavorite("live", id);
        new Dialogs.Builder(a).setTitle(title)
                .setItems(new String[]{fav ? "Remove from Favorites" : "Add to Favorites", "What's on (program guide)", "TV guide", "Channel list", "Exit player"}, (d, w) -> {
                    if (w == 0) toast(a.toggleFavorite("live", id, title, ext) ? "Added to Favorites" : "Removed from Favorites");
                    else if (w == 1) a.guide(id, title);
                    else if (w == 2) openGuideFromPlayer();
                    else if (w == 3) channelList();
                    else a.onBackPressed();
                }).show();
    }

    /** A quick channel picker over the video, so you can jump without leaving the player. */
    private void channelList() {
        if (zapList == null || zapList.length() == 0) { toast("No channel list to show."); return; }
        String[] names = new String[zapList.length()];
        for (int i = 0; i < names.length; i++) {
            JSONObject o = zapList.optJSONObject(i);
            String num = o == null ? "" : o.optString("num");
            names[i] = (num.isEmpty() ? String.valueOf(i + 1) : num) + "   " + (o == null ? "" : o.optString("name"));
        }
        final JSONArray list = zapList;
        AlertDialog d = new Dialogs.Builder(a).setTitle("Channels").setSingleChoiceItems(names, Math.max(0, zapIndex), (dialog, which) -> {
            dialog.dismiss();
            if (which != zapIndex || list != zapList) switchTo(list, which);
        }).create();
        d.show();
        if (zapIndex > 0 && d.getListView() != null) d.getListView().setSelection(zapIndex);
    }

    private void openGuideFromPlayer() {
        JSONArray channels = zapList;
        String streamId = id;
        a.closePlayer();
        a.resumeStreamId = streamId;
        if (channels != null && channels != a.items) { a.items = channels; a.category = ""; }
        a.openGuide(streamId);
    }

    // ---------------------------------------------------------------- list screens

    private boolean listKey(KeyEvent e, int key) {
        if (a.mode.equals("login") && a.getCurrentFocus() instanceof android.widget.EditText) return false;
        switch (key) {
            case KeyEvent.KEYCODE_GUIDE:
                if (a.mode.equals("guide")) return true;
                a.openGuide(null);
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                if (!a.mode.equals("search")) a.searchLive();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                return !a.mode.equals("guide") && jumpFocus(-LIST_JUMP);
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return !a.mode.equals("guide") && jumpFocus(LIST_JUMP);
            case KeyEvent.KEYCODE_MOVE_HOME:
                return jumpFocus(-100_000);
            case KeyEvent.KEYCODE_MOVE_END:
                return jumpFocus(100_000);
            case KeyEvent.KEYCODE_MENU: {
                View f = a.getCurrentFocus();
                if (f != null && f.isLongClickable()) { f.performLongClick(); return true; }
                return false;
            }
            default:
                return false;
        }
    }

    /** Move focus several rows up or down inside the focused list. */
    private boolean jumpFocus(int delta) {
        View f = a.getCurrentFocus();
        if (f == null || !(f.getParent() instanceof LinearLayout)) return false;
        LinearLayout list = (LinearLayout) f.getParent();
        if (list.getOrientation() != LinearLayout.VERTICAL) return false;
        int at = list.indexOfChild(f), n = list.getChildCount();
        int target = RemoteKeys.pageJump(at, delta, n);
        // Skip anything that can't take focus, in the direction of travel.
        int step = delta > 0 ? -1 : 1;
        while (target != at && !list.getChildAt(target).isFocusable()) target += step;
        if (target == at) return true;
        View v = list.getChildAt(target);
        v.requestFocus();
        if (list.getParent() instanceof ScrollView) {
            ScrollView sv = (ScrollView) list.getParent();
            sv.post(() -> sv.smoothScrollTo(0, Math.max(0, v.getTop() - sv.getHeight() / 3)));
        }
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private GradientDrawable panel() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Theme.alpha(Theme.PANEL,0xe6), Theme.alpha(Theme.BG,0xf0)});
        g.setCornerRadius(a.dp(14));
        g.setStroke(a.dp(2), Theme.STROKE_FOCUS);
        return g;
    }

}
