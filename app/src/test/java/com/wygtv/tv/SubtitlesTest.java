package com.wygtv.tv;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitlesTest {
    @Test public void closedCaptionsWithoutLanguage() {
        assertEquals("Closed captions", Subtitles.label(null, null, MimeTypes.APPLICATION_CEA608, 0, 0, 1));
    }
    @Test public void languageNamesAndFlags() {
        assertEquals("English (CC)", Subtitles.label("en", null, MimeTypes.APPLICATION_CEA608, 0, 0, 1));
        assertTrue(Subtitles.label("es", null, MimeTypes.TEXT_VTT, 0, C.SELECTION_FLAG_FORCED, 2).endsWith("(forced)"));
        assertEquals("Track 3", Subtitles.label(null, null, MimeTypes.APPLICATION_SUBRIP, 0, 0, 3));
        assertEquals("Director commentary", Subtitles.label("und", "Director commentary", MimeTypes.TEXT_VTT, 0, 0, 4));
    }
}
