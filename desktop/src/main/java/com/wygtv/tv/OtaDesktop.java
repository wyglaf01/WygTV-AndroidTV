package com.wygtv.tv;

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * WygOTA for Ubuntu / desktop Linux: live over-the-air TV from an HDHomeRun tuner. The picture is on top
 * with what's on now beside it, and the TV guide below. Playback is VLC (libVLC), which plays the MPEG-2
 * and H.264 video and AC-3 audio that antenna TV uses.
 *
 * Keys: ▲ ▼ pick a channel in the guide, Enter plays it, Page Up / Page Down (or + / -) change channel,
 * digits then Enter jump to a channel ("4.1"), F or F11 full screen, Esc leaves full screen, M mutes.
 */
public final class OtaDesktop {
    static final String NAME = "WygOTA";
    private final Preferences prefs = Preferences.userRoot().node("wygota");
    private final JFrame frame = new JFrame(NAME);
    private final OtaGuide guide = new OtaGuide();
    private final JLabel tunerLabel = new JLabel(""), status = new JLabel(" "), chTitle = new JLabel(" "), nowTitle = new JLabel(" "),
            nowTime = new JLabel(" "), nextLine = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JTextArea nowDesc = new JTextArea();
    private JPanel header, info, statusBar;
    private JScrollPane guideScroll;
    private JSplitPane split;
    private EmbeddedMediaPlayerComponent player;
    private String base = "", lineupUrl = "";
    private List<OtaLineup.Channel> channels = Collections.emptyList();
    private boolean fullScreen;
    private final StringBuilder typed = new StringBuilder();
    private final Timer typedTimer = new Timer(2000, e -> jumpToTyped());

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel"); } catch (Exception ignored) { }
        darkNimbus();
        boolean vlc = new NativeDiscovery().discover();
        SwingUtilities.invokeLater(() -> new OtaDesktop().start(vlc));
    }

    private static void darkNimbus() {
        UIManager.put("control", OtaGuide.PANEL);
        UIManager.put("info", OtaGuide.PANEL);
        UIManager.put("nimbusBase", new Color(30, 44, 74));
        UIManager.put("nimbusFocus", OtaGuide.ACCENT);
        UIManager.put("nimbusLightBackground", new Color(30, 42, 66));
        UIManager.put("nimbusSelectionBackground", OtaGuide.SELECTED);
        UIManager.put("text", OtaGuide.TEXT);
    }

    private void start(boolean vlc) {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setIconImage(icon());
        frame.getContentPane().setBackground(OtaGuide.BG);
        frame.setLayout(new BorderLayout());

        header = new JPanel(new BorderLayout());
        header.setBackground(OtaGuide.PANEL);
        header.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 12));
        JLabel brand = new JLabel(NAME);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 24f));
        brand.setForeground(OtaGuide.ACCENT);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        left.setOpaque(false);
        left.add(brand);
        tunerLabel.setForeground(OtaGuide.MUTED);
        left.add(tunerLabel);
        header.add(left, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(button("Tuner…", () -> setup(null)));
        buttons.add(button("Reload guide", this::loadGuide));
        buttons.add(button("Full screen (F)", this::toggleFullScreen));
        header.add(buttons, BorderLayout.EAST);
        frame.add(header, BorderLayout.NORTH);

        JComponent video;
        if (vlc) {
            // Deinterlace: antenna TV is mostly 1080i / 480i.
            player = new EmbeddedMediaPlayerComponent("--no-video-title-show", "--deinterlace=1", "--deinterlace-mode=yadif2x") {
                @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) toggleFullScreen(); }
            };
            player.videoSurfaceComponent().setFocusable(false);
            player.mediaPlayer().events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override public void playing(MediaPlayer mp) { SwingUtilities.invokeLater(() -> { status.setText(" "); flash(); }); }
                @Override public void error(MediaPlayer mp) {
                    SwingUtilities.invokeLater(() -> status.setText("Couldn't play channel " + guide.playing + ". All of the tuner's tuners may be busy, or the signal is too weak."));
                }
                @Override public void finished(MediaPlayer mp) { SwingUtilities.invokeLater(() -> status.setText("Channel " + guide.playing + " stopped.")); }
            });
            player.mediaPlayer().audio().setVolume(prefs.getInt("volume", 100));
            video = player;
        } else {
            JLabel missing = new JLabel("<html><div style='text-align:center;width:420px'><b>VLC is needed to play TV.</b><br><br>"
                    + "Install it with:<br><tt>sudo apt install vlc</tt><br><br>then open WygOTA again.</div></html>", SwingConstants.CENTER);
            missing.setForeground(OtaGuide.TEXT);
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(Color.BLACK);
            p.add(missing);
            video = p;
        }
        video.setMinimumSize(new Dimension(320, 180));

        info = new JPanel();
        info.setLayout(new javax.swing.BoxLayout(info, javax.swing.BoxLayout.Y_AXIS));
        info.setBackground(OtaGuide.PANEL);
        info.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        info.setPreferredSize(new Dimension(380, 300));
        style(chTitle, 22f, true, OtaGuide.ACCENT);
        style(nowTitle, 18f, true, OtaGuide.TEXT);
        style(nowTime, 13f, false, OtaGuide.MUTED);
        style(nextLine, 13f, false, OtaGuide.MUTED);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        progress.setForeground(OtaGuide.ACCENT);
        progress.setBorderPainted(false);
        nowDesc.setLineWrap(true); nowDesc.setWrapStyleWord(true); nowDesc.setEditable(false); nowDesc.setFocusable(false);
        nowDesc.setBackground(OtaGuide.PANEL); nowDesc.setBorder(BorderFactory.createEmptyBorder()); nowDesc.setForeground(OtaGuide.TEXT); nowDesc.setFont(nowDesc.getFont().deriveFont(14f));
        for (JComponent c : new JComponent[]{chTitle, nowTitle, nowTime, progress, nowDesc, nextLine}) {
            c.setAlignmentX(0f);
            info.add(c);
            info.add(javax.swing.Box.createVerticalStrut(c == progress ? 12 : 6));
        }

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Color.BLACK);
        top.add(video, BorderLayout.CENTER);
        top.add(info, BorderLayout.EAST);

        guideScroll = new JScrollPane(guide);
        guideScroll.setRowHeaderView(guide.channelColumn);
        guideScroll.setColumnHeaderView(guide.timeHeader);
        guideScroll.setBorder(BorderFactory.createEmptyBorder());
        guideScroll.getViewport().setBackground(OtaGuide.BG);
        guideScroll.getVerticalScrollBar().setUnitIncrement(OtaGuide.ROW_H);
        guideScroll.getHorizontalScrollBar().setUnitIncrement(30 * OtaGuide.PX_PER_MIN);
        ToolTipManager.sharedInstance().setDismissDelay(15000);

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, guideScroll);
        split.setResizeWeight(0.58);
        split.setBorder(null);
        split.setDividerSize(6);
        frame.add(split, BorderLayout.CENTER);

        statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(OtaGuide.BG);
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
        status.setForeground(OtaGuide.MUTED);
        statusBar.add(status, BorderLayout.CENTER);
        JLabel keys = new JLabel("▲▼ pick  •  Enter play  •  PgUp/PgDn channel  •  F full screen  •  M mute");
        keys.setForeground(OtaGuide.MUTED);
        statusBar.add(keys, BorderLayout.EAST);
        frame.add(statusBar, BorderLayout.SOUTH);

        guide.onSelect = this::showInfo;
        guide.onPlay = this::play;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::key);
        new Timer(30_000, e -> { guide.tick(); showInfo(guide.selectedChannel()); }).start();
        new Timer(30 * 60_000, e -> loadGuide()).start();
        typedTimer.setRepeats(false);

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                if (player != null) { prefs.putInt("volume", player.mediaPlayer().audio().volume()); player.release(); }
                System.exit(0);
            }
        });
        frame.setSize(1280, 800);
        frame.setMinimumSize(new Dimension(800, 520));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        split.setDividerLocation(0.58);

        base = prefs.get("base", "");
        lineupUrl = prefs.get("lineup", "");
        connectSaved();
    }

    private static void style(JLabel l, float size, boolean bold, Color c) {
        l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        l.setForeground(c);
    }

    private JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private Image icon() {
        try (InputStream in = OtaDesktop.class.getResourceAsStream("/wygota.png")) { return in == null ? null : ImageIO.read(in); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ tuner and channels

    /** Opens the saved tuner, or tries hdhomerun.local on first run; asks for the address when that fails. */
    private void connectSaved() {
        status.setText("Connecting to the tuner…");
        new SwingWorker<Object[], Void>() {
            @Override protected Object[] doInBackground() throws Exception {
                String b = base.isEmpty() ? Hdhr.baseFromInput(Hdhr.DEFAULT_HOST) : base;
                Hdhr.Device dev;
                try { dev = Hdhr.describe(b); } catch (Exception e) {
                    // The tuner may have a new address: look for it on the network.
                    dev = null;
                    for (Hdhr.Device d : Hdhr.discoverAll()) if (dev == null || d.id.equals(prefs.get("device", ""))) dev = d;
                    if (dev == null) throw e;
                }
                return new Object[]{dev, OtaLineup.load(dev.lineupUrl)};
            }

            @SuppressWarnings("unchecked")
            @Override protected void done() {
                try {
                    Object[] r = get();
                    connected((Hdhr.Device) r[0], (List<OtaLineup.Channel>) r[1]);
                } catch (Exception e) {
                    status.setText("No tuner connected.");
                    setup(base.isEmpty() ? null : "Couldn't reach the tuner at " + Hdhr.hostOf(base) + ".");
                }
            }
        }.execute();
    }

    private void setup(String problem) {
        new OtaSetup(frame, base.isEmpty() ? "" : Hdhr.hostOf(base), problem, this::connected).setVisible(true);
    }

    private void connected(Hdhr.Device dev, List<OtaLineup.Channel> chans) {
        base = dev.base;
        lineupUrl = dev.lineupUrl;
        prefs.put("base", base);
        prefs.put("lineup", lineupUrl);
        prefs.put("device", dev.id);
        channels = chans;
        tunerLabel.setText(dev.summary() + "  •  " + chans.size() + " channels");
        guide.setChannels(chans);
        if (chans.isEmpty()) { status.setText("The tuner has no channels yet. Run a channel scan at " + Hdhr.hostOf(base) + "."); return; }
        int i = guide.indexOf(prefs.get("last", ""));
        if (i < 0) i = guide.step(0);
        guide.select(i);
        play(guide.selectedChannel());
        loadGuide();
    }

    private void loadGuide() {
        if (base.isEmpty()) return;
        final String b = base;
        new SwingWorker<Map<String, List<Xmltv.Program>>, Void>() {
            @Override protected Map<String, List<Xmltv.Program>> doInBackground() throws Exception { return Hdhr.loadGuide(b, OtaGuide.HOURS); }

            @Override protected void done() {
                try {
                    guide.setGuide(get());
                    showInfo(guide.selectedChannel());
                } catch (Exception e) {
                    status.setText("The TV guide couldn't be loaded (it needs an internet connection). Channels still play.");
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------ playing

    private void play(OtaLineup.Channel c) {
        if (c == null) return;
        guide.playing = c.number;
        guide.select(guide.indexOf(c.number));
        prefs.put("last", c.number);
        frame.setTitle(NAME + "  •  " + c.title());
        if (player == null) return;
        status.setText("Tuning " + c.title() + "…");
        player.mediaPlayer().media().play(c.url, ":network-caching=1500", ":http-reconnect");
    }

    private void zap(int step) {
        int i = guide.step(step);
        if (i >= 0) play(guide.rows.get(i).channel);
    }

    private void showInfo(OtaLineup.Channel c) {
        if (c == null) return;
        chTitle.setText(c.title() + c.hdTag());
        long now = System.currentTimeMillis();
        Xmltv.Program p = guide.at(c, now);
        if (p == null) {
            nowTitle.setText(c.group);
            nowTime.setText("No guide information");
            progress.setVisible(false);
            nowDesc.setText("");
        } else {
            nowTitle.setText("<html>" + OtaGuide.escape(p.title) + "</html>");
            nowTime.setText("Now  •  " + guide.span(p) + "  •  " + Math.max(0, (p.stop - now) / 60_000) + " min left");
            progress.setVisible(true);
            progress.setValue((int) (1000 * (now - p.start) / Math.max(1, p.stop - p.start)));
            nowDesc.setText(p.desc == null ? "" : p.desc);
        }
        Xmltv.Program next = guide.after(c, p == null ? now : p.stop);
        nextLine.setText(next == null ? " " : "<html>Next  •  " + guide.span(next) + "  " + OtaGuide.escape(next.title) + "</html>");
    }

    /** Full screen: shows the channel number and name over the picture for a few seconds once it starts. */
    private void flash() {
        OtaLineup.Channel c = guide.selectedChannel();
        if (fullScreen && player != null && c != null) {
            player.mediaPlayer().marquee().setText(c.title());
            player.mediaPlayer().marquee().setSize(36);
            player.mediaPlayer().marquee().setTimeout(3000);
            player.mediaPlayer().marquee().setPosition(uk.co.caprica.vlcj.player.base.MarqueePosition.TOP_LEFT);
            player.mediaPlayer().marquee().enable(true);
        }
    }

    // ------------------------------------------------------------------ full screen and keys

    private void toggleFullScreen() {
        fullScreen = !fullScreen;
        header.setVisible(!fullScreen);
        info.setVisible(!fullScreen);
        statusBar.setVisible(!fullScreen);
        guideScroll.setVisible(!fullScreen);
        split.setDividerSize(fullScreen ? 0 : 6);
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (gd.isFullScreenSupported()) gd.setFullScreenWindow(fullScreen ? frame : null);
        else frame.setExtendedState(fullScreen ? JFrame.MAXIMIZED_BOTH : JFrame.NORMAL);
        if (!fullScreen) SwingUtilities.invokeLater(() -> split.setDividerLocation(0.58));
        frame.revalidate();
    }

    private boolean key(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED || !frame.isActive()) return false;
        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof javax.swing.text.JTextComponent
                && !(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == nowDesc)) return false;
        char ch = e.getKeyChar();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP: guide.select(guide.step(-1)); return true;
            case KeyEvent.VK_DOWN: guide.select(guide.step(1)); return true;
            case KeyEvent.VK_ENTER:
                if (typed.length() > 0) jumpToTyped(); else play(guide.selectedChannel());
                return true;
            case KeyEvent.VK_PAGE_UP: case KeyEvent.VK_ADD: zap(-1); return true;
            case KeyEvent.VK_PAGE_DOWN: case KeyEvent.VK_SUBTRACT: zap(1); return true;
            case KeyEvent.VK_F11: case KeyEvent.VK_F: toggleFullScreen(); return true;
            case KeyEvent.VK_ESCAPE: if (fullScreen) toggleFullScreen(); return true;
            case KeyEvent.VK_M: if (player != null) player.mediaPlayer().audio().mute(); return true;
            default: break;
        }
        if (ch == '+') { zap(-1); return true; }
        if (ch == '-' && typed.length() == 0) { zap(1); return true; }
        if (Character.isDigit(ch) || ((ch == '.' || ch == '-') && typed.length() > 0)) {
            typed.append(ch == '-' ? '.' : ch);
            status.setText("Channel " + typed + "_");
            typedTimer.restart();
            return true;
        }
        return false;
    }

    /** Plays the channel typed with the number keys: "4.1", or "4" for the first sub-channel of 4. */
    private void jumpToTyped() {
        typedTimer.stop();
        String t = typed.toString();
        typed.setLength(0);
        if (t.isEmpty()) return;
        OtaLineup.Channel best = null;
        for (OtaLineup.Channel c : channels) {
            if (c.number.equals(t)) { best = c; break; }
            if (best == null && (c.number.startsWith(t + ".") || c.number.startsWith(t + "-"))) best = c;
        }
        if (best == null) status.setText("There's no channel " + t + ".");
        else play(best);
    }
}
