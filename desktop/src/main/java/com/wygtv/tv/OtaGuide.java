package com.wygtv.tv;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The TV guide grid: one row per channel (with a header row for each channel-number group), programmes
 * laid out on a time line about 12 hours long. Its channel column and time header are separate components
 * so they stay put while the grid scrolls (JScrollPane row and column headers).
 */
final class OtaGuide extends JComponent implements Scrollable {
    static final int HOURS = 12, PX_PER_MIN = 5, ROW_H = 46, GROUP_H = 26, CHANNEL_W = 250, TIME_H = 30;
    static final Color BG = new Color(14, 20, 34), PANEL = new Color(24, 34, 56), BLOCK = new Color(32, 46, 76),
            BLOCK_NOW = new Color(40, 66, 106), SELECTED = new Color(58, 92, 146), ACCENT = new Color(242, 186, 72),
            TEXT = new Color(240, 244, 250), MUTED = new Color(150, 162, 184), LINE = new Color(52, 64, 92), NOW_LINE = new Color(232, 84, 84);

    /** A row: a group header (channel == null) or a channel. */
    static final class Row {
        final String group; final OtaLineup.Channel channel; int y, h;
        Row(String group, OtaLineup.Channel channel) { this.group = group; this.channel = channel; }
    }

    final List<Row> rows = new ArrayList<>();
    private Map<String, List<Xmltv.Program>> guide = Collections.emptyMap();
    private long start = floor(System.currentTimeMillis());
    private int height;
    int selected = -1;
    String playing = "";
    Consumer<OtaLineup.Channel> onPlay = c -> { }, onSelect = c -> { };
    final JComponent channelColumn = new ChannelColumn(), timeHeader = new TimeHeader();
    private final SimpleDateFormat clock = new SimpleDateFormat("h:mm a");

