package com.wygtv.tv;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitlesTest {
    @Test public void closedCaptionsWithoutLanguage() {
        Format f = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build();
        assertEquals("Closed captions", Subtitles.label(f, 1));
    }
    @Test public void languageNamesAndFlags() {
        Format cc = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setLanguage("en").build();
        assertTrue(Subtitles.label(cc, 1).endsWith("(CC)"));
        Format forced = new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage("es").setSelectionFlags(C.SELECTION_FLAG_FORCED).build();
        assertTrue(Subtitles.label(forced, 2).endsWith("(forced)"));
        Format plain = new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build();
        assertEquals("Track 3", Subtitles.label(plain, 3));
    }
}
