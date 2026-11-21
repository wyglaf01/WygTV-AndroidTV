package com.wygtv.tv;

import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;
import static org.junit.Assert.*;

public class EpgGridTest {
    static final long M = EpgGrid.MINUTE;
    static final long T0 = 1_795_291_200_000L; // a half-hour boundary

    static EpgGrid.Program p(long startMin, long endMin, String title) {
        return new EpgGrid.Program(T0 + startMin * M, T0 + endMin * M, title, "");
    }

    @Test public void parsesEpochSecondsAndFallsBackToServerText() {
        assertEquals(1_700_000_000_000L, EpgGrid.parseTime("1700000000", "ignored", TimeZone.getTimeZone("UTC")));
        assertEquals(0L, EpgGrid.parseTime("", "1970-01-01 00:00:00", TimeZone.getTimeZone("UTC")));
        assertEquals(-1L, EpgGrid.parseTime(null, "not a date", TimeZone.getTimeZone("UTC")));
        assertEquals(-1L, EpgGrid.parseTime(null, null, TimeZone.getTimeZone("UTC")));
    }

    @Test public void windowStartsOnTheHalfHour() {
        assertEquals(T0, EpgGrid.windowStart(T0 + 17 * M));
        assertEquals(T0 + 30 * M, EpgGrid.windowStart(T0 + 30 * M));
    }

    @Test public void cleanSortsDropsBrokenAndTrimsOverlaps() {
        List<EpgGrid.Program> out = EpgGrid.clean(Arrays.asList(p(30, 60, "B"), p(0, 40, "A"), p(50, 55, "inside"), p(70, 70, "empty"), null));
        assertEquals(2, out.size());
        assertEquals("A", out.get(0).title);
        assertEquals(T0 + 40 * M, out.get(1).start);
        assertEquals("B", out.get(1).title);
    }

    @Test public void cellsClipToWindowAndFillGaps() {
        List<EpgGrid.Program> progs = EpgGrid.clean(Arrays.asList(p(-30, 30, "Running"), p(60, 90, "Later"), p(120, 300, "Long")));
        List<EpgGrid.Cell> cells = EpgGrid.cells(progs, T0, T0 + EpgGrid.WINDOW);
        assertEquals(4, cells.size());
        assertEquals(T0, cells.get(0).start);
        assertEquals("Running", cells.get(0).program.title);
        assertNull(cells.get(1).program);
        assertEquals(T0 + 30 * M, cells.get(1).start);
        assertEquals("Later", cells.get(2).program.title);
        assertNull(cells.get(3).program);
        assertEquals(T0 + EpgGrid.WINDOW, cells.get(3).end);
    }

    @Test public void tinyGapsAreAbsorbedInsteadOfMakingSliverCells() {
        List<EpgGrid.Program> progs = Arrays.asList(
                new EpgGrid.Program(T0 + 20_000, T0 + 60 * M - 10_000, "A", ""),
                new EpgGrid.Program(T0 + 60 * M, T0 + EpgGrid.WINDOW - 5_000, "B", ""));
        List<EpgGrid.Cell> cells = EpgGrid.cells(progs, T0, T0 + EpgGrid.WINDOW);
        assertEquals(2, cells.size());
        assertEquals(T0, cells.get(0).start);
        assertEquals(T0 + 60 * M, cells.get(0).end);
        assertEquals(T0 + EpgGrid.WINDOW, cells.get(1).end);
    }

    @Test public void noDataGivesOneEmptyCell() {
        List<EpgGrid.Cell> cells = EpgGrid.cells(Arrays.asList(), T0, T0 + EpgGrid.WINDOW);
        assertEquals(1, cells.size());
        assertNull(cells.get(0).program);
    }

    @Test public void lookupsAndProgress() {
        List<EpgGrid.Program> progs = Arrays.asList(p(0, 60, "A"), p(60, 90, "B"));
        List<EpgGrid.Cell> cells = EpgGrid.cells(progs, T0, T0 + EpgGrid.WINDOW);
        assertEquals(1, EpgGrid.indexAt(cells, T0 + 75 * M));
        assertEquals(0, EpgGrid.indexAt(cells, T0 - M));
        assertEquals("A", EpgGrid.at(progs, T0 + 59 * M).title);
        assertEquals("B", EpgGrid.next(progs, progs.get(0)).title);
        assertNull(EpgGrid.next(progs, progs.get(1)));
        assertEquals(50, EpgGrid.progressPercent(progs.get(0), T0 + 30 * M));
        assertEquals(30, EpgGrid.minutesLeft(progs.get(0), T0 + 30 * M));
        assertEquals(1, EpgGrid.minutesLeft(progs.get(0), T0 + 59 * M + 1));
    }
}