    OtaGuide() {
        setOpaque(true);
        setToolTipText("");
        MouseAdapter click = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int i = rowAt(e.getY());
                if (i >= 0 && rows.get(i).channel != null) { select(i); onPlay.accept(rows.get(i).channel); }
            }
        };
        addMouseListener(click);
        channelColumn.addMouseListener(click);
    }

    static long floor(long ms) { return ms - ms % (30 * 60_000L); }

    void setChannels(List<OtaLineup.Channel> channels) {
        rows.clear();
        String group = null;
        int y = 0;
        for (OtaLineup.Channel c : channels) {
            if (!c.group.equals(group)) {
                group = c.group;
                Row g = new Row(group, null); g.y = y; g.h = GROUP_H; y += GROUP_H; rows.add(g);
            }
            Row r = new Row(group, c); r.y = y; r.h = ROW_H; y += ROW_H; rows.add(r);
        }
        height = y;
        selected = -1;
        revalidateAll();
    }

    void setGuide(Map<String, List<Xmltv.Program>> g) { guide = g == null ? Collections.<String, List<Xmltv.Program>>emptyMap() : g; repaintAll(); }

    /** Moves the time line on as time passes (called every half minute). */
    void tick() {
        long f = floor(System.currentTimeMillis());
        if (f != start) { start = f; revalidateAll(); } else repaintAll();
    }

    List<Xmltv.Program> programs(OtaLineup.Channel c) {
        List<Xmltv.Program> p = c == null ? null : guide.get(c.number);
        return p == null ? Collections.<Xmltv.Program>emptyList() : p;
    }

    /** The programme on at {@code time}, or null. */
    Xmltv.Program at(OtaLineup.Channel c, long time) {
        for (Xmltv.Program p : programs(c)) if (p.start <= time && time < p.stop) return p;
        return null;
    }

    Xmltv.Program after(OtaLineup.Channel c, long time) {
        for (Xmltv.Program p : programs(c)) if (p.start >= time) return p;
        return null;
    }

    OtaLineup.Channel selectedChannel() { return selected >= 0 && selected < rows.size() ? rows.get(selected).channel : null; }

    int indexOf(String number) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).channel != null && rows.get(i).channel.number.equals(number)) return i;
        return -1;
    }

    /** The next channel row {@code step} channels away (wrapping round), from the selection. */
    int step(int step) {
        List<Integer> chans = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).channel != null) chans.add(i);
        if (chans.isEmpty()) return -1;
        int at = chans.indexOf(selected);
        if (at < 0) return chans.get(0);
        return chans.get(Math.floorMod(at + step, chans.size()));
    }

    void select(int i) {
        if (i < 0 || i >= rows.size() || rows.get(i).channel == null) return;
        selected = i;
        Row r = rows.get(i);
        scrollRectToVisible(new Rectangle(getVisibleRect().x, Math.max(0, r.y - (i > 0 && rows.get(i - 1).channel == null ? GROUP_H : 0)), 1, r.h + GROUP_H));
        repaintAll();
        onSelect.accept(r.channel);
    }

    int rowAt(int y) {
        for (int i = 0; i < rows.size(); i++) { Row r = rows.get(i); if (y >= r.y && y < r.y + r.h) return i; }
        return -1;
    }

    private int xOf(long time) { return (int) ((time - start) / 60_000L * PX_PER_MIN); }

    private void revalidateAll() { revalidate(); channelColumn.revalidate(); timeHeader.revalidate(); repaintAll(); }

    private void repaintAll() { repaint(); channelColumn.repaint(); timeHeader.repaint(); }

    @Override public Dimension getPreferredSize() { return new Dimension(HOURS * 60 * PX_PER_MIN, Math.max(height, ROW_H)); }

    @Override public String getToolTipText(MouseEvent e) {
        int i = rowAt(e.getY());
        if (i < 0 || rows.get(i).channel == null) return null;
        Xmltv.Program p = at(rows.get(i).channel, start + (long) e.getX() / PX_PER_MIN * 60_000L);
        if (p == null) return rows.get(i).channel.title();
        String desc = p.desc == null || p.desc.isEmpty() ? "" : "<br>" + escape(p.desc.length() > 300 ? p.desc.substring(0, 300) + "…" : p.desc);
        return "<html><div style='width:320px'><b>" + escape(p.title) + "</b><br>" + span(p) + desc + "</div></html>";
    }

    String span(Xmltv.Program p) { return clock.format(new Date(p.start)) + " – " + clock.format(new Date(p.stop)); }

    static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Rectangle clip = g.getClipBounds();
        g.setColor(BG);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);
        long now = System.currentTimeMillis(), end = start + HOURS * 3_600_000L;
        Font title = getFont().deriveFont(Font.BOLD, 14f), small = getFont().deriveFont(12f);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.y > clip.y + clip.height || r.y + r.h < clip.y) continue;
            if (r.channel == null) { g.setColor(PANEL); g.fillRect(clip.x, r.y, clip.width, r.h); continue; }
            List<Xmltv.Program> progs = programs(r.channel);
            boolean any = false;
            for (Xmltv.Program p : progs) {
                if (p.stop <= start || p.start >= end) continue;
                any = true;
                int x1 = Math.max(0, xOf(p.start)), x2 = Math.min(getWidth(), xOf(p.stop));
                block(g, x1, r.y, x2 - x1, r.h, p.title, span(p), p.start <= now && now < p.stop, i == selected, title, small);
            }
            if (!any) block(g, 0, r.y, getWidth(), r.h, r.channel.name.isEmpty() ? r.channel.number : r.channel.name, "No guide information", true, i == selected, title, small);
        }
        int nx = xOf(now);
        g.setColor(NOW_LINE);
        g.fillRect(nx - 1, clip.y, 2, clip.height);
    }

    private void block(Graphics2D g, int x, int y, int w, int h, String title, String sub, boolean now, boolean sel, Font tf, Font sf) {
        g.setColor(sel ? SELECTED : now ? BLOCK_NOW : BLOCK);
        g.fillRoundRect(x + 1, y + 2, Math.max(2, w - 3), h - 4, 8, 8);
        if (w < 24) return;
        Graphics2D t = (Graphics2D) g.create(x + 8, y, Math.max(0, w - 14), h);
        t.setFont(tf); t.setColor(TEXT);
        t.drawString(fit(t, title, w - 14), 0, 20);
        t.setFont(sf); t.setColor(sel ? TEXT : MUTED);
        t.drawString(fit(t, sub, w - 14), 0, 37);
        t.dispose();
    }

    static String fit(Graphics g, String s, int w) {
        FontMetrics m = g.getFontMetrics();
        if (s == null) return "";
        if (m.stringWidth(s) <= w) return s;
        for (int n = s.length() - 1; n > 0; n--) { String c = s.substring(0, n) + "…"; if (m.stringWidth(c) <= w) return c; }
        return "";
    }

    // ------------------------------------------------------------------ Scrollable

    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(900, ROW_H * 5); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return o == SwingConstants.VERTICAL ? ROW_H : 30 * PX_PER_MIN; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return o == SwingConstants.VERTICAL ? r.height - ROW_H : r.width - 30 * PX_PER_MIN; }
    @Override public boolean getScrollableTracksViewportWidth() { return false; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }

    // ------------------------------------------------------------------ fixed channel column and time header

    private final class ChannelColumn extends JComponent {
        @Override public Dimension getPreferredSize() { return new Dimension(CHANNEL_W, OtaGuide.this.getPreferredSize().height); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle clip = g.getClipBounds();
            g.setColor(BG);
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            Font num = getFont().deriveFont(Font.BOLD, 16f), name = getFont().deriveFont(13f), grp = getFont().deriveFont(Font.BOLD, 12f);
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.y > clip.y + clip.height || r.y + r.h < clip.y) continue;
                if (r.channel == null) {
                    g.setColor(PANEL); g.fillRect(0, r.y, getWidth(), r.h);
                    g.setFont(grp); g.setColor(ACCENT);
                    g.drawString(fit(g, r.group.toUpperCase(), getWidth() - 16), 10, r.y + 18);
                    continue;
                }
                boolean sel = i == selected, on = r.channel.number.equals(playing);
                g.setColor(sel ? SELECTED : PANEL);
                g.fillRoundRect(2, r.y + 2, getWidth() - 6, r.h - 4, 8, 8);
                g.setFont(num); g.setColor(on ? ACCENT : TEXT);
                g.drawString((on ? "▶ " : "") + r.channel.number, 10, r.y + 28);
                g.setFont(name); g.setColor(sel ? TEXT : MUTED);
                g.drawString(fit(g, r.channel.name + r.channel.hdTag(), getWidth() - 104), 96, r.y + 28);
            }
        }
    }

    private final class TimeHeader extends JComponent {
        @Override public Dimension getPreferredSize() { return new Dimension(OtaGuide.this.getPreferredSize().width, TIME_H); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(PANEL);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            for (int k = 0; k <= HOURS * 2; k++) {
                int x = k * 30 * PX_PER_MIN;
                g.setColor(LINE); g.fillRect(x, 6, 1, getHeight() - 6);
                g.setColor(TEXT); g.drawString(clock.format(new Date(start + k * 30 * 60_000L)), x + 6, 20);
            }
            g.setColor(NOW_LINE);
            g.fillRect(xOf(System.currentTimeMillis()) - 1, 0, 2, getHeight());
        }
    }
}
